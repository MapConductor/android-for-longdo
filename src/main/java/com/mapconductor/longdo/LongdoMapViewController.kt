package com.mapconductor.longdo

import com.longdo.sdk3.LongdoMap
import com.mapconductor.core.circle.CircleCapableInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageCapableInterface
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapUISettings
import com.mapconductor.core.marker.MarkerCapableInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonCapableInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineCapableInterface
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.raster.RasterLayerCapableInterface
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.longdo.circle.LongdoCircleController
import com.mapconductor.longdo.circle.LongdoCircleOverlayRenderer
import com.mapconductor.longdo.groundimage.LongdoGroundImageController
import com.mapconductor.longdo.groundimage.LongdoGroundImageOverlayRenderer
import com.mapconductor.longdo.marker.LongdoMarkerTileRenderer
import com.mapconductor.longdo.polygon.LongdoPolygonController
import com.mapconductor.longdo.polygon.LongdoPolygonOverlayRenderer
import com.mapconductor.longdo.polyline.LongdoPolylineController
import com.mapconductor.longdo.polyline.LongdoPolylineOverlayRenderer
import com.mapconductor.longdo.zoom.ZoomAltitudeConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MapConductor コアと Longdo Map API3 SDK（[LongdoMap]）を橋渡しするマップコントローラ。
 *
 * Longdo Map SDK は WebView（Longdo Map JS API3）ベースのため、カメラ操作は JS ブリッジ
 * （`LongdoMap.call`）経由で `map.location` / `map.zoom` / `map.bound` を呼び出す。マーカーやポリゴン等の
 * オーバーレイは各プロバイダの GL 実装とは仕組みが異なるため、本コントローラは地図表示とカメラ制御を担う。
 *
 * 地図の準備（`ready`）前に要求されたカメラ操作は [pendingCameraPosition] に退避し、[onMapReady] で適用する。
 */
