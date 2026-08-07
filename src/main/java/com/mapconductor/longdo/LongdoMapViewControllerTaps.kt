package com.mapconductor.longdo

import com.mapconductor.core.circle.CircleEvent
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.groundimage.GroundImageEvent
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.map.MapGesture
import com.mapconductor.core.map.MapUISettings
import com.mapconductor.core.map.MapUISettingsDiagnostics
import com.mapconductor.core.polygon.PolygonEvent
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.PolylineEvent
import com.mapconductor.core.polyline.PolylineState

// オーバーレイのタップ配送と、ジェスチャ設定の反映。
// Longdo は WebView 上の JS 地図で、ネイティブのヒットテストが無い。タップ座標を
// 受けてコア側のマネージャ（緯度経度ベースの判定）に問い合わせる形で拾っている。

/**
 * タップ座標付近のポリラインを [PolylineState.onClick] へ配送する。
 *
 * 判定は SDK ではなくコアの [com.mapconductor.core.polyline.PolylineManager]（緯度経度ベース。ズーム依存の
 * ピクセル許容量で線分への近接を判定）が担う。カメラ（ズーム）は [dispatchCameraToOverlays] 経由で
 * コントローラへ通知済み。
 */
internal fun LongdoMapViewController.handlePolylineTap(point: GeoPointInterface) {
    polylineController.findWithClosestPoint(point)?.let { hit ->
        polylineController.dispatchClick(PolylineEvent(hit.entity.state, hit.closestPoint))
    }
}

/**
 * タップ座標がポリゴン内（外周内かつ穴の外）にあれば [PolygonState.onClick] を配送する。
 *
 * 判定は SDK ではなくコアの [com.mapconductor.core.polygon.PolygonManager]（測地線辺を補間したうえでの巻き数
 * 判定・穴の除外）が担うため、測地線ポリゴンでも描画された曲線どおりの内外判定になる。
 */
internal fun LongdoMapViewController.handlePolygonTap(point: GeoPointInterface) {
    polygonController.find(point)?.let { entity ->
        polygonController.dispatchClick(PolygonEvent(entity.state, point))
    }
}

/**
 * タップ座標がグラウンドイメージの bounds 内にあれば [GroundImageState.onClick] を配送する。
 *
 * 判定は SDK ではなくコアの [com.mapconductor.core.groundimage.GroundImageManager]（bounds への内外判定＝
 * 緯度経度ベース）が担う。
 */
internal fun LongdoMapViewController.handleGroundImageTap(point: GeoPoint) {
    groundImageController.find(point)?.let { entity ->
        groundImageController.dispatchClick(GroundImageEvent(entity.state, point))
    }
}

/**
 * タップ座標が円内（中心からの距離 ≤ 半径）にあれば [CircleState.onClick] を配送する。
 *
 * 判定は SDK ではなくコアの [com.mapconductor.core.circle.CircleManager]（球面距離による内外判定）が担う。
 */
internal fun LongdoMapViewController.handleCircleTap(point: GeoPointInterface) {
    circleController.find(point)?.let { entity ->
        circleController.dispatchClick(CircleEvent(entity.state, point))
    }
}

/**
 * Longdo runs inside a WebView. Its JS API exposes drag and wheel toggles under
 * `map.Ui.Mouse`; `map.rotate()` / `map.pitch()` set the camera values rather
 * than gating a gesture, so rotation and tilt cannot be switched off here.
 * Calls made before the page reports ready are dropped, so the value is
 * remembered and re-applied from [onMapReady].
 */
internal fun LongdoMapViewController.applyGestureSettings(settings: MapUISettings) {
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
