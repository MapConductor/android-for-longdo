package com.mapconductor.longdo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mapconductor.compose.map.BaseMapViewSaver
import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.map.AttributionRule
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapViewState
import com.mapconductor.core.map.MapViewStateBundle
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
) : MapViewState<LongdoMapDesignTypeInterface>(
        initialCameraPosition = initialCameraPosition,
        // WebView ブリッジ越しでカメライベントの往復が遅い。楽観更新しないと、
        // moveCameraTo の直後に cameraPosition を読んだとき古い値が返る。
        optimisticCameraUpdate = true,
    ),
    LongdoViewStateInterface {
    private var _mapDesignType by mutableStateOf(mapDesignType)

    // Read through the base so that letting go of the controller there (MapViewBase
    // does it when the map is torn down) leaves nothing behind here.
    private val controller: LongdoMapViewController?
        get() = attachedMapController as? LongdoMapViewController

    override var mapDesignType: LongdoMapDesignTypeInterface
        get() = _mapDesignType
        set(value) {
            _mapDesignType = value
            controller?.setMapDesignType(value)
        }

    /** MapView 生成時にコントローラを紐付ける。 */
    fun setController(controller: LongdoMapViewController) {
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
 * デザインは id・レイヤ名・attributionRules を保存する。id だけで [LongdoDesign.fromId] に
 * 頼ると、定義に無いデザインは [LongdoDesign.Normal] に化け、attributionRules も落ちる。
 */
class LongdoMapViewSaver : BaseMapViewSaver<LongdoViewState>() {
    override fun saveMapDesign(
        state: LongdoViewState,
        bundle: Bundle,
    ) {
        val design = state.mapDesignType
        bundle.putString(KEY_DESIGN_ID, design.id)
        bundle.putString(KEY_LAYER_NAME, design.layerName)
        MapViewStateBundle.putAttributionRules(bundle, design.attributionRules)
    }

    override fun createState(
        stateId: String,
        mapDesignBundle: Bundle?,
        cameraPosition: MapCameraPosition,
    ): LongdoViewState =
        LongdoViewState(
            mapDesignType = restoreDesign(mapDesignBundle),
            id = stateId,
            initialCameraPosition = cameraPosition,
        )

    private fun restoreDesign(bundle: Bundle?): LongdoMapDesignTypeInterface {
        val designId = bundle?.getString(KEY_DESIGN_ID)
        // レイヤ名を持たない以前の形式は id から引く。
        val layerName = bundle?.getString(KEY_LAYER_NAME) ?: return LongdoDesign.fromId(designId)
        val id = designId ?: LongdoDesign.Normal.id
        val rules = MapViewStateBundle.getAttributionRules(bundle)
        // LongdoDesign は attributionRules を持てない。規則が無ければ LongdoDesign で戻し、
        // 定義済み（LongdoDesign.Gray など）と等しくなるようにする。
        return if (rules.isEmpty()) {
            LongdoDesign(id = id, layerName = layerName)
        } else {
            RestoredLongdoDesign(id = id, layerName = layerName, attributionRules = rules)
        }
    }

    override fun getStateId(state: LongdoViewState): String = state.id

    private companion object {
        const val KEY_DESIGN_ID = "longdo_design_id"
    }
}

// 公開 API の門番（javap）にコンパニオンの定数が載らないよう、ファイル private にする。
private const val KEY_LAYER_NAME = "longdo_layer_name"

/** attributionRules を持つデザインを復元するときの入れ物（[LongdoDesign] は規則を持てない）。 */
@InternalMapConductorApi
internal data class RestoredLongdoDesign(
    override val id: String,
    override val layerName: String,
    override val attributionRules: List<AttributionRule>,
) : LongdoMapDesignTypeInterface {
    override fun getValue(): String = id
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
