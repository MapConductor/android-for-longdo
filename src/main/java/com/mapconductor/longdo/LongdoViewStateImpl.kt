package com.mapconductor.longdo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mapconductor.compose.map.BaseMapViewSaver
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapViewState
import com.mapconductor.core.map.MapViewStateInterface
import android.os.Bundle

/**
 * Longdo Map 用の [MapViewState] 実装インターフェース。
 * 他プロバイダの `*ViewStateInterface` と同様に、型ディスパッチで用いられる。
 */
interface LongdoViewStateInterface : MapViewStateInterface<LongdoMapDesignTypeInterface>

/**
 * Longdo Map の地図状態。カメラ位置・デザイン・コントローラを保持し、
 * MapConductor コアのカメラ操作 API を [LongdoMapViewController] へ委譲する。
 */
class LongdoViewState(
    mapDesignType: LongdoMapDesignTypeInterface,
    override val id: String,
    initialCameraPosition: MapCameraPosition = MapCameraPosition.Default,
) : MapViewState<LongdoMapDesignTypeInterface>(initialCameraPosition),
    LongdoViewStateInterface {
    private var _mapDesignType by mutableStateOf(mapDesignType)

    private var controller: LongdoMapViewController? = null

    override var mapDesignType: LongdoMapDesignTypeInterface
        get() = _mapDesignType
        set(value) {
            _mapDesignType = value
            controller?.setMapDesignType(value)
        }

    /** MapView 生成時にコントローラを紐付ける。 */
    fun setController(controller: LongdoMapViewController) {
        this.controller = controller
        // 初期カメラは LongdoMap.LOCATION として地図読み込み前に渡すので、接続時には移動しない。
        attachController(controller, moveToInitialCamera = false)
    }

    /** 現在のカメラ位置を更新する（地図移動イベントからの反映用）。 */
    fun updateCameraPosition(position: MapCameraPosition) {
        setCameraPositionInternal(position)
    }

    /** 戻り型をこのプロバイダのホルダーへ絞る（アプリが `?.map` を取れる形を保つため）。 */
    override fun getMapViewHolder(): LongdoMapViewHolder? = super.getMapViewHolder() as? LongdoMapViewHolder
}

/**
 * [LongdoViewState] の保存・復元を行う Saver。
 * デザインは [LongdoMapDesignTypeInterface.id] を保存し、[LongdoDesign.fromId] で復元する。
 */
class LongdoMapViewSaver : BaseMapViewSaver<LongdoViewState>() {
    override fun saveMapDesign(
        state: LongdoViewState,
        bundle: Bundle,
    ) {
        bundle.putString(KEY_DESIGN_ID, state.mapDesignType.id)
    }

    override fun createState(
        stateId: String,
        mapDesignBundle: Bundle?,
        cameraPosition: MapCameraPosition,
    ): LongdoViewState {
        val designId = mapDesignBundle?.getString(KEY_DESIGN_ID)
        return LongdoViewState(
            mapDesignType = LongdoDesign.fromId(designId),
            id = stateId,
            initialCameraPosition = cameraPosition,
        )
    }

    override fun getStateId(state: LongdoViewState): String = state.id

    private companion object {
        const val KEY_DESIGN_ID = "longdo_design_id"
    }
}

/**
 * [LongdoViewState] を生成・記憶する Composable ファクトリ。
 *
 * @param mapDesign 初期の地図デザイン（ベースレイヤ）。
 * @param cameraPosition 初期カメラ位置。
 */
@Composable
fun rememberLongdoMapViewState(
    mapDesign: LongdoMapDesignTypeInterface = LongdoDesign.Normal,
    cameraPosition: MapCameraPositionInterface = MapCameraPosition.Default,
): LongdoViewState {
    val initialCamera =
        cameraPosition as? MapCameraPosition
            ?: MapCameraPosition(
                position = cameraPosition.position,
                zoom = cameraPosition.zoom,
                bearing = cameraPosition.bearing,
                tilt = cameraPosition.tilt,
                paddings = cameraPosition.paddings,
            )
    return rememberSaveable(saver = LongdoMapViewSaver().createSaver()) {
        LongdoViewState(
            mapDesignType = mapDesign,
            id = "longdo-${initialCamera.hashCode()}",
            initialCameraPosition = initialCamera,
        )
    }
}
