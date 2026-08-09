package com.mapconductor.longdo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.longdo.sdk3.LongdoMap
import com.mapconductor.compose.CollectAndRenderOverlays
import com.mapconductor.compose.MapViewScope
import com.mapconductor.compose.circle.LocalCircleCollector
import com.mapconductor.compose.groundimage.LocalGroundImageCollector
import com.mapconductor.compose.info.InfoBubbleEntry
import com.mapconductor.compose.info.LocalInfoBubbleCollector
import com.mapconductor.compose.marker.LocalMarkerCollector
import com.mapconductor.compose.polygon.LocalPolygonCollector
import com.mapconductor.compose.polyline.LocalPolylineCollector
import com.mapconductor.compose.raster.LocalRasterLayerCollector
import com.mapconductor.core.OnCameraMoveHandler
import com.mapconductor.core.OnMapEventHandler
import com.mapconductor.core.OnMapLoadedHandler
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.circle.CircleCapableInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.groundimage.GroundImageCapableInterface
import com.mapconductor.core.map.LocalMapOverlayRegistry
import com.mapconductor.core.map.LocalMapServiceRegistry
import com.mapconductor.core.map.LocalMapViewController
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCapability
import com.mapconductor.core.map.MapServiceRegistrations
import com.mapconductor.core.map.VisibleRegion
import com.mapconductor.core.marker.MarkerCapableInterface
import com.mapconductor.core.marker.MarkerRenderingSupportKey
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.polygon.PolygonCapableInterface
import com.mapconductor.core.polyline.PolylineCapableInterface
import com.mapconductor.core.raster.RasterLayerCapableInterface
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import kotlinx.coroutines.delay

