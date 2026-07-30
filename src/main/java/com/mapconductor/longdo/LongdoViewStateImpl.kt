package com.mapconductor.longdo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mapconductor.compose.map.BaseMapViewSaver
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapViewHolderInterface
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
) : MapViewState<LongdoMapDesignTypeInterface>(),
    LongdoViewStateInterface {
    private var _cameraPosition by mutableStateOf(initialCameraPosition)
    private var _mapDesignType by mutableStateOf(mapDesignType)

    private var controller: LongdoMapViewController? = null

    override val cameraPosition: MapCameraPosition
        get() = _cameraPosition

    override var mapDesignType: LongdoMapDesignTypeInterface
        get() = _mapDesignType
        set(value) {
            _mapDesignType = value
            controller?.setMapDesignType(value)
        }

    /** MapView 生成時にコントローラを紐付ける。 */
    fun setController(controller: LongdoMapViewController) {
        this.controller = controller
    }

    /** 現在のカメラ位置を更新する（地図移動イベントからの反映用）。 */
    fun updateCameraPosition(position: MapCameraPosition) {
        _cameraPosition = position
    }

    override fun moveCameraTo(
        cameraPosition: MapCameraPosition,
        durationMillis: Long?,
    ) {
        _cameraPosition = cameraPosition
        val ctrl = controller ?: return
        if ((durationMillis ?: 0) > 0) {
            ctrl.animateCamera(cameraPosition, durationMillis!!)
        } else {
            ctrl.moveCamera(cameraPosition)
        }
    }

    override fun moveCameraTo(
        position: GeoPoint,
        durationMillis: Long?,
    ) {
        moveCameraTo(_cameraPosition.copy(position = position), durationMillis)
    }

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) {
        controller?.fitBounds(bounds, padding)
    }

    override fun getMapViewHolder(): MapViewHolderInterface<*, *>? = controller?.holder

    override fun getControllers(): Map<String, OverlayControllerInterface<*, *>>? = controller?.getControllers()
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
