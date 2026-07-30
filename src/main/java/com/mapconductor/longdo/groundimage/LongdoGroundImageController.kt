package com.mapconductor.longdo.groundimage

import com.mapconductor.core.groundimage.GroundImageController
import com.mapconductor.core.groundimage.GroundImageManager
import com.mapconductor.core.groundimage.GroundImageManagerInterface

/**
 * Longdo 用のグラウンドイメージコントローラ。
 *
 * 追加・更新・削除の差分計算とクリックのヒットテスト（[com.mapconductor.core.groundimage.GroundImageManager] に
 * よる bounds への内外判定＝緯度経度ベース）はコア基底 [GroundImageController] が担い、実際の描画は
 * [LongdoGroundImageOverlayRenderer] へ委譲する。
 */
class LongdoGroundImageController(
    override val renderer: LongdoGroundImageOverlayRenderer,
    groundImageManager: GroundImageManagerInterface<LongdoGroundImageHandle> = GroundImageManager(),
) : GroundImageController<LongdoGroundImageHandle>(groundImageManager, renderer) {
    /** 既知のグラウンドイメージを再適用する（地図 `ready` 後の復元用）。 */
    fun reapply() {
        renderer.reapply(groundImageManager.allEntities().map { it.groundImage })
    }
}