/**
 * Longdo Map の地図を表示する Composable。他プロバイダの `*MapView` と同じ引数体系を持ち、
 * example-app の型ディスパッチ（`MapViewContainer`）からそのまま利用できる。
 *
 * Longdo Map API3 SDK は WebView（Longdo Map JS API3 / 内部 MapLibre GL）ベースのため、地図表示・カメラ制御・
 * タップ/移動イベントを [LongdoMap] の JS ブリッジ経由で扱う。マーカー／InfoBubble は他プロバイダの GL レイヤ
 * とは異なり、コンポーズのオーバーレイとして重ね、`map.Renderer.project`（内部 MapLibre GL）の投影座標へ配置
 * して地図移動へ追従させる（マーカードラッグは Compose のジェスチャで直接扱う）。
 *
 * @param state 地図状態。デザイン・カメラ・コントローラを保持する。
 * @param onMapLoaded 地図の準備完了（Longdo の `ready`）で呼ばれる。
 * @param onMapClick 地図タップ時に、タップ座標付きで呼ばれる。
 * @param onCameraMoveStart / onCameraMove / onCameraMoveEnd カメラ移動の各段階で呼ばれる。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LongdoMapView(
    state: LongdoViewState,
    modifier: Modifier = Modifier,
    markerTiling: com.mapconductor.core.marker.MarkerTilingOptions? = null,
    cameraRestriction: com.mapconductor.core.map.CameraRestriction? = null,
    onMapLoaded: OnMapLoadedHandler? = null,
    onMapClick: OnMapEventHandler? = null,
    @Suppress("UNUSED_PARAMETER") onMapLongClick: OnMapEventHandler? = null,
    onCameraMoveStart: OnCameraMoveHandler? = null,
    onCameraMove: OnCameraMoveHandler? = null,
    onCameraMoveEnd: OnCameraMoveHandler? = null,
    content: (@Composable MapViewScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    remember(context) {
        LongdoInitSDK.ensureInitialized(context)
        ResourceProvider.init(context)
        true
    }

    val currentState by rememberUpdatedState(state)
    val onLoaded by rememberUpdatedState(onMapLoaded)
    val onClick by rememberUpdatedState(onMapClick)
    val onMoveStart by rememberUpdatedState(onCameraMoveStart)
    val onMove by rememberUpdatedState(onCameraMove)
    val onMoveEnd by rememberUpdatedState(onCameraMoveEnd)

    val bridge = remember { LongdoEventBridge() }

    // WebView・ホルダ・コントローラは remember で先に生成し、マーカー状態を購読できるようにする。
    @SuppressLint("SetJavaScriptEnabled")
    val longdoMap =
        remember {
            LongdoMap(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // マーカータイリングのタイルはローカル HTTP サーバ（http://127.0.0.1）配信で、Longdo ページ
                // （https）からは mixed content になるため許可する。
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                addJavascriptInterface(bridge, BRIDGE_NAME)
            }
        }
    val registrations = remember(state) { MapServiceRegistrations() }
    val controller =
        remember(longdoMap) {
            LongdoMapViewController(LongdoMapViewHolder(longdoMap)).also {
                state.setController(it)
                // レジストリの持ち主は state（react-sdk / ios-sdk と同じ）。
                // 登録は content の合成より前に済ませる必要がある — MarkerClusterGroup は
                // 未登録ならその場で return し、レジストリは Compose の state ではないので
                // 後から入れても再合成が走らない。
                registrations += state.serviceRegistry.register(MarkerRenderingSupportKey, it.markerRenderingSupport)

                // Longdo は WebView（Longdo Map JS API3）ブリッジ越しの実装で、任意点の
                // 同期 project / unproject を持たない（[LongdoMapViewHolder] を参照）。
                // これを宣言しておかないと、スクリーン空間を要求する機能
                // （InfoBubble・マーカーアニメーション・タイル方式マーカーのヒットテスト）が
                // 理由の分からないまま無反応になる。
                registrations +=
                    state.serviceRegistry.declareUnsupported(
                        MapCapability.ScreenProjectionSync,
                        "Longdo runs on a WebView bridge with no synchronous project/unproject",
                    )
            }
        }

    // このプロバイダは MapViewBase を通らないので、登録の取り下げもここで行う。
    // 登録トークンでまとめて外すので、キー名を列挙する必要がない。
    DisposableEffect(state) {
        onDispose { registrations.disposeAll() }
    }

    // markerTiling 指定時（多数マーカー）はマーカータイリング（ラスターレイヤ）経路で描画する。
    controller.useMarkerLayer = markerTiling != null
    controller.markerTilingOptions = markerTiling

    // This provider builds its view itself rather than going through MapViewBase,
    // so the shared gesture dispatch has to be wired here.
    LaunchedEffect(controller, cameraRestriction) {
        controller.setCameraRestriction(cameraRestriction)
    }

    LaunchedEffect(controller, state.uiSettings) {
        controller.applyUISettings(state.uiSettings)
    }

    val overlayScope = remember { LongdoMapViewScope() }
    val registry = remember(overlayScope) { overlayScope.buildRegistry() }
    val overlayState = remember { LongdoOverlayState() }

    var mapReady by remember { mutableStateOf(false) }
    val moveDispatcher = remember { LongdoCameraMoveDispatcher() }

    val markers by controller.markers.collectAsState()
    val bubbles by overlayScope.bubbleFlow.collectAsState()

    // 現在のマーカー・InfoBubble の地理座標を JS の投影対象として登録する。
    fun pushTargets() {
        val json = buildTargetsJson(controller.markers.value, overlayScope.bubbleFlow.value)
        runCatching { longdoMap.run("window.mcSetTargets && window.mcSetTargets($json)") {} }
    }

    // ドラッグ中の逆投影が処理中かどうか。連続する move 更新で JS 逆投影が氾濫しないよう、処理中は
    // 中間更新をスキップする（他モジュール MapTiler の onDrag 処理中スキップと同じ考え方）。
    val dragUnprojectInFlight = remember { arrayOf(false) }

    // マーカードラッグの座標更新：画面座標（デバイスピクセル）を逆投影して [MarkerState.position] を更新し、
    // 確定時（[end]=true）は [MarkerState.onDragEnd]、ドラッグ中（[end]=false）は [MarkerState.onDrag] を発火する。
    // Longdo の逆投影は JS 経由で非同期のため、ドラッグ中は処理中スキップで過負荷を防ぐ（他モジュールと同じ挙動）。
    fun applyMarkerDrag(
        marker: MarkerState,
        devicePx: Offset,
        end: Boolean,
    ) {
        if (!end && dragUnprojectInFlight[0]) return
        dragUnprojectInFlight[0] = true
        val cssX = devicePx.x / density
        val cssY = devicePx.y / density
        val js =
            "(function(){try{var p=map.Renderer.unproject([$cssX,$cssY]);" +
                "return p.lng+','+p.lat;}catch(e){return '';}})()"
        val started =
            runCatching {
                longdoMap.run(js) { result ->
                    dragUnprojectInFlight[0] = false
                    val cleaned = result.trim().trim('"')
                    val parts = cleaned.split(',')
                    if (parts.size == 2) {
                        val lon = parts[0].toDoubleOrNull()
                        val lat = parts[1].toDoubleOrNull()
                        if (lon != null && lat != null) {
                            marker.position = GeoPoint(latitude = lat, longitude = lon)
                            if (end) {
                                marker.onDragEnd?.invoke(marker)
                                pushTargets()
                            } else {
                                marker.onDrag?.invoke(marker)
                            }
                        }
                    }
                }
            }.isSuccess
        if (!started) dragUnprojectInFlight[0] = false
    }

    // ブリッジのコールバックは最新の state / ユーザーコールバックを参照する。
    bridge.onReady = {
        controller.onMapReady()
        mapReady = true
        pushTargets()
        onLoaded?.invoke(currentState)
    }
    bridge.onMapClick = { point, zoom ->
        // まず地図タップ（例: 選択解除）を通知し、続いてタイリング・マーカー／ポリラインのヒットテストを行う。
        onClick?.invoke(point)
        controller.handleMarkerTap(point, zoom)
        controller.handlePolylineTap(point)
        controller.handlePolygonTap(point)
        controller.handleGroundImageTap(point)
        controller.handleCircleTap(point)
    }
    bridge.onCameraMove = cameraMove@{ lon, lat, zoom, bearing, tilt, bounds ->
        val base = currentState.cameraPosition
        val region =
            bounds?.let {
                VisibleRegion(bounds = it, nearLeft = null, nearRight = null, farLeft = null, farRight = null)
            }
        val updated =
            MapCameraPosition(
                position = GeoPoint(latitude = lat, longitude = lon),
                zoom = LongdoMapViewController.longdoZoomToCore(zoom),
                bearing = bearing,
                tilt = tilt,
                paddings = base.paddings,
                visibleRegion = region,
            )
        // 範囲・ズーム制限に違反していれば矩形内へ引き戻す（Longdo にはネイティブの範囲制限 API が
        // 無いため）。android-sdk の HERE/ArcGIS/TomTom と同じく、補正した回は state 更新も
        // コールバックも行わないので、アプリ側が範囲外のカメラを観測することはない。
        if (controller.applyCameraRestrictionCorrectionIfNeeded(updated)) return@cameraMove
        currentState.updateCameraPosition(updated)
        // マーカークラスタリング用に、可視領域つきカメラを記録する。
        controller.setLatestOverlayCamera(updated)
        moveDispatcher.dispatch(
            position = updated,
            onStart = { onMoveStart?.invoke(it) },
            onMove = { onMove?.invoke(it) },
            onEnd = {
                onMoveEnd?.invoke(it)
                // 静止時にクラスタを再算出する。
                controller.dispatchCameraToOverlays()
            },
        )
    }
    bridge.onProjected = { positions ->
        // CSS ピクセル → デバイスピクセル。ドラッグ中のマーカーはドラッグ量が優先なので上書きしない。
        val dragging = overlayState.draggingId
        positions.forEach { (id, css) ->
            if (id != dragging) {
                overlayState.renderPx[id] = Offset(css.x * density, css.y * density)
            }
        }
    }

    // 地図・オーバーレイを同一 Box に重ねる。
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { longdoMap },
        )

        // Longdo's JS API only gates *mouse* input (map.Ui.Mouse), so on a touch
        // device a drag still pans the WebView. Swallow drags natively instead —
        // taps fall through, so markers stay clickable.
        val ui = state.uiSettings
        if (!ui.scrollGesture || !ui.zoomGesture) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .pointerInput(ui.scrollGesture, ui.zoomGesture) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val multiTouch = event.changes.size > 1
                                    val dragging =
                                        event.changes.any { change ->
                                            (change.position - change.previousPosition).getDistance() > 0f
                                        }
                                    val blockPan = !ui.scrollGesture && dragging
                                    val blockPinch = !ui.zoomGesture && multiTouch
                                    if (blockPan || blockPinch) {
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
            )
        }

        if (mapReady) {
            markers.forEach { markerState ->
                key(markerState.id) {
                    LongdoMarkerOverlay(
                        marker = markerState,
                        state = overlayState,
                        onDragCommit = { m, px -> applyMarkerDrag(m, px, end = true) },
                        onDragMove = { m, px -> applyMarkerDrag(m, px, end = false) },
                    )
                }
            }
            bubbles.values.forEach { entry ->
                key(entry.id) {
                    LongdoInfoBubbleOverlay(entry = entry, state = overlayState)
                }
            }
        }
    }

    // オーバーレイ content を CompositionLocal 群の下で評価し、マーカー／InfoBubble を各コレクタへ登録する。
    content?.let { overlay ->
        CompositionLocalProvider(
            LocalMapOverlayRegistry provides registry,
            LocalMapServiceRegistry provides state.serviceRegistry,
            LocalMapViewController provides controller,
            LocalMarkerCollector provides overlayScope.markerCollector,
            LocalInfoBubbleCollector provides overlayScope.bubbleFlow,
            LocalCircleCollector provides overlayScope.circleCollector,
            LocalPolylineCollector provides overlayScope.polylineCollector,
            LocalPolygonCollector provides overlayScope.polygonCollector,
            LocalGroundImageCollector provides overlayScope.groundImageCollector,
            LocalRasterLayerCollector provides overlayScope.rasterLayerCollector,
        ) {
            with(overlayScope) { overlay() }
        }
    }

    // 収集したマーカーをコントローラへ流し込む（compositionMarkers 経由で controller.markers を更新）。
    if (mapReady) {
        CollectAndRenderOverlays(registry = registry, controller = controller)

        DisposableEffect(controller) {
            overlayScope.markerCollector.setUpdateHandler { markerState ->
                (controller as MarkerCapableInterface).let {
                    if (it.hasMarker(markerState)) it.updateMarker(markerState)
                }
            }
            overlayScope.rasterLayerCollector.setUpdateHandler { rasterLayerState ->
                (controller as RasterLayerCapableInterface).let {
                    if (it.hasRasterLayer(rasterLayerState)) it.updateRasterLayer(rasterLayerState)
                }
            }
            overlayScope.polylineCollector.setUpdateHandler { polylineState ->
                (controller as PolylineCapableInterface).let {
                    if (it.hasPolyline(polylineState)) it.updatePolyline(polylineState)
                }
            }
            overlayScope.polygonCollector.setUpdateHandler { polygonState ->
                (controller as PolygonCapableInterface).let {
                    if (it.hasPolygon(polygonState)) it.updatePolygon(polygonState)
                }
            }
            overlayScope.groundImageCollector.setUpdateHandler { groundImageState ->
                (controller as GroundImageCapableInterface).let {
                    if (it.hasGroundImage(groundImageState)) it.updateGroundImage(groundImageState)
                }
            }
            overlayScope.circleCollector.setUpdateHandler { circleState ->
                (controller as CircleCapableInterface).let {
                    if (it.hasCircle(circleState)) it.updateCircle(circleState)
                }
            }
            onDispose {
                overlayScope.markerCollector.setUpdateHandler(null)
                overlayScope.rasterLayerCollector.setUpdateHandler(null)
                overlayScope.polylineCollector.setUpdateHandler(null)
                overlayScope.polygonCollector.setUpdateHandler(null)
                overlayScope.groundImageCollector.setUpdateHandler(null)
                overlayScope.circleCollector.setUpdateHandler(null)
            }
        }
    }

    // マーカー／InfoBubble の集合が変化したら投影対象を再登録する。
    LaunchedEffect(markers, bubbles, mapReady) {
        if (mapReady) pushTargets()
    }

    // 地図（WebView）読み込み後、`map` が生成され次第イベント購読・投影を注入する。run() は JS コンテキスト
    // 未生成時に破棄されるため、成立するまで一定間隔で再試行する（注入スクリプト自体も冪等・自己再試行）。
    LaunchedEffect(longdoMap) {
        var attempts = 0
        while (attempts < BIND_MAX_ATTEMPTS) {
            runCatching { longdoMap.run(bindingScript()) {} }
            delay(BIND_RETRY_MILLIS)
            attempts++
        }
    }

    // 初期カメラ・デザインを静的設定へ反映してから地図を読み込む（load は一度だけ）。
    LaunchedEffect(Unit) {
        val camera = currentState.cameraPosition
        LongdoMap.LAYER = LongdoMap.LongdoStatic("Layers", currentState.mapDesignType.layerName)
        LongdoMap.LOCATION = camera.position.toLongdoLocation()
        LongdoMap.ZOOM =
            LongdoMapViewController
                .coreZoomToLongdo(camera.zoom)
                .roundToInt()
                .coerceIn(MIN_ZOOM, MAX_ZOOM)
        LongdoMap.LAST_VIEW = false
        // 初期の方位・傾きは静的設定に無いため、ready 後に反映されるようキューへ積む。
        controller.moveCamera(camera)
        longdoMap.load()
    }

    DisposableEffect(Unit) {
        onDispose {
            moveDispatcher.cancel()
            controller.destroy()
        }
    }
}

/** マーカー・InfoBubble の投影対象（id・経度・緯度）を JSON 配列文字列にする。 */
private fun buildTargetsJson(
    markers: List<MarkerState>,
    bubbles: Map<String, InfoBubbleEntry>,
): String {
    val arr = JSONArray()
    val seen = HashSet<String>()
    markers.forEach { m ->
        arr.put(
            JSONObject()
                .put("id", m.id)
                .put("lon", m.position.longitude)
                .put("lat", m.position.latitude),
        )
        seen.add(m.id)
    }
    bubbles.forEach { (id, entry) ->
        if (id !in seen) {
            val p = entry.positionProvider()
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("lon", p.longitude)
                    .put("lat", p.latitude),
            )
        }
    }
    return arr.toString()
}

