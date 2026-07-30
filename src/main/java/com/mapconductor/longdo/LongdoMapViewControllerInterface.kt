package com.mapconductor.longdo

import com.mapconductor.core.controller.MapViewControllerInterface

/**
 * Longdo Map 用のマップコントローラインターフェース。
 *
 * 他プロバイダの `*ViewControllerInterface` と同様に [MapViewControllerInterface] を拡張し、
 * 実行時の地図デザイン（ベースレイヤ）変更を追加で公開する。
 */
interface LongdoMapViewControllerInterface : MapViewControllerInterface {
    /** 実行時に地図デザイン（ベースレイヤ）を切り替える。 */
    fun setMapDesignType(value: LongdoMapDesignTypeInterface)
}
