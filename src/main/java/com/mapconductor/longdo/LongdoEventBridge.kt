package com.mapconductor.longdo

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Longdo Map JS API3（WebView）から発火するイベントを Kotlin へ橋渡しする `@JavascriptInterface`。
 *
 * [LongdoMapView] が `addJavascriptInterface(bridge, "MapConductorLongdoBridge")` で WebView へ登録し、
 * 地図側では `map.Event.bind(...)` から本クラスのメソッドを呼び出す（[LongdoMapView] の注入スクリプト参照）。
 *
 * これらのメソッドは WebView の JS スレッドで呼ばれるため、コールバックはメインスレッドへ委譲してから
 * 実行する（Compose の状態更新・ユーザーコールバックはメインスレッド前提）。
 */
internal class LongdoEventBridge {
    /** 地図の準備完了（`ready`）。 */
    var onReady: (() -> Unit)? = null

    /** 地図タップ（クリック地点の座標と、その時点のネイティブズーム）。 */
    var onMapClick: ((point: GeoPoint, zoom: Double) -> Unit)? = null

    /** カメラ移動（中心座標・ズーム・方位・傾き・可視領域）。 */
    var onCameraMove: (
        (lon: Double, lat: Double, zoom: Double, bearing: Double, tilt: Double, bounds: GeoRectBounds?) -> Unit
    )? = null

    /**
     * オーバーレイ（マーカー・InfoBubble）投影結果。id → 画面座標（CSS ピクセル）。
     * JS 側が `map.Renderer` の各カメラ更新（`move`）で登録座標を投影して通知する。
     */
    var onProjected: ((positions: Map<String, Offset>) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var readyDelivered = false

    @JavascriptInterface
    fun onReady() {
        mainHandler.post {
            if (readyDelivered) return@post
            readyDelivered = true
            onReady?.invoke()
        }
    }

    @JavascriptInterface
    fun onClick(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val lon = obj.optDouble("lon", Double.NaN)
        val lat = obj.optDouble("lat", Double.NaN)
        if (lon.isNaN() || lat.isNaN()) return
        val zoom = obj.optDouble("zoom", 0.0).let { if (it.isNaN()) 0.0 else it }
        mainHandler.post { onMapClick?.invoke(GeoPoint(latitude = lat, longitude = lon), zoom) }
    }

    @JavascriptInterface
    fun onCamera(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val lon = obj.optDouble("lon", Double.NaN)
        val lat = obj.optDouble("lat", Double.NaN)
        val zoom = obj.optDouble("zoom", Double.NaN)
        if (lon.isNaN() || lat.isNaN() || zoom.isNaN()) return
        // 方位・傾きは未取得時 0 とみなす。
        val bearing = obj.optDouble("rotate", 0.0).let { if (it.isNaN()) 0.0 else it }
        val tilt = obj.optDouble("pitch", 0.0).let { if (it.isNaN()) 0.0 else it }
        // 可視領域（クラスタリングの表示範囲算出用）。sw/ne は [lng, lat]。
        val bounds =
            runCatching {
                val b = obj.optJSONObject("bounds") ?: return@runCatching null
                val sw = b.optJSONArray("sw") ?: return@runCatching null
                val ne = b.optJSONArray("ne") ?: return@runCatching null
                GeoRectBounds(
                    southWest = GeoPoint(latitude = sw.getDouble(1), longitude = sw.getDouble(0)),
                    northEast = GeoPoint(latitude = ne.getDouble(1), longitude = ne.getDouble(0)),
                )
            }.getOrNull()
        mainHandler.post { onCameraMove?.invoke(lon, lat, zoom, bearing, tilt, bounds) }
    }

    @JavascriptInterface
    fun onProject(json: String) {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
        val map = HashMap<String, Offset>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val x = o.optDouble("x", Double.NaN)
            val y = o.optDouble("y", Double.NaN)
            if (id.isEmpty() || x.isNaN() || y.isNaN()) continue
            map[id] = Offset(x.toFloat(), y.toFloat())
        }
        mainHandler.post { onProjected?.invoke(map) }
    }
}
