package com.mapconductor.longdo

import com.mapconductor.core.map.MapDesignTypeInterface

/**
 * Longdo Map の地図デザイン（ベースレイヤ）を表すインターフェース。
 *
 * 他プロバイダの `*MapDesignTypeInterface` と同じく [MapDesignTypeInterface] を実装する。
 * [id] はデザインを一意に識別する文字列で、状態の保存・復元にも用いる。
 * [layerName] は Longdo Map JS API3 の `longdo.Layers.<NAME>` に対応するベースレイヤ名。
 */
interface LongdoMapDesignTypeInterface : MapDesignTypeInterface<String> {
    /** `longdo.Layers` 配下のベースレイヤ名（例: `NORMAL` / `GRAY` / `DARK`）。 */
    val layerName: String
}

/**
 * Longdo Map のベースレイヤを指す地図デザイン。
 *
 * @property id デザイン識別子。
 * @property layerName Longdo のベースレイヤ名（`longdo.Layers.<layerName>`）。
 */
data class LongdoDesign(
    override val id: String,
    override val layerName: String,
) : LongdoMapDesignTypeInterface {
    override fun getValue(): String = id

    companion object {
        // 以下は Longdo Map API3（`longdo.Layers`）が標準提供するベースレイヤ。
        // レイヤ名は SDK の `longdo.Layers` に実在するものを用いる（実行時列挙で検証済み）。

        /** 標準地図。 */
        val Normal = LongdoDesign("Normal", "NORMAL")

        /** シンプルで見やすい地図。 */
        val Easy = LongdoDesign("Easy", "EASY")

        /** パステル調地図。 */
        val Pastel = LongdoDesign("Pastel", "PASTEL")

        /** パステル調グレースケール地図。 */
        val PastelGray = LongdoDesign("PastelGray", "PASTEL_GRAY")

        /** 高コントラスト地図。 */
        val Hard = LongdoDesign("Hard", "HARD")

        /** グレースケール地図。 */
        val Gray = LongdoDesign("Gray", "GRAY")

        /** 明るい地図。 */
        val Light = LongdoDesign("Light", "LIGHT")

        /** 夜間（暗色）地図。 */
        val Night = LongdoDesign("Night", "NIGHT")

        /** ダークテーマ地図。 */
        val Dark = LongdoDesign("Dark", "DARK")

        /** 行政界（政治）地図。 */
        val Political = LongdoDesign("Political", "POLITICAL")

        /** OpenStreetMap ベース地図。 */
        val Osm = LongdoDesign("Osm", "OSM")

        /** 航空写真（衛星画像）。 */
        val Satellite = LongdoDesign("Satellite", "SPHERE_IMAGES")

        /** 航空写真＋ラベル（ハイブリッド）。 */
        val Hybrid = LongdoDesign("Hybrid", "SPHERE_HYBRID")

        /** SDK が標準提供する全デザイン（[MapDesignMapPage] のセレクタで用いる）。 */
        val all: List<LongdoDesign> =
            listOf(
                Normal, Easy, Pastel, PastelGray, Hard, Gray,
                Light, Night, Dark, Political, Osm, Satellite, Hybrid,
            )

        /** 保存済みの [id] からデザインを復元する。未知の場合は [Normal] を返す。 */
        fun fromId(id: String?): LongdoDesign = all.firstOrNull { it.id == id } ?: Normal
    }
}
