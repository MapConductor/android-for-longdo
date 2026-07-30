package com.mapconductor.longdo.polygon

import com.mapconductor.core.polygon.PolygonController
import com.mapconductor.core.polygon.PolygonManager
import com.mapconductor.core.polygon.PolygonManagerInterface

/**
 * Longdo 用のポリゴンコントローラ。
 *
 * 追加・更新・削除の差分計算とヒットテスト（[com.mapconductor.core.polygon.PolygonManager] による測地線対応の
 * 巻き数判定＝緯度経度ベースの内外判定）はコア基底 [PolygonController] が担い、実際の描画は
 * [LongdoPolygonOverlayRenderer] へ委譲する。
 */
class LongdoPolygonController(
    override val renderer: LongdoPolygonOverlayRenderer,
    polygonManager: PolygonManagerInterface<LongdoPolygonHandle> = PolygonManager(),
) : PolygonController<LongdoPolygonHandle>(polygonManager, renderer) {
    /** 既知のポリゴンを再適用する（地図 `ready` 後の復元用）。 */
    fun reapply() {
        renderer.reapply(polygonManager.allEntities().map { it.polygon })
    }
}
