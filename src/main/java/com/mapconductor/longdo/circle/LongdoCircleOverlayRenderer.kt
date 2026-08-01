package com.mapconductor.longdo.circle

import androidx.compose.ui.graphics.toArgb
import com.longdo.sdk3.LongdoMap
import com.mapconductor.core.circle.CircleEntityInterface
import com.mapconductor.core.circle.CircleOverlayRendererInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.geometry.OverlayGeoJson
import com.mapconductor.core.geometry.circleToRing
import org.json.JSONObject

/**
 * 円を Longdo（内部 MapLibre GL）の `map.Renderer` へ反映するレンダラ。
 *
 * MapLibre GL にネイティブの円レイヤは無いため、中心・半径から測地線上の多角形リングを生成し、ポリゴンと同じく
 * GeoJSON ソース＋塗り（fill）レイヤ・輪郭（line）レイヤで描画する（他プロバイダ MapTiler 等と同じ方式）。
 * 形状（中心・半径）変更時はソース／レイヤを作り直さず、ソースの `setData` でジオメトリのみ差し替える。
 * クリック判定はコアの [com.mapconductor.core.circle.CircleManager]（中心からの距離 ≤ 半径＝緯度経度ベース）が
 * 担うため、本レンダラは描画のみを行う。
 */
class LongdoCircleOverlayRenderer(
    private val longdoMap: LongdoMap,
) : CircleOverlayRendererInterface<LongdoCircleHandle> {
    override suspend fun onAdd(
        data: List<CircleOverlayRendererInterface.AddParamsInterface>,
    ): List<LongdoCircleHandle?> = data.map { addCircle(it.state) }

    override suspend fun onChange(
        data: List<CircleOverlayRendererInterface.ChangeParamsInterface<LongdoCircleHandle>>,
    ): List<LongdoCircleHandle?> =
        data.map { params ->
            val next = params.current.state
            // 中心・半径の変更は、登録時に確定した prev のフィンガープリント（値）と現在値の比較で検出する。
            val prevFinger = params.prev.fingerPrint
            val nextFinger = next.fingerPrint()
            val geometryChanged =
                prevFinger.center != nextFinger.center ||
                    prevFinger.radiusMeters != nextFinger.radiusMeters ||
                    prevFinger.geodesic != nextFinger.geodesic
            val handle = params.prev.circle
            // 形状が変わってもソース／レイヤは作り直さず、ソースのデータのみ差し替える（ちらつき・競合防止）。
            if (geometryChanged) updateGeometry(handle, next)
            updatePaint(handle, next)
            handle
        }

    override suspend fun onRemove(data: List<CircleEntityInterface<LongdoCircleHandle>>) {
        data.forEach { runJs(removeJs(it.circle)) }
    }

    override suspend fun onPostProcess() {}

    /** 既知の円を全て再追加する（地図 `ready` 後やスタイル再読込後の復元用）。 */
    fun reapply(handles: List<LongdoCircleHandle>) {
        handles.forEach { runJs(addJs(it)) }
    }

    private fun addCircle(state: CircleState): LongdoCircleHandle? {
        val feature = buildGeoJson(state) ?: return null
        val fillArgb = state.fillColor.toArgb()
        val strokeArgb = state.strokeColor.toArgb()
        val handle =
            LongdoCircleHandle(
                sourceId = "circle-source-${state.id}",
                fillLayerId = "circle-fill-${state.id}",
                lineLayerId = "circle-line-${state.id}",
                featureJson = feature,
                fillColor = String.format("#%06X", 0xFFFFFF and fillArgb),
                fillOpacity = (fillArgb ushr 24) / 255.0,
                strokeColor = String.format("#%06X", 0xFFFFFF and strokeArgb),
                strokeOpacity = (strokeArgb ushr 24) / 255.0,
                strokeWidth = state.strokeWidth.value.toDouble(),
            )
        runJs(addJs(handle))
        return handle
    }

    private fun updateGeometry(
        handle: LongdoCircleHandle,
        state: CircleState,
    ) {
        val feature = buildGeoJson(state) ?: return
        handle.featureJson = feature
        runJs(setDataJs(handle.sourceId, feature))
    }

    private fun updatePaint(
        handle: LongdoCircleHandle,
        state: CircleState,
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
     * 中心・半径から円周上の点列（多角形リング）を生成し、Polygon／MultiPolygon の GeoJSON Feature を作る。
     * 子午線をまたぐ場合は MultiPolygon へ分割する。半径 0 以下は null。
     */
    private fun buildGeoJson(state: CircleState): String? {
        // リングは中心経度まわりに連続化（unwrap）済み。MapLibre GL JS は ±180 を超える
        // 経度を扱えるため、±180 を跨ぐ円も分割せず 1 枚の Polygon として描画できる。
        val ring = circleToRing(state.center, state.radiusMeters, state.geodesic)
        if (ring.isEmpty()) return null
        return OverlayGeoJson.ringsFeature(listOf(ring))
    }

    private fun addJs(handle: LongdoCircleHandle): String {
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

    private fun setPaintJs(handle: LongdoCircleHandle): String =
        "(function(){try{var m=map.Renderer;" +
            "if(m.getLayer('${handle.fillLayerId}')){m.setPaintProperty('${handle.fillLayerId}','fill-color'," +
            "'${handle.fillColor}');" +
            "m.setPaintProperty('${handle.fillLayerId}','fill-opacity',${handle.fillOpacity});}" +
            "if(m.getLayer('${handle.lineLayerId}')){m.setPaintProperty('${handle.lineLayerId}','line-color'," +
            "'${handle.strokeColor}');m.setPaintProperty('${handle.lineLayerId}','line-opacity'," +
            "${handle.strokeOpacity});m.setPaintProperty('${handle.lineLayerId}','line-width'," +
            "${handle.strokeWidth});}}catch(e){}})()"

    private fun removeJs(handle: LongdoCircleHandle): String =
        "(function(){try{var m=map.Renderer;" +
            "if(m.getLayer('${handle.lineLayerId}'))m.removeLayer('${handle.lineLayerId}');" +
            "if(m.getLayer('${handle.fillLayerId}'))m.removeLayer('${handle.fillLayerId}');" +
            "if(m.getSource('${handle.sourceId}'))m.removeSource('${handle.sourceId}');}catch(e){}})()"
}

/**
 * Longdo の円（GeoJSON ソース＋塗りレイヤ＋輪郭レイヤ）を参照するハンドル。
 *
 * 円は中心・半径から生成した多角形リングとして表現し、ポリゴンと同じく塗り・輪郭の 2 レイヤで描画する。
 * Longdo は WebView（MapLibre GL JS）で SDK 経由のソース／レイヤオブジェクトを保持できないため、ソース／レイヤ
 * id と最後に適用した GeoJSON・ペイントを保持し、`map.Renderer` への JS 反映と復元に用いる。
 */
data class LongdoCircleHandle(
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
