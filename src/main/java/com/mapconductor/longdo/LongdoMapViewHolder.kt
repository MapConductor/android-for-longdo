package com.mapconductor.longdo

import androidx.compose.ui.geometry.Offset
import com.longdo.sdk3.LongdoMap
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface
import com.mapconductor.core.projection.WebMercatorScreenProjection

/**
 * Longdo Map 用の [MapViewHolderInterface] 実装。
 *
 * Longdo Map API3 SDK は WebView（Longdo Map JS API3）ベースで、ネイティブの `MapView` / `Map` を
 * 直接公開しない。実体としては [LongdoMap]（WebView サブクラス）を保持し、地図操作は JS ブリッジ
 * （`call` / `run`）経由で行う。
 *
 * ブリッジは同期の座標変換 API を持たないが、Longdo の地図は Web Mercator なので
 * 投影はカメラとビューの大きさだけで決まる。式はコアの [WebMercatorScreenProjection]
 * にあり、ここはカメラとサイズを渡すだけ。**各所で式を書き直さないこと。**
 */
class LongdoMapViewHolder(
    private val longdoMap: LongdoMap,
) : MapViewHolderInterface<LongdoMap, LongdoMap> {
    override val mapView: LongdoMap = longdoMap
    override val map: LongdoMap = longdoMap

    /**
     * 投影に使うカメラの取り出し口。[LongdoMapViewController] が自身の直近カメラを繋ぐ。
     * ホルダーはコントローラより先に作られるので、コンストラクタでは受け取れない。
     */
    internal var cameraProvider: (() -> MapCameraPosition?)? = null

    /**
     * 地理座標 → 画面座標（**端末ピクセル**）。カメラ未確定（地図の準備前）なら null。
     *
     * ホルダーの契約は端末ピクセル（MapLibre 等の `toScreenLocation` に合わせている）。
     * 一方 [WebMercatorScreenProjection] の世界の大きさ `256 * 2^zoom` は
     * **密度非依存の単位**（dp / CSS ピクセル）なので、密度を挟まずに px を渡すと
     * 中心からのずれが density 分だけ小さくなり、マーカーと吹き出しがずれる。
     */
    override fun toScreenOffset(position: GeoPointInterface): Offset? {
        val camera = cameraProvider?.invoke() ?: return null
        val density = ResourceProvider.getDensity()
        val offsetDp =
            WebMercatorScreenProjection.toScreenOffset(
                position = position,
                camera = camera,
                widthPx = longdoMap.width / density,
                heightPx = longdoMap.height / density,
            ) ?: return null
        return Offset(offsetDp.x * density, offsetDp.y * density)
    }

    /**
     * 画面座標 → 地理座標（同期）。タップの当たり判定はブリッジの応答を待てないので
     * こちらを使う。非同期版は基底の既定実装がここへ委譲する。
     */
    override fun fromScreenOffsetSync(offset: Offset): GeoPoint? {
        val camera = cameraProvider?.invoke() ?: return null
        val density = ResourceProvider.getDensity()
        return WebMercatorScreenProjection.fromScreenOffset(
            offset = Offset(offset.x / density, offset.y / density),
            camera = camera,
            widthPx = longdoMap.width / density,
            heightPx = longdoMap.height / density,
        )
    }
}
