package com.mapconductor.longdo.polyline

import com.mapconductor.core.polyline.PolylineController
import com.mapconductor.core.polyline.PolylineManager
import com.mapconductor.core.polyline.PolylineManagerInterface

/**
 * Longdo 用のポリラインコントローラ。
 *
 * 追加・更新・削除の差分計算とヒットテスト（[com.mapconductor.core.polyline.PolylineManager] による緯度経度
 * ベースの判定）はコア基底 [PolylineController] が担い、実際の描画は [LongdoPolylineOverlayRenderer] へ委譲する。
 */
class LongdoPolylineController(
    override val renderer: LongdoPolylineOverlayRenderer,
    polylineManager: PolylineManagerInterface<LongdoPolylineHandle> = PolylineManager(),
) : PolylineController<LongdoPolylineHandle>(polylineManager, renderer) {
    /** 既知のポリラインを再適用する（地図 `ready` 後の復元用）。 */
    fun reapply() {
        renderer.reapply(polylineManager.allEntities().map { it.polyline })
    }
}
