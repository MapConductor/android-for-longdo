package com.mapconductor.longdo.polyline

import androidx.compose.ui.graphics.toArgb
import com.longdo.sdk3.LongdoMap
import com.mapconductor.core.features.normalize
import com.mapconductor.core.polyline.PolylineEntityInterface
import com.mapconductor.core.polyline.PolylineOverlayRendererInterface
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.spherical.createInterpolatePoints
import com.mapconductor.core.spherical.createLinearInterpolatePoints
import com.mapconductor.core.spherical.splitByMeridian
import org.json.JSONObject

/**
 * ポリラインを Longdo（内部 MapLibre GL）の `map.Renderer` へ反映するレンダラ。
 *
 * ポリライン 1 本につき GeoJSON ソース（MultiLineString）＋ line レイヤを 1 組追加する。
 * 測地線補間・子午線分割はコアの共通ユーティリティを再利用し、他プロバイダ（MapTiler 等）と同じ形状にする。
 * ヒットテストはコアの [com.mapconductor.core.polyline.PolylineManager] が担うため、本レンダラは描画のみを行う。
 */
class LongdoPolylineOverlayRenderer(
    private val longdoMap: LongdoMap,
) : PolylineOverlayRendererInterface<LongdoPolylineHandle> {
    override suspend fun onAdd(
        data: List<PolylineOverlayRendererInterface.AddParamsInterface>,
    ): List<LongdoPolylineHandle?> = data.map { addLine(it.state) }

    override suspend fun onChange(
        data: List<PolylineOverlayRendererInterface.ChangeParamsInterface<LongdoPolylineHandle>>,
    ): List<LongdoPolylineHandle?> =
        data.map { params ->
            val next = params.current.state
            // 頂点ドラッグ等で points が同一リストのまま in-place 書き換えされても検出できるよう、
            // 登録時に確定した prev のフィンガープリント（値）と現在値を比較する（参照比較は不可）。
            val prevFinger = params.prev.fingerPrint
            val nextFinger = next.fingerPrint()
            val geometryChanged =
                prevFinger.points != nextFinger.points || prevFinger.geodesic != nextFinger.geodesic
            val handle = params.prev.polyline
            // 形状が変わってもソース／レイヤは作り直さず、ソースのデータのみ差し替える（ちらつき・競合防止）。
            if (geometryChanged) updateGeometry(handle, next)
            updatePaint(handle, next)
            handle
        }

    override suspend fun onRemove(data: List<PolylineEntityInterface<LongdoPolylineHandle>>) {
        data.forEach { runJs(removeJs(it.polyline)) }
    }

    override suspend fun onPostProcess() {}

    /** 既知のポリラインを全て再追加する（地図 `ready` 後やスタイル再読込後の復元用）。 */
    fun reapply(handles: List<LongdoPolylineHandle>) {
        handles.forEach { runJs(addLineJs(it)) }
    }

    private fun addLine(state: PolylineState): LongdoPolylineHandle? {
        val feature = buildGeoJson(state) ?: return null
        val argb = state.strokeColor.toArgb()
        val handle =
            LongdoPolylineHandle(
                sourceId = "polyline-source-${state.id}",
                layerId = "polyline-layer-${state.id}",
                featureJson = feature,
                color = String.format("#%06X", 0xFFFFFF and argb),
                opacity = (argb ushr 24) / 255.0,
                width = state.strokeWidth.value.toDouble(),
            )
        runJs(addLineJs(handle))
        return handle
    }

    private fun updateGeometry(
        handle: LongdoPolylineHandle,
        state: PolylineState,
    ) {
        val feature = buildGeoJson(state) ?: return
        handle.featureJson = feature
        runJs(setDataJs(handle.sourceId, feature))
    }

    private fun updatePaint(
        handle: LongdoPolylineHandle,
        state: PolylineState,
    ) {
        val argb = state.strokeColor.toArgb()
        handle.color = String.format("#%06X", 0xFFFFFF and argb)
        handle.opacity = (argb ushr 24) / 255.0
        handle.width = state.strokeWidth.value.toDouble()
        runJs(setPaintJs(handle))
    }

    private fun runJs(js: String) {
        runCatching { longdoMap.run(js) {} }
    }

    /**
     * [PolylineState] から MultiLineString の GeoJSON Feature 文字列を生成する（頂点 2 未満は null）。
     * 座標は経度・緯度の順。子午線をまたぐ場合は複数セグメントに分割する。
     */
    private fun buildGeoJson(state: PolylineState): String? {
        if (state.points.size < 2) return null
        val interpolated =
            (if (state.geodesic) createInterpolatePoints(state.points) else createLinearInterpolatePoints(state.points))
                .map { it.normalize() }
        val segments = splitByMeridian(interpolated, state.geodesic).filter { it.size >= 2 }
        if (segments.isEmpty()) return null
        val coordinates =
            segments.joinToString(separator = ",") { segment ->
                segment.joinToString(separator = ",", prefix = "[", postfix = "]") { p ->
                    "[${p.longitude},${p.latitude}]"
                }
            }
        return "{\"type\":\"Feature\",\"geometry\":" +
            "{\"type\":\"MultiLineString\",\"coordinates\":[$coordinates]},\"properties\":{}}"
    }

    private fun addLineJs(handle: LongdoPolylineHandle): String {
        val layerSpec =
            JSONObject()
                .put("id", handle.layerId)
                .put("type", "line")
                .put("source", handle.sourceId)
                .put(
                    "layout",
                    JSONObject()
                        .put("line-cap", "round")
                        .put("line-join", "round")
                        .put("visibility", "visible"),
                ).put(
                    "paint",
                    JSONObject()
                        .put("line-color", handle.color)
                        .put("line-opacity", handle.opacity)
                        .put("line-width", handle.width),
                ).toString()
        return """
            (function(){var m=map.Renderer; if(!m)return;
              var tries=0;
              function apply(){
                try{
                  if(!m.getSource('${handle.sourceId}'))
                    m.addSource('${handle.sourceId}',{type:'geojson',data:${handle.featureJson}});
                  if(!m.getLayer('${handle.layerId}'))m.addLayer($layerSpec);
                }catch(e){ if(tries++<30)setTimeout(apply,150); }
              }
              apply();
            })()
            """.trimIndent()
    }

    private fun setDataJs(
        sourceId: String,
        featureJson: String,
    ): String =
        "(function(){try{var s=map.Renderer.getSource('$sourceId'); if(s)s.setData($featureJson);}catch(e){}})()"

    private fun setPaintJs(handle: LongdoPolylineHandle): String =
        "(function(){try{var m=map.Renderer; if(!m.getLayer('${handle.layerId}'))return;" +
            "m.setPaintProperty('${handle.layerId}','line-color','${handle.color}');" +
            "m.setPaintProperty('${handle.layerId}','line-opacity',${handle.opacity});" +
            "m.setPaintProperty('${handle.layerId}','line-width',${handle.width});}catch(e){}})()"

    private fun removeJs(handle: LongdoPolylineHandle): String =
        "(function(){try{var m=map.Renderer;" +
            "if(m.getLayer('${handle.layerId}'))m.removeLayer('${handle.layerId}');" +
            "if(m.getSource('${handle.sourceId}'))m.removeSource('${handle.sourceId}');}catch(e){}})()"
}

/**
 * Longdo のポリライン実体（GeoJSON ソース＋ラインレイヤ）を参照するハンドル。
 *
 * Longdo は WebView（MapLibre GL JS）で、SDK 経由でソース／レイヤのオブジェクトを保持できないため、
 * ソース／レイヤ id と最後に適用した GeoJSON・ペイントを保持し、`map.Renderer` への JS 反映と復元に用いる。
 */
data class LongdoPolylineHandle(
    val sourceId: String,
    val layerId: String,
    var featureJson: String,
    var color: String,
    var opacity: Double,
    var width: Double,
)