/**
 * Longdo の連続的なカメラ変化通知から、他プロバイダと同じ move-start / move / move-end 3 段階を合成する。
 */
private class LongdoCameraMoveDispatcher {
    private val handler = Handler(Looper.getMainLooper())
    private var moving = false
    private var endRunnable: Runnable? = null

    fun dispatch(
        position: MapCameraPosition,
        onStart: (MapCameraPosition) -> Unit,
        onMove: (MapCameraPosition) -> Unit,
        onEnd: (MapCameraPosition) -> Unit,
    ) {
        if (!moving) {
            moving = true
            onStart(position)
        }
        onMove(position)
        endRunnable?.let { handler.removeCallbacks(it) }
        val runnable =
            Runnable {
                moving = false
                onEnd(position)
            }
        endRunnable = runnable
        handler.postDelayed(runnable, QUIET_MILLIS)
    }

    fun cancel() {
        endRunnable?.let { handler.removeCallbacks(it) }
        endRunnable = null
        moving = false
    }

    private companion object {
        const val QUIET_MILLIS = 180L
    }
}

/**
 * Longdo Map JS API3 のイベント購読と、オーバーレイ投影の注入スクリプト。
 *
 * `map` が生成されるまで自己再試行し、二重初期化を `map.__mcBound` で防ぐ。カメラ変化は `Location` / `Zoom` /
 * `Rotate` / `Pitch` で取得し、オーバーレイ投影は `map.Renderer`（内部 MapLibre GL）の `project` と `move` で行う。
 */
