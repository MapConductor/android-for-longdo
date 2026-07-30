package com.mapconductor.longdo

import androidx.compose.ui.geometry.Offset
import com.longdo.sdk3.LongdoMap
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapViewHolderInterface

/**
 * Longdo Map 用の [MapViewHolderInterface] 実装。
 *
 * Longdo Map API3 SDK は WebView（Longdo Map JS API3）ベースで、ネイティブの `MapView` / `Map` を
 * 直接公開しない。実体としては [LongdoMap]（WebView サブクラス）を保持し、地図操作は JS ブリッジ
 * （`call` / `run`）経由で行う。
 *
 * 座標⇔スクリーン変換はブリッジが同期 API を提供しないため未対応（null を返す）。
 */
class LongdoMapViewHolder(
    private val longdoMap: LongdoMap,
) : MapViewHolderInterface<LongdoMap, LongdoMap> {
    override val mapView: LongdoMap = longdoMap
    override val map: LongdoMap = longdoMap

    /** 地理座標 → 画面座標。Longdo は同期変換 API を持たないため未対応。 */
    override fun toScreenOffset(position: GeoPointInterface): Offset? = null

    /** 画面座標 → 地理座標。Longdo は任意点の同期 unproject を持たないため未対応。 */
    override suspend fun fromScreenOffset(offset: Offset): GeoPoint? = null
}
