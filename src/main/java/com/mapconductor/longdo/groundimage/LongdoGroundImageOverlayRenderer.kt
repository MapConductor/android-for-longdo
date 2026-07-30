package com.mapconductor.longdo.groundimage

import com.longdo.sdk3.LongdoMap
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageEntityInterface
import com.mapconductor.core.groundimage.GroundImageOverlayRendererInterface
import com.mapconductor.core.groundimage.GroundImageState
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64

/**
 * グラウンドイメージ（地理座標に貼り付けた画像）を Longdo（内部 MapLibre GL）の `map.Renderer` へ反映するレンダラ。
 *
 * Longdo の地図描画は MapLibre GL JS のため、他プロバイダ（MapTiler 等）と同じく MapLibre の image ソース
 * （4 隅の座標＋画像 URL）＋ raster レイヤで描画するのが最適解となる。画像は [Drawable] を PNG にエンコードして
 * `data:image/png;base64,...` の data URL として渡すため、追加のローカルサーバやファイル配置を必要としない。
 *
 * 更新時はソース／レイヤを作り直さず、画像変更は image ソースの `updateImage`、bounds 変更は `setCoordinates`、
 * 不透明度変更は raster レイヤの `raster-opacity` で差分のみ反映し、ちらつきと再生成の競合を避ける。
 * クリック判定はコアの [com.mapconductor.core.groundimage.GroundImageManager]（bounds への内外判定＝緯度経度
 * ベース）が担うため、本レンダラは描画のみを行う。
 */
