package com.mapconductor.longdo.circle

import com.mapconductor.core.circle.CircleController
import com.mapconductor.core.circle.CircleManager
import com.mapconductor.core.circle.CircleManagerInterface

/**
 * Longdo 用の円コントローラ。
 *
 * 追加・更新・削除の差分計算とクリックのヒットテスト（[com.mapconductor.core.circle.CircleManager] による中心
 * からの距離判定＝緯度経度ベース）はコア基底 [CircleController] が担い、実際の描画は
 * [LongdoCircleOverlayRenderer] へ委譲する。
 */
class LongdoCircleController(
    override val renderer: LongdoCircleOverlayRenderer,
    circleManager: CircleManagerInterface<LongdoCircleHandle> = CircleManager(),
) : CircleController<LongdoCircleHandle>(circleManager, renderer) {
    /** 既知の円を再適用する（地図 `ready` 後の復元用）。 */
    fun reapply() {
        renderer.reapply(circleManager.allEntities().map { it.circle })
    }
}
