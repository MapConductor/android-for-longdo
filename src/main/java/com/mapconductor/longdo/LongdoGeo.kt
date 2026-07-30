package com.mapconductor.longdo

import com.mapconductor.core.features.GeoPointInterface
import org.json.JSONObject

/**
 * MapConductor コアの座標型と Longdo Map JS API3 が期待する座標表現（`{ lon, lat }`）の変換ヘルパ。
 *
 * Longdo Map は経度 `lon`・緯度 `lat` のキーを用いる点に注意する（GeoJSON と同じ経度先行の思想）。
 */
internal fun GeoPointInterface.toLongdoLocation(): JSONObject =
    JSONObject()
        .put("lon", longitude)
        .put("lat", latitude)
