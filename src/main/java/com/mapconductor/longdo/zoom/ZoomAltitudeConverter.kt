package com.mapconductor.longdo.zoom

import com.mapconductor.core.zoom.WebMercatorZoomAltitudeConverter

/**
 * 統一ズーム（Google Maps 基準・256px タイル）⇄ 高度の変換。
 *
 * このプロバイダのネイティブズームは統一ズームと同じ基準なのでオフセットは 0。
 * 換算式はコアの [WebMercatorZoomAltitudeConverter] にある。
 */
class ZoomAltitudeConverter(
    zoom0Altitude: Double = DEFAULT_ZOOM0_ALTITUDE,
) : WebMercatorZoomAltitudeConverter(zoom0Altitude, zoomOffset = 0.0)
