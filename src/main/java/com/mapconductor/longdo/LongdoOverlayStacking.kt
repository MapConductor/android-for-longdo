package com.mapconductor.longdo

import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.controller.OverlayStacking

/**
 * 重なり順（[OverlayStacking]: ラスター < GroundImage < ポリゴン < サークル < ポリライン < マーカー）。
 *
 * Longdo の地図は WebView の中の MapLibre GL JS（`map.Renderer`）で、レイヤーは
 * `addLayer` した順に上へ積まれていた（追加・送り直し・スタイル差し替えの順に左右される）。
 * ここで作る JS は、足す層の帯と zIndex を `m.__mcStack` に記録し、今のスタイルの並びから
 * 「鍵が自分より大きい一番下の SDK の層」を beforeId に選んで `addLayer` する。
 * ラスターだけはベースマップのラベル（SDK の層でない最初の symbol 層）の下に入れる。
 */
internal object LongdoOverlayStacking {
    private const val FN =
        "function(m,s,b,z){var K=m.__mcStack||(m.__mcStack={});K[s.id]=[b,z];" +
            "if(m.getLayer(s.id))return;var L=m.getStyle().layers,i,k,bi=-1;" +
            "for(i=0;i<L.length;i++){k=K[L[i].id];if(k&&(k[0]>b||(k[0]==b&&k[1]>z))){bi=i;break;}}" +
            "if(b==0){for(i=0;i<L.length;i++){if(L[i].type=='symbol'&&!K[L[i].id]){if(bi<0||i<bi)bi=i;break;}}}" +
            "m.addLayer(s,bi<0?undefined:L[bi].id);}"

    /**
     * `m`（MapLibre GL JS の地図）へ [layerSpec] を帯の順で足す JS 文。
     * 既に同じ id の層があれば何もしない。
     */
    fun addLayerJs(
        layerSpec: String,
        kind: OverlayKind,
        zIndex: Int = 0,
    ): String = "($FN)(m,$layerSpec,${OverlayStacking.band(kind)},$zIndex);"

    /** ポリゴンのタイル: ポリゴンの帯の一番下（画像になったポリゴンであって、地図の画像ではない）。 */
    fun addPolygonTileLayerJs(layerSpec: String): String = addLayerJs(layerSpec, OverlayKind.Polygon, Int.MIN_VALUE)

    /** 円のタイル: サークルの帯の一番下（ポリゴンの上、ネイティブの円とポリラインのタイルの下）。 */
    fun addCircleTileLayerJs(layerSpec: String): String = addLayerJs(layerSpec, OverlayKind.Circle, Int.MIN_VALUE)

    /** ポリラインのタイル: ポリラインの帯の一番下（サークルの上、ネイティブのポリラインの下）。 */
    fun addPolylineTileLayerJs(layerSpec: String): String = addLayerJs(layerSpec, OverlayKind.Polyline, Int.MIN_VALUE)

    /** マーカーのタイル: マーカーの帯の一番下。 */
    fun addMarkerTileLayerJs(layerSpec: String): String = addLayerJs(layerSpec, OverlayKind.Marker, Int.MIN_VALUE)
}
