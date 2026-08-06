package com.mapconductor.longdo

import com.longdo.sdk3.LongdoMap
import com.mapconductor.core.circle.CircleCapableInterface
import com.mapconductor.core.circle.CircleEvent
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageCapableInterface
import com.mapconductor.core.groundimage.GroundImageEvent
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapGesture
import com.mapconductor.core.map.MapUISettings
import com.mapconductor.core.map.MapUISettingsDiagnostics
import com.mapconductor.core.marker.MarkerCapableInterface
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerRenderingStrategyInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonCapableInterface
import com.mapconductor.core.polygon.PolygonEvent
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineCapableInterface
import com.mapconductor.core.polyline.PolylineEvent
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.raster.RasterHeaderRuleSet
import com.mapconductor.core.raster.RasterLayerCapableInterface
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.longdo.circle.LongdoCircleController
import com.mapconductor.longdo.circle.LongdoCircleOverlayRenderer
import com.mapconductor.longdo.groundimage.LongdoGroundImageController
import com.mapconductor.longdo.groundimage.LongdoGroundImageOverlayRenderer
import com.mapconductor.longdo.marker.LongdoClusterMarkerRenderer
import com.mapconductor.longdo.marker.LongdoMarkerTileRenderer
import com.mapconductor.longdo.polygon.LongdoPolygonController
import com.mapconductor.longdo.polygon.LongdoPolygonOverlayRenderer
import com.mapconductor.longdo.polyline.LongdoPolylineController
import com.mapconductor.longdo.polyline.LongdoPolylineOverlayRenderer
import com.mapconductor.longdo.zoom.ZoomAltitudeConverter
import kotlin.math.abs
import kotlin.math.tan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
    private val longdoMap: LongdoMap
        get() = holder.map

    /** 適用済みラスターレイヤ（id → 最後に適用した状態）。差分適用と削除に用いる。 */
    private val appliedRasters = mutableMapOf<String, RasterLayerState>()

    /**
     * ポリラインコントローラ。追加・更新・削除の差分計算とヒットテスト（緯度経度ベース）はコア基底
     * [com.mapconductor.core.polyline.PolylineController] が担い、描画は [LongdoPolylineOverlayRenderer] へ委譲する。
     */
    private val polylineController: LongdoPolylineController =
        LongdoPolylineController(LongdoPolylineOverlayRenderer(longdoMap))

    /**
     * ポリゴンコントローラ。追加・更新・削除の差分計算とヒットテスト（測地線対応の巻き数判定）はコア基底
     * [com.mapconductor.core.polygon.PolygonController] が担い、描画は [LongdoPolygonOverlayRenderer] へ委譲する。
     */
    private val polygonController: LongdoPolygonController =
        LongdoPolygonController(LongdoPolygonOverlayRenderer(longdoMap))

    /**
     * グラウンドイメージコントローラ。追加・更新・削除の差分計算とクリックのヒットテスト（bounds への内外判定
     * ＝緯度経度ベース）はコア基底 [com.mapconductor.core.groundimage.GroundImageController] が担い、描画（image
     * ソース＋ raster レイヤ）は [LongdoGroundImageOverlayRenderer] へ委譲する。
     */
    private val groundImageController: LongdoGroundImageController =
        LongdoGroundImageController(LongdoGroundImageOverlayRenderer(longdoMap))

    /**
     * 円コントローラ。追加・更新・削除の差分計算とクリック判定（中心からの距離＝緯度経度ベース）はコア基底
     * [com.mapconductor.core.circle.CircleController] が担い、描画（中心・半径から生成した多角形リングの GeoJSON
     * ソース＋塗り／輪郭レイヤ）は [LongdoCircleOverlayRenderer] へ委譲する。
     */
    private val circleController: LongdoCircleController =
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

    /**
     * true のとき、多数マーカーをマーカータイリング（ラスターレイヤ）で描画する。false（既定）では
     * 少数の対話的マーカーをコンポーズオーバーレイ（[markers] フロー）として描画する。
     * [LongdoMapView] が `markerTiling` の有無に応じて設定する。
     */
    var useMarkerLayer: Boolean = false

    /** マーカータイリング設定（[useMarkerLayer] と併せて設定）。 */
    var markerTilingOptions: MarkerTilingOptions? = null

    private var markerTileRenderer: LongdoMarkerTileRenderer? = null

    /** マーカータイル・ラスターレイヤの適用状態（[appliedRasters] とは別管理）。 */
    private var markerTileState: RasterLayerState? = null

    private fun tileRenderer(): LongdoMarkerTileRenderer =
        markerTileRenderer ?: LongdoMarkerTileRenderer(
            markerTilingOptions ?: MarkerTilingOptions.Default,
        ).also { markerTileRenderer = it }

    /** 地図の準備完了状態。マーカークラスタリング（android-marker-clustering）の開始判定に用いる。 */
    val mapLoaded: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** オーバーレイ（クラスタ算出用の [StrategyMarkerController] 等）へ通知する直近のカメラ（可視領域つき）。 */
    private var latestOverlayCamera: MapCameraPosition? = null

    /**
     * マーカークラスタリング等のプラグインが解決する capability。
     *
     * レジストリはこのコントローラではなく **state が持つ**（react-sdk / ios-sdk と同じ）。
     * [LongdoMapView] がコントローラ生成時に `state.serviceRegistry` へ登録する。
     */
    val markerRenderingSupport: MarkerRenderingSupport<Any> = createMarkerRenderingSupport()

    private fun createMarkerRenderingSupport(): MarkerRenderingSupport<Any> =
        object : MarkerRenderingSupport<Any> {
            override fun createMarkerRenderer(
                strategy: MarkerRenderingStrategyInterface<Any>,
            ): MarkerOverlayRendererInterface<Any> =
                // クラスタ／単体マーカーは既存のコンポーズオーバーレイ（[markers] フロー）として描画する。
                LongdoClusterMarkerRenderer(holder) { rendered -> _markers.value = rendered }

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
    private val zoomConverter = ZoomAltitudeConverter()

    /** 地図の準備完了フラグ。`ready` イベント（あるいは map オブジェクト生成）で true になる。 */
    @Volatile
    private var mapReady: Boolean = false

    /** ready 前に要求された最後のカメラ位置。ready 時に適用する。 */
    private var pendingCameraPosition: MapCameraPosition? = null

    /**
     * 地図の準備完了時に呼ぶ。退避していたカメラ操作があれば適用する。
     * [LongdoMapView] の JS ブリッジ（`ready`）から呼ばれる。
     */
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

    /**
     * カメラ停止時に [setCameraRestriction] の制限違反を補正する。補正したら true。
     *
     * Longdo Map API3 には（Google の `setLatLngBoundsForCameraTarget` に相当する）
     * カメラ範囲制限の JS API が無いため、android-sdk の HERE/ArcGIS/TomTom と同じく
     * カメラ停止時に矩形内へクランプして再適用する方式で制限する。
     * 再適用すると再度カメライベントが発火し、そこでは補正不要になり通常フローへ進む。
     */
    fun applyCameraRestrictionCorrectionIfNeeded(current: MapCameraPosition): Boolean {
        val corrected = cameraRestrictionCorrection(current) ?: return false
        moveCamera(corrected)
        return true
    }

    override fun moveCamera(position: MapCameraPosition) {
        if (mapReady) applyCamera(position) else pendingCameraPosition = position
    }

    override fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    ) {
        // Longdo の JS ブリッジでは中心（location）とズーム（zoom）が別コマンドで、双方をアニメーション指定
        // すると後発のズームがパンのアニメーションを打ち切ってしまい中心が移動しない。カメラ同期では同期先が
        // ソース側の連続イベントを追って滑らかに追従するため、ここでは瞬時に確定位置へ適用する。
        if (mapReady) applyCamera(position) else pendingCameraPosition = position
    }

    /**
     * Longdo の `map.location` / `map.zoom` / `map.rotate` / `map.pitch` を呼んでカメラを確定位置へ即時移動する。
     *
     * - ズームは統一ズーム（Google）→ Longdo ネイティブズームへ変換して渡す。
     * - 方位（[MapCameraPosition.bearing]）は Longdo の `rotate`（北から時計回り＝Google と同一規約）へ渡す。
     * - 傾き（[MapCameraPosition.tilt]）は Longdo の `pitch` へ渡す。負の傾き（仰角ビュー）は [nativeCameraFor] で
     *   前進ターゲット＋正 pitch に擬似変換する。
     *
     * すべて `animate=false`（即時）で呼ぶ。中心・ズーム・方位・傾きのアニメーションは別コマンドのため、
     * いずれかをアニメーション指定すると後続コマンドが直前のアニメーションを打ち切ってしまう。
     */
    private fun applyCamera(position: MapCameraPosition) {
        val native = nativeCameraFor(position)
        runCatching {
            longdoMap.call("location", listOf(native.target.toLongdoLocation(), false)) {}
            longdoMap.call("zoom", listOf(native.longdoZoom, false)) {}
            longdoMap.call("rotate", listOf(position.bearing, false)) {}
            longdoMap.call("pitch", listOf(native.pitch)) {}
        }
    }

    /** Longdo ネイティブへ渡すカメラ（中心・ズーム・pitch）。 */
    private data class NativeCamera(
        val target: GeoPointInterface,
        val longdoZoom: Double,
        val pitch: Double,
    )

    /**
     * 統一カメラ（[MapCameraPosition]）を Longdo ネイティブのカメラへ変換する。
     *
     * tilt ≥ 0 は中心・ズームをそのまま用い、pitch を 0〜[MAX_PITCH] にクランプする。
     *
     * tilt < 0（水平線より上方を向く仰角ビュー）は Longdo（MapLibre GL）が上向き pitch を表現できないため、
     * Google Maps 実装と同方式で擬似再現する: カメラ eye を固定したまま、地面ターゲットを bearing 方向へ
     * `altitude * tan(|tilt|)` メートル前進させ、`|tilt|` の下向き pitch で描画する。ズームは変更しない。
     */
    private fun nativeCameraFor(position: MapCameraPosition): NativeCamera {
        val longdoZoom = coreZoomToLongdo(position.zoom)
        if (position.tilt >= 0.0) {
            return NativeCamera(position.position, longdoZoom, position.tilt.coerceIn(0.0, MAX_PITCH))
        }
        val tiltAbsDeg = abs(position.tilt).coerceIn(0.0, MAX_PITCH)
        val tiltAbsRad = Math.toRadians(tiltAbsDeg)
        // 高度は統一ズーム（Google）基準で算出する（Longdo ネイティブズームではない）。
        val altitude = zoomConverter.zoomLevelToAltitude(position.zoom, position.position.latitude, 0.0)
        val distanceForward = altitude * tan(tiltAbsRad)
        val target = Spherical.computeOffset(position.position, distanceForward, position.bearing)
        return NativeCamera(target, longdoZoom, tiltAbsDeg)
    }

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) {
        val sw = bounds.southWest ?: return
        val ne = bounds.northEast ?: return
        val bound =
            JSONObject()
                .put("minLon", sw.longitude)
                .put("minLat", sw.latitude)
                .put("maxLon", ne.longitude)
                .put("maxLat", ne.latitude)
        // NOTE: Longdo Map API3 の map.bound(box) は境界矩形のみを引数に取り padding 相当の
        // 余白パラメータを持たないため、padding は反映できない（矩形を膨らませる擬似対応は行わない）。
        if (mapReady) {
            runCatching { longdoMap.call("bound", listOf(bound)) {} }
        }
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

    /**
     * 多数マーカーをマーカータイル（ラスターレイヤ）として描画する。
     * マーカーが無ければラスターレイヤを削除する。
     */
    private fun renderTiledMarkers(data: List<MarkerState>) {
        val state = tileRenderer().render(data)
        if (state != null) {
            applyMarkerTileRaster(state)
        } else {
            removeMarkerTileRaster()
        }
    }

    /** マーカータイル・ラスターを Longdo（MapLibre GL）へ適用する（[appliedRasters] とは独立管理）。 */
    private fun applyMarkerTileRaster(state: RasterLayerState) {
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

    private fun removeMarkerTileRaster() {
        markerTileState?.let { runRasterJs(removeRasterJs(it.id)) }
        markerTileState = null
    }

    /**
     * タップ座標付近のタイリング・マーカーを [MarkerState.onClick] へ配送する。
     *
     * @param point タップ座標。
     * @param nativeZoom Longdo ネイティブ（MapLibre）ズーム。
     */
    fun handleMarkerTap(
        point: com.mapconductor.core.features.GeoPoint,
        nativeZoom: Double,
    ) {
        markerTileRenderer?.findMarkerAt(point, nativeZoom)?.let { it.onClick?.invoke(it) }
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

    private fun applyRaster(state: RasterLayerState) {
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

    private fun runRasterJs(js: String) {
        runCatching { longdoMap.run(js) {} }
    }

    /** MapLibre のラスターソース仕様（JSON）。未対応ソースは null。 */
    private fun rasterSourceSpec(source: RasterLayerSource): String? =
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

    private fun rasterLayerSpec(
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

    private fun addRasterJs(
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

    private fun removeRasterJs(id: String): String {
        val srcId = rasterSourceId(id)
        val layerId = rasterLayerId(id)
        return "(function(){try{var m=map.Renderer;" +
            "if(m.getLayer('$layerId'))m.removeLayer('$layerId');" +
            "if(m.getSource('$srcId'))m.removeSource('$srcId');}catch(e){}})()"
    }

    private fun setRasterOpacityJs(
        layerId: String,
        opacity: Float,
    ): String =
        "(function(){try{if(map.Renderer.getLayer('$layerId'))" +
            "map.Renderer.setPaintProperty('$layerId','raster-opacity',${opacity.toDouble()});}catch(e){}})()"

    private fun setRasterVisibilityJs(
        layerId: String,
        visible: Boolean,
    ): String =
        "(function(){try{if(map.Renderer.getLayer('$layerId'))" +
            "map.Renderer.setLayoutProperty('$layerId','visibility','${if (visible) "visible" else "none"}');}catch(e){}})()"

    private fun rasterSourceId(id: String): String = "mcrs_$id"

    private fun rasterLayerId(id: String): String = "mcrl_$id"

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

    /**
     * タップ座標付近のポリラインを [PolylineState.onClick] へ配送する。
     *
     * 判定は SDK ではなくコアの [com.mapconductor.core.polyline.PolylineManager]（緯度経度ベース。ズーム依存の
     * ピクセル許容量で線分への近接を判定）が担う。カメラ（ズーム）は [dispatchCameraToOverlays] 経由で
     * コントローラへ通知済み。
     */
    fun handlePolylineTap(point: GeoPointInterface) {
        polylineController.findWithClosestPoint(point)?.let { hit ->
            polylineController.dispatchClick(PolylineEvent(hit.entity.state, hit.closestPoint))
        }
    }

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

    /**
     * タップ座標がポリゴン内（外周内かつ穴の外）にあれば [PolygonState.onClick] を配送する。
     *
     * 判定は SDK ではなくコアの [com.mapconductor.core.polygon.PolygonManager]（測地線辺を補間したうえでの巻き数
     * 判定・穴の除外）が担うため、測地線ポリゴンでも描画された曲線どおりの内外判定になる。
     */
    fun handlePolygonTap(point: GeoPointInterface) {
        polygonController.find(point)?.let { entity ->
            polygonController.dispatchClick(PolygonEvent(entity.state, point))
        }
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

    /**
     * タップ座標がグラウンドイメージの bounds 内にあれば [GroundImageState.onClick] を配送する。
     *
     * 判定は SDK ではなくコアの [com.mapconductor.core.groundimage.GroundImageManager]（bounds への内外判定＝
     * 緯度経度ベース）が担う。
     */
    fun handleGroundImageTap(point: GeoPoint) {
        groundImageController.find(point)?.let { entity ->
            groundImageController.dispatchClick(GroundImageEvent(entity.state, point))
        }
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

    /**
     * タップ座標が円内（中心からの距離 ≤ 半径）にあれば [CircleState.onClick] を配送する。
     *
     * 判定は SDK ではなくコアの [com.mapconductor.core.circle.CircleManager]（球面距離による内外判定）が担う。
     */
    fun handleCircleTap(point: GeoPointInterface) {
        circleController.find(point)?.let { entity ->
            circleController.dispatchClick(CircleEvent(entity.state, point))
        }
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

    private var appliedUISettings: MapUISettings = MapUISettings.Default

    /**
     * Longdo runs inside a WebView, so gestures are toggled through its JS API
     * rather than a native property. Calls made before the page reports ready are
     * dropped, so the value is remembered and re-applied from [onMapReady].
     */
    /**
     * Longdo runs inside a WebView. Its JS API exposes drag and wheel toggles under
     * `map.Ui.Mouse`; `map.rotate()` / `map.pitch()` set the camera values rather
     * than gating a gesture, so rotation and tilt cannot be switched off here.
     * Calls made before the page reports ready are dropped, so the value is
     * remembered and re-applied from [onMapReady].
     */
    override fun applyUISettings(settings: MapUISettings) {
        appliedUISettings = settings
        MapUISettingsDiagnostics.warnIfRequested(
            settings.rotateGesture,
            gesture = MapGesture.Rotate,
            provider = "Longdo",
            reason = "the Longdo JS API has no rotation gesture toggle (map.rotate only sets the angle)",
        )
        MapUISettingsDiagnostics.warnIfRequested(
            settings.tiltGesture,
            gesture = MapGesture.Tilt,
            provider = "Longdo",
            reason = "the Longdo JS API has no tilt gesture toggle (map.pitch only sets the angle)",
        )
        val js =
            """
            (function(){
              try {
                var m = window.map;
                if (!m || !m.Ui || !m.Ui.Mouse) return;
                m.Ui.Mouse.enableDrag(${settings.scrollGesture});
                m.Ui.Mouse.enableWheel(${settings.zoomGesture});
                // Touch drags are not covered by enableDrag alone; when every
                // gesture is off, gate all pointer input.
                if (m.Ui.Mouse.enable) m.Ui.Mouse.enable(${settings.scrollGesture || settings.zoomGesture});
                if (m.Ui.Keyboard && m.Ui.Keyboard.enable) m.Ui.Keyboard.enable(${settings.scrollGesture || settings.zoomGesture});
              } catch (e) {}
            })()
            """.trimIndent()
        runCatching { longdoMap.run(js) {} }
    }

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
        private const val MAX_PITCH = 60.0

        /** 統一ズーム（Google）→ Longdo ネイティブズーム。 */
        internal fun coreZoomToLongdo(coreZoom: Double): Double =
            (coreZoom - LONGDO_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM, MAX_ZOOM)

        /** Longdo ネイティブズーム → 統一ズーム（Google）。 */
        internal fun longdoZoomToCore(longdoZoom: Double): Double =
            (longdoZoom + LONGDO_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
