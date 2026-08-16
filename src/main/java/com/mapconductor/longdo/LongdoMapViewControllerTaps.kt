package com.mapconductor.longdo

import com.mapconductor.core.map.MapGesture
import com.mapconductor.core.map.MapUISettings
import com.mapconductor.core.map.MapUISettingsDiagnostics

// ジェスチャ設定の反映。
//
// オーバーレイのタップ配送はコアの BaseMapViewController.dispatchOverlayTap が持つ
// （Longdo は WebView 上の JS 地図でネイティブのヒットテストが無いため、タップ座標を
// 受けてコアのマネージャ（緯度経度ベースの判定）に問い合わせる。これは他プロバイダと
// まったく同じ経路になる）。

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