class LongdoMapViewController(
    override val holder: LongdoMapViewHolder,
    override val mainCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
    override val defaultCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : BaseMapViewController(),
    LongdoMapViewControllerInterface,
    MarkerCapableInterface,
    RasterLayerCapableInterface,
    PolylineCapableInterface,
    PolygonCapableInterface,
    GroundImageCapableInterface,
    CircleCapableInterface {
    internal val longdoMap: LongdoMap
        get() = holder.map

    /** 適用済みラスターレイヤ（id → 最後に適用した状態）。差分適用と削除に用いる。 */
    internal val appliedRasters = mutableMapOf<String, RasterLayerState>()

    /**
     * ポリラインコントローラ。追加・更新・削除の差分計算とヒットテスト（緯度経度ベース）はコア基底
     * [com.mapconductor.core.polyline.PolylineController] が担い、描画は [LongdoPolylineOverlayRenderer] へ委譲する。
     */
    internal val polylineController: LongdoPolylineController =
        LongdoPolylineController(LongdoPolylineOverlayRenderer(longdoMap))

    /**
     * ポリゴンコントローラ。追加・更新・削除の差分計算とヒットテスト（測地線対応の巻き数判定）はコア基底
     * [com.mapconductor.core.polygon.PolygonController] が担い、描画は [LongdoPolygonOverlayRenderer] へ委譲する。
     */
    internal val polygonController: LongdoPolygonController =
        LongdoPolygonController(LongdoPolygonOverlayRenderer(longdoMap))

    /**
     * グラウンドイメージコントローラ。追加・更新・削除の差分計算とクリックのヒットテスト（bounds への内外判定
     * ＝緯度経度ベース）はコア基底 [com.mapconductor.core.groundimage.GroundImageController] が担い、描画（image
     * ソース＋ raster レイヤ）は [LongdoGroundImageOverlayRenderer] へ委譲する。
     */
    internal val groundImageController: LongdoGroundImageController =
        LongdoGroundImageController(LongdoGroundImageOverlayRenderer(longdoMap))

    /**
     * 円コントローラ。追加・更新・削除の差分計算とクリック判定（中心からの距離＝緯度経度ベース）はコア基底
     * [com.mapconductor.core.circle.CircleController] が担い、描画（中心・半径から生成した多角形リングの GeoJSON
     * ソース＋塗り／輪郭レイヤ）は [LongdoCircleOverlayRenderer] へ委譲する。
     */
    internal val circleController: LongdoCircleController =
        LongdoCircleController(LongdoCircleOverlayRenderer(longdoMap))

    init {
        // ポリラインのヒットテストはカメラ（ズーム）依存のタップ許容量を用いるため、カメラ通知を受け取れるよう
        // オーバーレイコントローラとして登録する（他プロバイダと同一の仕組み）。
        registerOverlayController(polylineController)
        registerOverlayController(polygonController)
        registerOverlayController(groundImageController)
        registerOverlayController(circleController)
    }

    /**
     * 現在のマーカー一覧。Longdo は WebView（MapLibre GL）のため、マーカーは GL シンボルではなく
     * コンポーズのオーバーレイとして描画する。ここで状態を保持し、[LongdoMapView] が
     * `map.Renderer.project` による投影座標に配置する。
     */
    private val _markers = MutableStateFlow<List<MarkerState>>(emptyList())
    val markers: StateFlow<List<MarkerState>> = _markers.asStateFlow()

    /** 拡張ファイルから合成済みマーカーを差し替えるための入口（`_markers` は private のまま）。 */
    internal fun publishMarkers(rendered: List<MarkerState>) {
        _markers.value = rendered
    }

    /**
     * true のとき、多数マーカーをマーカータイリング（ラスターレイヤ）で描画する。false（既定）では
     * 少数の対話的マーカーをコンポーズオーバーレイ（[markers] フロー）として描画する。
     * [LongdoMapView] が `markerTiling` の有無に応じて設定する。
     */
    var useMarkerLayer: Boolean = false

    /** マーカータイリング設定（[useMarkerLayer] と併せて設定）。 */
    var markerTilingOptions: MarkerTilingOptions? = null

    internal var markerTileRenderer: LongdoMarkerTileRenderer? = null

    /** マーカータイル・ラスターレイヤの適用状態（[appliedRasters] とは別管理）。 */
    internal var markerTileState: RasterLayerState? = null

    /** 地図の準備完了状態。マーカークラスタリング（android-marker-clustering）の開始判定に用いる。 */
    val mapLoaded: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** オーバーレイ（クラスタ算出用の [StrategyMarkerController] 等）へ通知する直近のカメラ（可視領域つき）。 */
    internal var latestOverlayCamera: MapCameraPosition? = null

    /**
     * マーカークラスタリング等のプラグインが解決する capability。
     *
     * レジストリはこのコントローラではなく **state が持つ**（react-sdk / ios-sdk と同じ）。
     * [LongdoMapView] がコントローラ生成時に `state.serviceRegistry` へ登録する。
     */
    val markerRenderingSupport: MarkerRenderingSupport<Any> = createMarkerRenderingSupport()

    /** 直近のカメラ（可視領域つき）を記録する。[LongdoMapView] のカメライベントから呼ばれる。 */
    fun setLatestOverlayCamera(camera: MapCameraPosition) {
        latestOverlayCamera = camera
    }

    /**
     * 直近のカメラ（可視領域つき）をオーバーレイコントローラ（クラスタ算出の [StrategyMarkerController] 等）へ
     * 通知する。クラスタは `camera.visibleRegion.bounds` を用いて表示範囲内のマーカーを再クラスタリングする。
     */
    fun dispatchCameraToOverlays() {
        val camera = latestOverlayCamera ?: return
        mainCoroutine.launch { runCatching { notifyMapCameraPosition(camera) } }
    }

    /** tilt < 0（仰角ビュー）の擬似表現に用いるカメラ高度換算器。 */
    internal val zoomConverter = ZoomAltitudeConverter()

    /** 地図の準備完了フラグ。`ready` イベント（あるいは map オブジェクト生成）で true になる。 */
    @Volatile
    internal var mapReady: Boolean = false

    /** ready 前に要求された最後のカメラ位置。ready 時に適用する。 */
    internal var pendingCameraPosition: MapCameraPosition? = null

    /**
     * 地図の準備完了時に呼ぶ。退避していたカメラ操作があれば適用する。
     * [LongdoMapView] の JS ブリッジ（`ready`）から呼ばれる。
     */
    override fun moveCamera(position: MapCameraPosition) = handleMoveCamera(position)

    override fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    ) = handleAnimateCamera(position, duration)

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) = handleFitBounds(bounds, padding)

    // 拡張ファイルからは基底クラスの protected へ触れないため、内部向けの入口。
    internal fun correctForCameraRestriction(current: MapCameraPosition): MapCameraPosition? =
        cameraRestrictionCorrection(current)

    internal suspend fun emitCameraPosition(position: MapCameraPosition) {
        notifyMapCameraPosition(position)
    }

    override fun applyUISettings(settings: MapUISettings) = applyGestureSettings(settings)

    fun onMapReady() {
        mapReady = true
        mapLoaded.value = true
        pendingCameraPosition?.let { position ->
            applyCamera(position)
            pendingCameraPosition = null
        }
        dispatchCameraToOverlays()
        // Gesture JS issued before ready is dropped by the page; re-apply now.
        applyUISettings(appliedUISettings)
    }

    override fun setMapDesignType(value: LongdoMapDesignTypeInterface) {
        if (!mapReady) return
        runCatching {
            longdoMap.call(
                "Layers.setBase",
                listOf(LongdoMap.LongdoStatic("Layers", value.layerName)),
            ) {}
        }
    }

    // --- MarkerCapableInterface ---

    override suspend fun compositionMarkers(data: List<MarkerState>) {
        if (useMarkerLayer) {
            renderTiledMarkers(data)
        } else {
            _markers.value = data
        }
    }

    override suspend fun updateMarker(state: MarkerState) {
        if (useMarkerLayer) {
            val current = markerTileRenderer?.markers ?: return
            renderTiledMarkers(current.map { if (it.id == state.id) state else it })
        } else {
            _markers.value = _markers.value.map { if (it.id == state.id) state else it }
        }
    }

    override fun hasMarker(state: MarkerState): Boolean =
        if (useMarkerLayer) {
            markerTileRenderer?.markers?.any { it.id == state.id } ?: false
        } else {
            _markers.value.any { it.id == state.id }
        }

    @Deprecated("Use MarkerState.onDragStart instead.")
    override fun setOnMarkerDragStart(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onDrag instead.")
    override fun setOnMarkerDrag(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onDragEnd instead.")
    override fun setOnMarkerDragEnd(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onAnimateStart instead.")
    override fun setOnMarkerAnimateStart(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onAnimateEnd instead.")
    override fun setOnMarkerAnimateEnd(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onClick instead.")
    override fun setOnMarkerClickListener(listener: OnMarkerEventHandler?) = Unit

    // --- RasterLayerCapableInterface ---
    //
    // Longdo（内部 MapLibre GL）の `map.Renderer`（= MapLibre map）へラスターソース／レイヤを追加して描画する。

    override suspend fun compositionRasterLayers(data: List<RasterLayerState>) {
        val newIds = data.map { it.id }.toSet()
        appliedRasters.keys.filter { it !in newIds }.toList().forEach { id ->
            runRasterJs(removeRasterJs(id))
            appliedRasters.remove(id)
        }
        data.forEach { applyRaster(it) }
    }

    override suspend fun updateRasterLayer(state: RasterLayerState) {
        applyRaster(state)
    }

    override fun hasRasterLayer(state: RasterLayerState): Boolean = appliedRasters.containsKey(state.id)

    // --- PolylineCapableInterface ---
    //
    // ポリラインはコア基底 [com.mapconductor.core.polyline.PolylineController] が差分計算・状態管理・ヒットテスト
    // （[com.mapconductor.core.polyline.PolylineManager] による緯度経度ベースの判定）を担い、実際の描画（Longdo
    // 内部 MapLibre GL の `map.Renderer` への GeoJSON ソース＋line レイヤ反映）は [LongdoPolylineOverlayRenderer]
    // が担う。これにより他プロバイダ（MapTiler 等）と内部構造・タップ判定を一致させる。

    override suspend fun compositionPolylines(data: List<PolylineState>) {
        polylineController.add(data)
    }

    override suspend fun updatePolyline(state: PolylineState) {
        polylineController.update(state)
    }

    override fun hasPolyline(state: PolylineState): Boolean =
        polylineController.polylineManager.getEntity(state.id) != null

    @Deprecated("Use PolylineState.onClick instead.")
    override fun setOnPolylineClickListener(listener: OnPolylineEventHandler?) {
        polylineController.clickListener = listener
    }

    // --- PolygonCapableInterface ---
    //
    // ポリゴンはコア基底 [com.mapconductor.core.polygon.PolygonController] が差分計算・状態管理・ヒットテスト
    // （[com.mapconductor.core.polygon.PolygonManager] による測地線対応の巻き数判定＝緯度経度ベースの内外判定）を
    // 担い、実際の描画（`map.Renderer` への GeoJSON ソース＋塗り／輪郭レイヤ反映）は [LongdoPolygonOverlayRenderer]
    // が担う。測地線ポリゴンでも描画曲線と一致した内外判定になるため、他プロバイダと同じ結果になる。

    override suspend fun compositionPolygons(data: List<PolygonState>) {
        polygonController.add(data)
    }

    override suspend fun updatePolygon(state: PolygonState) {
        polygonController.update(state)
    }

    override fun hasPolygon(state: PolygonState): Boolean = polygonController.polygonManager.getEntity(state.id) != null

    @Deprecated("Use PolygonState.onClick instead.")
    override fun setOnPolygonClickListener(listener: OnPolygonEventHandler?) {
        polygonController.clickListener = listener
    }

    // --- GroundImageCapableInterface ---
    //
    // グラウンドイメージはコア基底 [com.mapconductor.core.groundimage.GroundImageController] が差分計算・状態管理・
    // クリック判定を担い、描画（`map.Renderer` への MapLibre image ソース＋ raster レイヤ反映）は
    // [LongdoGroundImageOverlayRenderer] が担う。MapLibre GL JS の image ソースが Longdo での最適な表現方法。

    override suspend fun compositionGroundImages(data: List<GroundImageState>) {
        groundImageController.add(data)
    }

    override suspend fun updateGroundImage(state: GroundImageState) {
        groundImageController.update(state)
    }

    override fun hasGroundImage(state: GroundImageState): Boolean =
        groundImageController.groundImageManager.getEntity(state.id) != null

    @Deprecated("Use GroundImageState.onClick instead.")
    override fun setOnGroundImageClickListener(listener: OnGroundImageEventHandler?) {
        groundImageController.clickListener = listener
    }

    // --- CircleCapableInterface ---
    //
    // 円はコア基底 [com.mapconductor.core.circle.CircleController] が差分計算・状態管理・クリック判定（中心からの
    // 距離 ≤ 半径＝緯度経度ベース）を担い、描画（`map.Renderer` への多角形リング GeoJSON ソース＋塗り／輪郭
    // レイヤ反映）は [LongdoCircleOverlayRenderer] が担う。MapLibre GL に円レイヤは無いため多角形近似で表現する。

    override suspend fun compositionCircles(data: List<CircleState>) {
        circleController.add(data)
    }

    override suspend fun updateCircle(state: CircleState) {
        circleController.update(state)
    }

    override fun hasCircle(state: CircleState): Boolean = circleController.circleManager.getEntity(state.id) != null

    @Deprecated("Use CircleState.onClick instead.")
    override fun setOnCircleClickListener(listener: OnCircleEventHandler?) {
        circleController.clickListener = listener
    }

    /** 既知のポリライン／ポリゴン／グラウンドイメージ／円を再適用する（地図 `ready` 後やスタイル再読込後の復元用）。 */
    fun reapplyOverlays() {
        polylineController.reapply()
        polygonController.reapply()
        groundImageController.reapply()
        circleController.reapply()
    }

    override suspend fun clearOverlays() {
        _markers.value = emptyList()
        appliedRasters.keys.toList().forEach { id -> runRasterJs(removeRasterJs(id)) }
        appliedRasters.clear()
        polylineController.clear()
        polygonController.clear()
        groundImageController.clear()
        circleController.clear()
        removeMarkerTileRaster()
        markerTileRenderer?.clear()
    }

    internal var appliedUISettings: MapUISettings = MapUISettings.Default

    override fun destroy() {
        markerTileRenderer?.clear()
        markerTileRenderer = null
        super.destroy()
    }

    override fun getControllers(): Map<String, OverlayControllerInterface<*, *>> =
        mapOf(
            "polyline" to polylineController,
            "polygon" to polygonController,
            "ground_image" to groundImageController,
            "circle" to circleController,
        )

    companion object {
        /**
         * Longdo（内部 MapLibre GL / 512px ベクタタイル）ネイティブズームと統一ズーム（Google Maps 準拠）の差。
         *
         * Longdo Map API3 は MapLibre GL ベースで、そのズームは Google Maps より 1 段小さい。
         * すなわち `GoogleZoom ≈ LongdoZoom + 1.0`（MapLibre / MapTiler 実装と同一値）。
         * これにより Camera Sync で Google Maps とズームレベルが一致する。
         */
        private const val LONGDO_TO_GOOGLE_ZOOM_OFFSET = 1.0

        private const val MIN_ZOOM = 1.0
        private const val MAX_ZOOM = 20.0

        /** Longdo（MapLibre GL）が表現できる最大 pitch（傾き）。 */
        internal const val MAX_PITCH = 60.0

        /** 統一ズーム（Google）→ Longdo ネイティブズーム。 */
        internal fun coreZoomToLongdo(coreZoom: Double): Double =
            (coreZoom - LONGDO_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM, MAX_ZOOM)

        /** Longdo ネイティブズーム → 統一ズーム（Google）。 */
        internal fun longdoZoomToCore(longdoZoom: Double): Double =
            (longdoZoom + LONGDO_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
