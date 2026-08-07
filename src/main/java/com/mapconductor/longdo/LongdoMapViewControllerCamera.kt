package com.mapconductor.longdo

import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.spherical.Spherical
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.tan

// カメラの適用。Longdo のズームは MapConductor の論理ズームと 1 段ずれるため、
// ZoomAltitudeConverter を通してから渡す。

/**
 * カメラ停止時に [setCameraRestriction] の制限違反を補正する。補正したら true。
 *
 * Longdo Map API3 には（Google の `setLatLngBoundsForCameraTarget` に相当する）
 * カメラ範囲制限の JS API が無いため、android-sdk の HERE/ArcGIS/TomTom と同じく
 * カメラ停止時に矩形内へクランプして再適用する方式で制限する。
 * 再適用すると再度カメライベントが発火し、そこでは補正不要になり通常フローへ進む。
 */
internal fun LongdoMapViewController.applyCameraRestrictionCorrectionIfNeeded(current: MapCameraPosition): Boolean {
    val corrected = correctForCameraRestriction(current) ?: return false
    moveCamera(corrected)
    return true
}

internal fun LongdoMapViewController.handleMoveCamera(position: MapCameraPosition) {
    if (mapReady) applyCamera(position) else pendingCameraPosition = position
}

internal fun LongdoMapViewController.handleAnimateCamera(
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
internal fun LongdoMapViewController.applyCamera(position: MapCameraPosition) {
    val native = nativeCameraFor(position)
    runCatching {
        longdoMap.call("location", listOf(native.target.toLongdoLocation(), false)) {}
        longdoMap.call("zoom", listOf(native.longdoZoom, false)) {}
        longdoMap.call("rotate", listOf(position.bearing, false)) {}
        longdoMap.call("pitch", listOf(native.pitch)) {}
    }
}

/** Longdo ネイティブへ渡すカメラ（中心・ズーム・pitch）。 */
internal data class NativeCamera(
    val target: GeoPointInterface,
    val longdoZoom: Double,
    val pitch: Double,
)

/**
 * 統一カメラ（[MapCameraPosition]）を Longdo ネイティブのカメラへ変換する。
 *
 * tilt ≥ 0 は中心・ズームをそのまま用い、pitch を 0〜[LongdoMapViewController.MAX_PITCH] にクランプする。
 *
 * tilt < 0（水平線より上方を向く仰角ビュー）は Longdo（MapLibre GL）が上向き pitch を表現できないため、
 * Google Maps 実装と同方式で擬似再現する: カメラ eye を固定したまま、地面ターゲットを bearing 方向へ
 * `altitude * tan(|tilt|)` メートル前進させ、`|tilt|` の下向き pitch で描画する。ズームは変更しない。
 */
internal fun LongdoMapViewController.nativeCameraFor(position: MapCameraPosition): NativeCamera {
    val longdoZoom = LongdoMapViewController.coreZoomToLongdo(position.zoom)
    if (position.tilt >= 0.0) {
        return NativeCamera(
            position.position, longdoZoom,
            position.tilt
                .coerceIn(0.0, LongdoMapViewController.MAX_PITCH),
        )
    }
    val tiltAbsDeg = abs(position.tilt).coerceIn(0.0, LongdoMapViewController.MAX_PITCH)
    val tiltAbsRad = Math.toRadians(tiltAbsDeg)
    // 高度は統一ズーム（Google）基準で算出する（Longdo ネイティブズームではない）。
    val altitude = zoomConverter.zoomLevelToAltitude(position.zoom, position.position.latitude, 0.0)
    val distanceForward = altitude * tan(tiltAbsRad)
    val target = Spherical.computeOffset(position.position, distanceForward, position.bearing)
    return NativeCamera(target, longdoZoom, tiltAbsDeg)
}

internal fun LongdoMapViewController.handleFitBounds(
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
