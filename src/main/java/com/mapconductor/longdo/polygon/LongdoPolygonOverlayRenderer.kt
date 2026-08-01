package com.mapconductor.longdo.polygon

import androidx.compose.ui.graphics.toArgb
import com.longdo.sdk3.LongdoMap
import com.mapconductor.core.geometry.OverlayGeoJson
import com.mapconductor.core.geometry.buildUnwrappedPolygonRings
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonOverlayRendererInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polygon.unionHoles
import org.json.JSONObject

/**
 * ポリゴンを Longdo（内部 MapLibre GL）の `map.Renderer` へ反映するレンダラ。
 *
 * ポリゴン 1 個につき GeoJSON ソース（Polygon／MultiPolygon、穴対応）と、塗り（fill）レイヤ・輪郭（line）レイヤを
 * 1 組ずつ追加する。測地線補間・子午線分割・複数穴の結合はコアの共通ユーティリティを再利用し、他プロバイダ
 * （MapTiler 等）と同じ形状にする。ヒットテストはコアの [com.mapconductor.core.polygon.PolygonManager]（測地線対応
 * の巻き数判定）が担うため、本レンダラは描画のみを行う。
 */
class LongdoPolygonOverlayRenderer(
    private val longdoMap: LongdoMap,
) : PolygonOverlayRendererInterface<LongdoPolygonHandle> {
    override suspend fun onAdd(
        data: List<PolygonOverlayRendererInterface.AddParamsInterface>,
    ): List<LongdoPolygonHandle?> = data.map { addPolygon(it.state) }

    override suspend fun onChange(
        data: List<PolygonOverlayRendererInterface.ChangeParamsInterface<LongdoPolygonHandle>>,
    ): List<LongdoPolygonHandle?> =
        data.map { params ->
            val next = params.current.state
            // 形状変更の判定は、頂点ドラッグ等で points が同一リストのまま in-place で書き換わっても
            // 検出できるよう、登録時に確定した prev のフィンガープリント（値）と現在値を比較する。
            val prevFinger = params.prev.fingerPrint
            val nextFinger = next.fingerPrint()
            val geometryChanged =
                prevFinger.points != nextFinger.points ||
                    prevFinger.holes != nextFinger.holes ||
                    prevFinger.geodesic != nextFinger.geodesic
            val handle = params.prev.polygon
            // 形状が変わってもソース／レイヤは作り直さず、ソースのデータのみ差し替える（ちらつき・競合防止）。
            if (geometryChanged) updateGeometry(handle, next)
            updatePaint(handle, next)
            handle
        }

    override suspend fun onRemove(data: List<PolygonEntityInterface<LongdoPolygonHandle>>) {
        data.forEach { runJs(removeJs(it.polygon)) }
    }

    override suspend fun onPostProcess() {}

    /** 既知のポリゴンを全て再追加する（地図 `ready` 後やスタイル再読込後の復元用）。 */
    fun reapply(handles: List<LongdoPolygonHandle>) {
        handles.forEach { runJs(addPolygonJs(it)) }
    }

    private fun addPolygon(state: PolygonState): LongdoPolygonHandle? {
        val feature = buildGeoJson(state) ?: return null
        val fillArgb = state.fillColor.toArgb()
        val strokeArgb = state.strokeColor.toArgb()
        val handle =
            LongdoPolygonHandle(
                sourceId = "polygon-source-${state.id}",
                fillLayerId = "polygon-fill-${state.id}",
                lineLayerId = "polygon-line-${state.id}",
                featureJson = feature,
                fillColor = String.format("#%06X", 0xFFFFFF and fillArgb),
                fillOpacity = (fillArgb ushr 24) / 255.0,
                strokeColor = String.format("#%06X", 0xFFFFFF and strokeArgb),
                strokeOpacity = (strokeArgb ushr 24) / 255.0,
                strokeWidth = state.strokeWidth.value.toDouble(),
            )
        runJs(addPolygonJs(handle))
        return handle
    }

    private fun updateGeometry(
        handle: LongdoPolygonHandle,
        state: PolygonState,
    ) {
        val feature = buildGeoJson(state) ?: return
        handle.featureJson = feature
        runJs(setDataJs(handle.sourceId, feature))
    }

    private fun updatePaint(
        handle: LongdoPolygonHandle,
        state: PolygonState,
    ) {
        val fillArgb = state.fillColor.toArgb()
        val strokeArgb = state.strokeColor.toArgb()
        handle.fillColor = String.format("#%06X", 0xFFFFFF and fillArgb)
        handle.fillOpacity = (fillArgb ushr 24) / 255.0
        handle.strokeColor = String.format("#%06X", 0xFFFFFF and strokeArgb)
        handle.strokeOpacity = (strokeArgb ushr 24) / 255.0
        handle.strokeWidth = state.strokeWidth.value.toDouble()
        runJs(setPaintJs(handle))
    }

    private fun runJs(js: String) {
        runCatching { longdoMap.run(js) {} }
    }

    /**
     * [PolygonState] から Polygon／MultiPolygon の GeoJSON Feature 文字列を生成する（頂点 3 未満は null）。
     * 座標は経度・緯度の順。子午線をまたぐ場合は MultiPolygon に分割する（その場合は穴を含めない）。
     * 複数穴は偶奇規則の打ち消しを避けるため [unionHoles] で結合してから渡す。
     */
    private fun buildGeoJson(state: PolygonState): String? {
        val resolved = if (state.holes.size > 1) state.unionHoles() else state
        // unwrap 座標の外周 1 リング + 全穴。MapLibre GL JS は ±180 超の経度を扱えるため
        // 分割不要で、±180 跨ぎのポリゴンでも穴を保持できる。
        return OverlayGeoJson.polygonFeature(
            buildUnwrappedPolygonRings(resolved.points, resolved.holes, resolved.geodesic),
        )
    }

    private fun addPolygonJs(handle: LongdoPolygonHandle): String {
        val fillSpec =
            JSONObject()
                .put("id", handle.fillLayerId)
                .put("type", "fill")
                .put("source", handle.sourceId)
                .put(
                    "paint",
                    JSONObject().put("fill-color", handle.fillColor).put("fill-opacity", handle.fillOpacity),
                ).toString()
        val lineSpec =
            JSONObject()
                .put("id", handle.lineLayerId)
                .put("type", "line")
                .put("source", handle.sourceId)
                .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round"))
                .put(
                    "paint",
                    JSONObject()
                        .put("line-color", handle.strokeColor)
                        .put("line-opacity", handle.strokeOpacity)
                        .put("line-width", handle.strokeWidth),
                ).toString()
        return """
            (function(){var m=map.Renderer; if(!m)return;
              var tries=0;
              function apply(){
                try{
                  if(!m.getSource('${handle.sourceId}'))
                    m.addSource('${handle.sourceId}',{type:'geojson',data:${handle.featureJson}});
                  if(!m.getLayer('${handle.fillLayerId}'))m.addLayer($fillSpec);
                  if(!m.getLayer('${handle.lineLayerId}'))m.addLayer($lineSpec);
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

    private fun setPaintJs(handle: LongdoPolygonHandle): String =
        "(function(){try{var m=map.Renderer;" +
            "if(m.getLayer('${handle.fillLayerId}')){m.setPaintProperty('${handle.fillLayerId}','fill-color'," +
            "'${handle.fillColor}');" +
            "m.setPaintProperty('${handle.fillLayerId}','fill-opacity',${handle.fillOpacity});}" +
            "if(m.getLayer('${handle.lineLayerId}')){m.setPaintProperty('${handle.lineLayerId}','line-color'," +
            "'${handle.strokeColor}');m.setPaintProperty('${handle.lineLayerId}','line-opacity'," +
            "${handle.strokeOpacity});m.setPaintProperty('${handle.lineLayerId}','line-width'," +
            "${handle.strokeWidth});}}catch(e){}})()"

    private fun removeJs(handle: LongdoPolygonHandle): String =
        "(function(){try{var m=map.Renderer;" +
            "if(m.getLayer('${handle.lineLayerId}'))m.removeLayer('${handle.lineLayerId}');" +
            "if(m.getLayer('${handle.fillLayerId}'))m.removeLayer('${handle.fillLayerId}');" +
            "if(m.getSource('${handle.sourceId}'))m.removeSource('${handle.sourceId}');}catch(e){}})()"
}

/**
 * Longdo のポリゴン実体（GeoJSON ソース＋塗りレイヤ＋輪郭レイヤ）を参照するハンドル。
 *
 * Longdo は WebView（MapLibre GL JS）で、SDK 経由でソース／レイヤのオブジェクトを保持できないため、
 * ソース／レイヤ id と最後に適用した GeoJSON・ペイントを保持し、`map.Renderer` への JS 反映と復元に用いる。
 */
data class LongdoPolygonHandle(
    val sourceId: String,
    val fillLayerId: String,
    val lineLayerId: String,
    var featureJson: String,
    var fillColor: String,
    var fillOpacity: Double,
    var strokeColor: String,
    var strokeOpacity: Double,
    var strokeWidth: Double,
)