class LongdoGroundImageOverlayRenderer(
    private val longdoMap: LongdoMap,
) : GroundImageOverlayRendererInterface<LongdoGroundImageHandle> {
    override suspend fun onAdd(
        data: List<GroundImageOverlayRendererInterface.AddParamsInterface>,
    ): List<LongdoGroundImageHandle?> = data.map { addGroundImage(it.state) }

    override suspend fun onChange(
        data: List<GroundImageOverlayRendererInterface.ChangeParamsInterface<LongdoGroundImageHandle>>,
    ): List<LongdoGroundImageHandle?> =
        data.map { params -> updateGroundImage(params.prev.groundImage, params.current.state) }

    override suspend fun onRemove(data: List<GroundImageEntityInterface<LongdoGroundImageHandle>>) {
        data.forEach { runJs(removeJs(it.groundImage)) }
    }

    override suspend fun onPostProcess() {}

    /** 既知のグラウンドイメージを全て再追加する（地図 `ready` 後やスタイル再読込後の復元用）。 */
    fun reapply(handles: List<LongdoGroundImageHandle>) {
        handles.forEach { runJs(addJs(it)) }
    }

    private fun addGroundImage(state: GroundImageState): LongdoGroundImageHandle? {
        val coordinates = state.bounds.toCoordinatesJson() ?: return null
        val finger = state.fingerPrint()
        val handle =
            LongdoGroundImageHandle(
                sourceId = "groundimage-source-${state.id}",
                layerId = "groundimage-layer-${state.id}",
                imageUrl = imageDataUrl(state.image),
                coordinatesJson = coordinates,
                opacity = state.opacity.coerceIn(0f, 1f).toDouble(),
                appliedBounds = finger.bounds,
                appliedImage = finger.image,
                appliedOpacity = finger.opacity,
            )
        runJs(addJs(handle))
        return handle
    }

    private fun updateGroundImage(
        handle: LongdoGroundImageHandle,
        state: GroundImageState,
    ): LongdoGroundImageHandle {
        val coordinates = state.bounds.toCoordinatesJson() ?: return handle
        val finger = state.fingerPrint()
        when {
            finger.image != handle.appliedImage -> {
                // 画像変更：座標も同時に確定するため updateImage に url と coordinates を渡す。
                handle.imageUrl = imageDataUrl(state.image)
                handle.coordinatesJson = coordinates
                runJs(updateImageJs(handle))
            }
            finger.bounds != handle.appliedBounds -> {
                handle.coordinatesJson = coordinates
                runJs(setCoordinatesJs(handle))
            }
        }
        if (finger.opacity != handle.appliedOpacity) {
            handle.opacity = state.opacity.coerceIn(0f, 1f).toDouble()
            runJs(setOpacityJs(handle))
        }
        handle.appliedBounds = finger.bounds
        handle.appliedImage = finger.image
        handle.appliedOpacity = finger.opacity
        return handle
    }

    private fun runJs(js: String) {
        runCatching { longdoMap.run(js) {} }
    }

    /**
     * [GeoRectBounds] を MapLibre GL の image ソース座標順（左上・右上・右下・左下、各 [lng, lat]）の
     * JSON 配列文字列へ変換する。南西・北東いずれかが未設定なら null。
     */
    private fun GeoRectBounds.toCoordinatesJson(): String? {
        val sw = southWest ?: return null
        val ne = northEast ?: return null
        return "[[${sw.longitude},${ne.latitude}],[${ne.longitude},${ne.latitude}]," +
            "[${ne.longitude},${sw.latitude}],[${sw.longitude},${sw.latitude}]]"
    }

    /**
     * [Drawable] を PNG にエンコードし、`data:image/png;base64,...` の data URL 文字列として返す。
     * base64 は単一引用符・バックスラッシュを含まないため、JS の文字列リテラル内へそのまま埋め込める。
     */
    private fun imageDataUrl(drawable: Drawable): String {
        val bitmap = drawable.toBitmap()
        val bytes =
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap
        val width = intrinsicWidth.takeIf { it > 0 } ?: 1
        val height = intrinsicHeight.takeIf { it > 0 } ?: 1
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val oldBounds = Rect(bounds)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bounds = oldBounds
        return bitmap
    }

    private fun addJs(handle: LongdoGroundImageHandle): String {
        val layerSpec =
            JSONObject()
                .put("id", handle.layerId)
                .put("type", "raster")
                .put("source", handle.sourceId)
                .put("paint", JSONObject().put("raster-opacity", handle.opacity))
                .put("layout", JSONObject().put("visibility", "visible"))
                .toString()
        return """
            (function(){var m=map.Renderer; if(!m)return;
              var tries=0;
              function apply(){
                try{
                  if(!m.getSource('${handle.sourceId}'))
                    m.addSource('${handle.sourceId}',
                      {type:'image',url:'${handle.imageUrl}',coordinates:${handle.coordinatesJson}});
                  if(!m.getLayer('${handle.layerId}'))m.addLayer($layerSpec);
                }catch(e){ if(tries++<30)setTimeout(apply,150); }
              }
              apply();
            })()
            """.trimIndent()
    }

    private fun updateImageJs(handle: LongdoGroundImageHandle): String =
        "(function(){try{var s=map.Renderer.getSource('${handle.sourceId}');" +
            "if(s&&s.updateImage){s.updateImage({url:'${handle.imageUrl}'," +
            "coordinates:${handle.coordinatesJson}});}}catch(e){}})()"

    private fun setCoordinatesJs(handle: LongdoGroundImageHandle): String =
        "(function(){try{var s=map.Renderer.getSource('${handle.sourceId}');" +
            "if(s&&s.setCoordinates){s.setCoordinates(${handle.coordinatesJson});}}catch(e){}})()"

    private fun setOpacityJs(handle: LongdoGroundImageHandle): String =
        "(function(){try{if(map.Renderer.getLayer('${handle.layerId}'))" +
            "map.Renderer.setPaintProperty('${handle.layerId}','raster-opacity',${handle.opacity});}catch(e){}})()"

    private fun removeJs(handle: LongdoGroundImageHandle): String =
        "(function(){try{var m=map.Renderer;" +
            "if(m.getLayer('${handle.layerId}'))m.removeLayer('${handle.layerId}');" +
            "if(m.getSource('${handle.sourceId}'))m.removeSource('${handle.sourceId}');}catch(e){}})()"
}

/**
 * Longdo のグラウンドイメージ（image ソース＋ raster レイヤ）を参照するハンドル。
 *
 * Longdo は WebView（MapLibre GL JS）で SDK 経由のソース／レイヤオブジェクトを保持できないため、
 * ソース／レイヤ id と最後に適用した画像 data URL・座標・不透明度を保持して `map.Renderer` への JS 反映と
 * 復元に用いる。変更検出のため、適用済みの bounds／image／opacity のハッシュ（[appliedBounds] 等）も保持する。
 */
data class LongdoGroundImageHandle(
    val sourceId: String,
    val layerId: String,
    var imageUrl: String,
    var coordinatesJson: String,
    var opacity: Double,
    var appliedBounds: Int,
    var appliedImage: Int,
    var appliedOpacity: Int,
)