private fun bindingScript(): String =
    """
    (function bindMapConductor() {
      if (typeof map === 'undefined' || !window.longdo || !map.Event) {
        setTimeout(bindMapConductor, 50);
        return;
      }
      if (map.__mcBound) return;
      map.__mcBound = true;
      var emitCamera = function () {
        try {
          var c = map.location();
          var bounds = null;
          try {
            var bb = map.Renderer && map.Renderer.getBounds ? map.Renderer.getBounds() : null;
            if (bb && bb.getSouthWest && bb.getNorthEast) {
              var sw = bb.getSouthWest();
              var ne = bb.getNorthEast();
              bounds = { sw: [sw.lng, sw.lat], ne: [ne.lng, ne.lat] };
            }
          } catch (e2) {}
          $BRIDGE_NAME.onCamera(JSON.stringify({
            lon: c.lon, lat: c.lat, zoom: map.zoom(), rotate: map.rotate(), pitch: map.pitch(), bounds: bounds
          }));
        } catch (e) {}
        if (window.__mcProject) window.__mcProject();
      };
      try { map.Event.bind(longdo.EventName.Ready, function () { $BRIDGE_NAME.onReady(); }); } catch (e) {}
      try { map.Event.bind(longdo.EventName.Location, emitCamera); } catch (e) {}
      try { map.Event.bind(longdo.EventName.Zoom, emitCamera); } catch (e) {}
      try { map.Event.bind(longdo.EventName.Rotate, emitCamera); } catch (e) {}
      try { map.Event.bind(longdo.EventName.Pitch, emitCamera); } catch (e) {}
      try {
        map.Event.bind(longdo.EventName.Click, function () {
          try {
            var p = map.location(longdo.LocationMode.Pointer);
            $BRIDGE_NAME.onClick(JSON.stringify({ lon: p.lon, lat: p.lat, zoom: map.zoom() }));
          } catch (e) {}
        });
      } catch (e) {}

      // オーバーレイ投影：登録座標を map.Renderer.project で画面座標へ変換して通知する。
      window.__mcTargets = window.__mcTargets || [];
      window.__mcProject = function () {
        try {
          if (!map.Renderer || !map.Renderer.project) return;
          var out = [];
          for (var i = 0; i < window.__mcTargets.length; i++) {
            var t = window.__mcTargets[i];
            var pt = map.Renderer.project([t.lon, t.lat]);
            out.push({ id: t.id, x: pt.x, y: pt.y });
          }
          $BRIDGE_NAME.onProject(JSON.stringify(out));
        } catch (e) {}
      };
      window.mcSetTargets = function (arr) {
        window.__mcTargets = arr || [];
        window.__mcProject();
      };
      // 毎フレームの追従は MapLibre の move で駆動する（Longdo イベントより高頻度）。
      try { map.Renderer.on('move', window.__mcProject); } catch (e) {}

      // 初期カメラ（可視領域つき）を先に通知してからレディを通知する（クラスタリングの初回算出に必要）。
      emitCamera();
      $BRIDGE_NAME.onReady();
    })();
    """.trimIndent()

private const val BRIDGE_NAME = "MapConductorLongdoBridge"
private const val BIND_MAX_ATTEMPTS = 40
private const val BIND_RETRY_MILLIS = 200L
private const val MIN_ZOOM = 1
private const val MAX_ZOOM = 20
