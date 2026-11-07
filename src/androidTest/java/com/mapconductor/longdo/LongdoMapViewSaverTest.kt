package com.mapconductor.longdo

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.AttributionRule
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapUISettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Bundle
import android.os.Parcel

/**
 * 回転などの構成変更で state が [LongdoMapViewSaver] を通って作り直されても、デザイン（id・
 * attributionRules ほか）と uiSettings・カメラが落ちないこと。Bundle は実機の Parcel を一度通す。
 */
@RunWith(AndroidJUnit4::class)
class LongdoMapViewSaverTest {
    private val rules =
        listOf(AttributionRule("© test"), AttributionRule("© ranged", minZoom = 3, maxZoom = 15))

    private val camera =
        MapCameraPosition(
            position = GeoPoint(21.38, -157.93),
            zoom = 12.0,
            bearing = 20.0,
            tilt = 10.0,
        )

    @Test
    fun keepsDesignOutsideThePresets() {
        // 以前は id だけを保存して fromId で戻しており、定義に無い id は Normal になっていた。
        val design = LongdoDesign(id = "my-gray", layerName = "GRAY")
        val state = LongdoViewState(mapDesignType = design, id = "longdo-1", initialCameraPosition = camera)
        state.uiSettings = MapUISettings(rotateGesture = false, tiltGesture = false)

        val restored = roundTrip(LongdoMapViewSaver().createSaver(), state)

        assertEquals(design, restored.mapDesignType)
        assertCommon(restored, "longdo-1")
    }

    @Test
    fun keepsAttributionRulesOfAnAppDesign() {
        val design =
            object : LongdoMapDesignTypeInterface {
                override val id: String = "app-design"
                override val layerName: String = "POLITICAL"
                override val attributionRules: List<AttributionRule> = rules

                override fun getValue(): String = id
            }
        val restored = roundTrip(LongdoMapViewSaver().createSaver(), LongdoViewState(design, "longdo-2"))

        assertEquals("app-design", restored.mapDesignType.id)
        assertEquals("POLITICAL", restored.mapDesignType.layerName)
        assertEquals(rules, restored.mapDesignType.attributionRules)
    }

    @Test
    fun keepsPresetDesign() {
        val restored = roundTrip(LongdoMapViewSaver().createSaver(), LongdoViewState(LongdoDesign.Pastel, "longdo-3"))
        assertEquals(LongdoDesign.Pastel, restored.mapDesignType)
    }

    private fun assertCommon(
        restored: com.mapconductor.core.map.MapViewStateInterface<*>,
        id: String,
    ) {
        assertEquals(id, restored.id)
        assertEquals(false, restored.uiSettings.rotateGesture)
        assertEquals(false, restored.uiSettings.tiltGesture)
        assertEquals(true, restored.uiSettings.scrollGesture)
        assertEquals(12.0, restored.cameraPosition.zoom, 1e-9)
        assertEquals(20.0, restored.cameraPosition.bearing, 1e-9)
        assertEquals(21.38, restored.cameraPosition.position.latitude, 1e-9)
    }

    private fun <T : Any> roundTrip(
        saver: Saver<T, Bundle>,
        value: T,
    ): T {
        val saved = with(saver) { SaverScope { true }.save(value) }!!
        // 一度 Parcel に書いて読み戻す（プロセスが落ちたあとの復元と同じ経路）。
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            return saver.restore(parcel.readBundle(javaClass.classLoader)!!)!!
        } finally {
            parcel.recycle()
        }
    }
}
