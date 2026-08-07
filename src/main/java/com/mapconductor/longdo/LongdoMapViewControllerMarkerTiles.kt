package com.mapconductor.longdo

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerRenderingStrategyInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.longdo.marker.LongdoClusterMarkerRenderer
import com.mapconductor.longdo.marker.LongdoMarkerTileRenderer
import kotlinx.coroutines.flow.StateFlow

/**
 * マーカーをタイル画像として描く経路。
 *
 * Longdo にはネイティブのマーカーが無いため、マーカーを 1 枚のラスターレイヤーへ
 * 焼いて載せている。タップは描画結果ではなく [LongdoMarkerTileRenderer] が持つ
 * 元データに対する当たり判定で拾う。
 */
internal fun LongdoMapViewController.tileRenderer(): LongdoMarkerTileRenderer =
    markerTileRenderer ?: LongdoMarkerTileRenderer(
        markerTilingOptions ?: MarkerTilingOptions.Default,
    ).also { markerTileRenderer = it }

internal fun LongdoMapViewController.createMarkerRenderingSupport(): MarkerRenderingSupport<Any> =
    object : MarkerRenderingSupport<Any> {
        override fun createMarkerRenderer(
            strategy: MarkerRenderingStrategyInterface<Any>,
        ): MarkerOverlayRendererInterface<Any> =
            // クラスタ／単体マーカーは既存のコンポーズオーバーレイ（[markers] フロー）として描画する。
            LongdoClusterMarkerRenderer(holder) { rendered -> publishMarkers(rendered) }

        override fun createMarkerEventController(
            controller: StrategyMarkerController<Any>,
            renderer: MarkerOverlayRendererInterface<Any>,
        ): MarkerEventControllerInterface<Any> =
            // クリックはコンポーズのタップ（marker.onClick）で配送するため、イベントコントローラは空実装。
            object : MarkerEventControllerInterface<Any> {}

        override fun registerMarkerEventController(controller: MarkerEventControllerInterface<Any>) = Unit

        override val mapLoadedState: StateFlow<Boolean>
            get() = mapLoaded

        override fun onMarkerRenderingReady() {
            dispatchCameraToOverlays()
        }
    }

/**
 * 多数マーカーをマーカータイル（ラスターレイヤ）として描画する。
 * マーカーが無ければラスターレイヤを削除する。
 */
internal fun LongdoMapViewController.renderTiledMarkers(data: List<MarkerState>) {
    val state = tileRenderer().render(data)
    if (state != null) {
        applyMarkerTileRaster(state)
    } else {
        removeMarkerTileRaster()
    }
}

/** マーカータイル・ラスターを Longdo（MapLibre GL）へ適用する（[appliedRasters] とは独立管理）。 */
internal fun LongdoMapViewController.applyMarkerTileRaster(state: RasterLayerState) {
    val srcId = rasterSourceId(state.id)
    val layerId = rasterLayerId(state.id)
    val spec = rasterSourceSpec(state.source) ?: return
    val prev = markerTileState
    val layerSpec = rasterLayerSpec(layerId, srcId, state.opacity, state.visible)
    when {
        prev == null -> runRasterJs(addRasterJs(srcId, layerId, spec, layerSpec))
        prev.source != state.source -> {
            runRasterJs(removeRasterJs(state.id))
            runRasterJs(addRasterJs(srcId, layerId, spec, layerSpec))
        }
    }
    markerTileState = state
}

internal fun LongdoMapViewController.removeMarkerTileRaster() {
    markerTileState?.let { runRasterJs(removeRasterJs(it.id)) }
    markerTileState = null
}

/**
 * タップ座標付近のタイリング・マーカーを [MarkerState.onClick] へ配送する。
 *
 * @param point タップ座標。
 * @param nativeZoom Longdo ネイティブ（MapLibre）ズーム。
 */
internal fun LongdoMapViewController.handleMarkerTap(
    point: com.mapconductor.core.features.GeoPoint,
    nativeZoom: Double,
) {
    markerTileRenderer?.findMarkerAt(point, nativeZoom)?.let { it.onClick?.invoke(it) }
}
