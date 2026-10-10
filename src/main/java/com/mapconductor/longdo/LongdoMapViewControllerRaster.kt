package com.mapconductor.longdo

import com.mapconductor.core.raster.RasterHeaderRuleSet
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * ラスターレイヤーの反映。
 *
 * Longdo は WebView 上の JS 地図なので、レイヤーの追加・削除・不透明度は
 * すべて JS 文字列を組み立てて流し込む形になる。ID は他レイヤーとぶつからない
 * よう `mcrs_` / `mcrl_` を前置する。
 */
internal fun LongdoMapViewController.applyRaster(state: RasterLayerState) {
    RasterHeaderRuleSet.warnUnsupported(provider = "Longdo", state = state)
    val srcId = rasterSourceId(state.id)
    val layerId = rasterLayerId(state.id)
    val spec = rasterSourceSpec(state.source) ?: return // 未対応ソースはスキップ
    val prev = appliedRasters[state.id]
    val layerSpec = rasterLayerSpec(layerId, srcId, state.opacity, state.visible)
    when {
        prev == null ->
            runRasterJs(addRasterJs(srcId, layerId, spec, layerSpec))
        prev.source != state.source -> {
            runRasterJs(removeRasterJs(state.id))
            runRasterJs(addRasterJs(srcId, layerId, spec, layerSpec))
        }
        else -> {
            if (prev.opacity != state.opacity) runRasterJs(setRasterOpacityJs(layerId, state.opacity))
            if (prev.visible != state.visible) runRasterJs(setRasterVisibilityJs(layerId, state.visible))
        }
    }
    appliedRasters[state.id] = state
}

internal fun LongdoMapViewController.runRasterJs(js: String) {
    runCatching { longdoMap.run(js) {} }
}

/** MapLibre のラスターソース仕様（JSON）。未対応ソースは null。 */
internal fun LongdoMapViewController.rasterSourceSpec(source: RasterLayerSource): String? =
    when (source) {
        is RasterLayerSource.UrlTemplate ->
            JSONObject()
                .put("type", "raster")
                .put("tiles", JSONArray().put(source.template))
                .put("tileSize", source.tileSize)
                .apply {
                    source.minZoom?.let { put("minzoom", it) }
                    source.maxZoom?.let { put("maxzoom", it) }
                }.put("scheme", if (source.scheme == TileScheme.TMS) "tms" else "xyz")
                .toString()

        is RasterLayerSource.TileJson ->
            JSONObject().put("type", "raster").put("url", source.url).toString()

        is RasterLayerSource.ArcGisService -> null
    }

internal fun LongdoMapViewController.rasterLayerSpec(
    layerId: String,
    sourceId: String,
    opacity: Float,
    visible: Boolean,
): String =
    JSONObject()
        .put("id", layerId)
        .put("type", "raster")
        .put("source", sourceId)
        .put("paint", JSONObject().put("raster-opacity", opacity.toDouble()))
        .put("layout", JSONObject().put("visibility", if (visible) "visible" else "none"))
        .toString()

internal fun LongdoMapViewController.addRasterJs(
    sourceId: String,
    layerId: String,
    sourceSpec: String,
    layerSpec: String,
): String =
    """
    (function(){var m=map.Renderer; if(!m)return;
      var tries=0;
      function apply(){
        try{
          if(!m.getSource('$sourceId'))m.addSource('$sourceId',$sourceSpec);
          if(!m.getLayer('$layerId'))m.addLayer($layerSpec);
        }catch(e){ if(tries++<30)setTimeout(apply,150); }
      }
      apply();
    })()
    """.trimIndent()

/**
 * ベースレイヤ切替（= 内部スタイルの差し替え）のあとに、[rasters] を載せ直す JS。
 *
 * `style.load` で一度、保険に 1.5 秒後にもう一度。どちらも「無ければ足す」なので
 * 二重にはならない。差し替え前に走った分は差し替えで消え、あとの分が残る。
 */
internal fun LongdoMapViewController.readdRastersAfterStyleSwapJs(rasters: List<RasterLayerState>): String {
    val adds =
        rasters
            .mapNotNull { state ->
                val spec = rasterSourceSpec(state.source) ?: return@mapNotNull null
                val srcId = rasterSourceId(state.id)
                val layerId = rasterLayerId(state.id)
                val layerSpec = rasterLayerSpec(layerId, srcId, state.opacity, state.visible)
                "try{if(!m.getSource('$srcId'))m.addSource('$srcId',$spec);" +
                    "if(!m.getLayer('$layerId'))m.addLayer($layerSpec);}catch(e){}"
            }.joinToString("")
    return "(function(){var m=map.Renderer;if(!m)return;function re(){$adds}" +
        "m.once('style.load',function(){setTimeout(re,0);});setTimeout(re,1500);})()"
}

internal fun LongdoMapViewController.removeRasterJs(id: String): String {
    val srcId = rasterSourceId(id)
    val layerId = rasterLayerId(id)
    return "(function(){try{var m=map.Renderer;" +
        "if(m.getLayer('$layerId'))m.removeLayer('$layerId');" +
        "if(m.getSource('$srcId'))m.removeSource('$srcId');}catch(e){}})()"
}

internal fun LongdoMapViewController.setRasterOpacityJs(
    layerId: String,
    opacity: Float,
): String =
    "(function(){try{if(map.Renderer.getLayer('$layerId'))" +
        "map.Renderer.setPaintProperty('$layerId','raster-opacity',${opacity.toDouble()});}catch(e){}})()"

internal fun LongdoMapViewController.setRasterVisibilityJs(
    layerId: String,
    visible: Boolean,
): String =
    "(function(){try{if(map.Renderer.getLayer('$layerId'))" +
        "map.Renderer.setLayoutProperty('$layerId','visibility','${if (visible) "visible" else "none"}');}catch(e){}})()"

internal fun LongdoMapViewController.rasterSourceId(id: String): String = "mcrs_$id"

internal fun LongdoMapViewController.rasterLayerId(id: String): String = "mcrl_$id"
