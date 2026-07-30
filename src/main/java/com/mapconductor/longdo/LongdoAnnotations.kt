package com.mapconductor.longdo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mapconductor.compose.info.InfoBubbleEntry
import com.mapconductor.core.marker.DefaultMarkerIcon
import com.mapconductor.core.marker.MarkerAnimation
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.settings.Settings
import kotlin.math.roundToInt
import android.view.animation.BounceInterpolator
import android.view.animation.LinearInterpolator

/**
 * Longdo（WebView / MapLibre GL）上に重ねるコンポーズオーバーレイの画面座標を共有する保持体。
 *
 * [renderPx] は各オーバーレイ（マーカー・InfoBubble）の「地図上の基準点（マーカー座標）」の
 * 画面座標（デバイスピクセル）。地図移動時は JS の投影結果で更新され、マーカードラッグ中は
 * ドラッグ量で上書きされる。マーカーと同 id の InfoBubble は同じ [renderPx] を参照するため、
 * 地図ドラッグ・マーカードラッグのどちらでも吹き出しがマーカーへ追従する。
 */
internal class LongdoOverlayState {
    val renderPx = mutableStateMapOf<String, Offset>()

    /** ドラッグ中のマーカー id。投影結果での上書きを避けるために用いる。 */
    var draggingId by mutableStateOf<String?>(null)
}

/**
 * マーカーを Longdo 上のコンポーズオーバーレイとして描画する。
 *
 * アイコンのアンカー点を [LongdoOverlayState.renderPx] の座標に一致させて配置する。タップで
 * [MarkerState.onClick] を配送し、ドラッグ中は画面座標を直接更新して追従、離した時点で
 * [onDragCommit] へ最終画面座標を渡して地理座標へ逆投影・確定する。
 */
@Composable
internal fun LongdoMarkerOverlay(
    marker: MarkerState,
    state: LongdoOverlayState,
    onDragCommit: (marker: MarkerState, finalDevicePx: Offset) -> Unit,
    onDragMove: (marker: MarkerState, devicePx: Offset) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current.density
    val bitmapIcon = remember(marker.icon) { (marker.icon ?: DefaultMarkerIcon()).toBitmapIcon() }
    val widthPx = bitmapIcon.size.width
    val heightPx = bitmapIcon.size.height
    val anchor = bitmapIcon.anchor

    val anchorPx = state.renderPx[marker.id] ?: return

    // アイコンのアンカー点が anchorPx に一致するよう左上を決める。
    val topLeftX = (anchorPx.x - anchor.x * widthPx)
    val topLeftY = (anchorPx.y - anchor.y * heightPx)

    // ドロップ／バウンス演出。アイコンへ縦方向 translation を重ねてアニメーションさせる（id ごとに一度だけ）。
    // 演出指定がある間は最初のフレームで画面外へ退避し、着地位置がちらつくのを防ぐ。
    val dropTranslation =
        remember(marker.id) {
            Animatable(if (marker.getAnimation() != null) MARKER_ANIMATION_OFFSCREEN_PX else 0f)
        }
    // marker.id だけでなく現在のアニメーション指定もキーにする（同一 id へ後から animate を設定するケース対応）。
    LaunchedEffect(marker.id, marker.getAnimation()) {
        val animation = marker.getAnimation() ?: return@LaunchedEffect
        // 落下開始位置：現在のスクリーン Y（デバイス px）＋アイコン高だけ上（＝画面上端の外）。
        val startTranslation = -(anchorPx.y + heightPx)
        val durationMs =
            when (animation) {
                MarkerAnimation.Drop -> Settings.Default.markerDropAnimateDuration
                MarkerAnimation.Bounce -> Settings.Default.markerBounceAnimateDuration
            }.toInt().coerceAtLeast(1)
        val interpolator =
            when (animation) {
                MarkerAnimation.Bounce -> BounceInterpolator()
                MarkerAnimation.Drop -> LinearInterpolator()
            }
        dropTranslation.snapTo(startTranslation)
        // 演出開始を通知する（他プロバイダの onAnimate 経路と同じく onAnimateStart / onAnimateEnd を発火する）。
        // Longdo のマーカーはコンポーズオーバーレイのため、コアの MarkerController 経由ではなくこの演出効果から
        // 直接発火する。これにより onAnimateEnd 購読側（例: ドロップ完了後に InfoBubble を表示する画面）が機能する。
        marker.onAnimateStart?.invoke(marker)
        dropTranslation.animateTo(
            targetValue = 0f,
            animationSpec =
                tween(
                    durationMillis = durationMs,
                    easing =
                        Easing { f ->
                            interpolator.getInterpolation(f)
                        },
                ),
        )
        // 演出完了を通知してからアニメーション指定を解除する（演出は一度きり）。
        marker.onAnimateEnd?.invoke(marker)
        marker.animate(null)
    }

    Box(
        modifier =
            Modifier
                .offset { IntOffset(topLeftX.roundToInt(), topLeftY.roundToInt()) }
                .pointerInput(marker.id, marker.clickable, marker.draggable) {
                    // WebView がドラッグ（MOVE）を横取りするため、DOWN を即 consume してジェスチャを確保する。
                    // タップ／ドラッグを 1 つのハンドラで判別し、ドラッグ中は画面座標を直接更新して追従、
                    // 離した時点で最終座標を [onDragCommit] へ渡して逆投影・確定する。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!marker.clickable && !marker.draggable) return@awaitEachGesture
                        down.consume()
                        val base = state.renderPx[marker.id] ?: Offset.Zero
                        var accum = Offset.Zero
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!dragging) {
                                    if (marker.clickable) marker.onClick?.invoke(marker)
                                } else {
                                    state.draggingId = null
                                    onDragCommit(marker, base + accum)
                                }
                                change.consume()
                                break
                            }
                            val delta = change.positionChange()
                            if (!dragging && marker.draggable && delta.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                                state.draggingId = marker.id
                                marker.onDragStart?.invoke(marker)
                            }
                            if (dragging) {
                                accum += delta
                                state.renderPx[marker.id] = base + accum
                                // ドラッグ中も座標を随時確定して onDrag を発火する（他モジュールと同じ挙動）。
                                // 逆投影は JS 経由で非同期のため、購読側の連続更新（例: グラウンドイメージの
                                // 追従リサイズ）に対応する。
                                onDragMove(marker, base + accum)
                                change.consume()
                            }
                        }
                    }
                },
    ) {
        Image(
            bitmap = bitmapIcon.bitmap.asImageBitmap(),
            contentDescription = null,
            modifier =
                Modifier
                    .size((widthPx / density).dp, (heightPx / density).dp)
                    .graphicsLayer { translationY = dropTranslation.value },
        )
    }
}

/** ドロップ演出開始前にアイコンを退避させておく画面外の縦位置（デバイス px）。 */
private const val MARKER_ANIMATION_OFFSCREEN_PX = -10000f

/**
 * InfoBubble を Longdo 上のコンポーズオーバーレイとして描画する。
 *
 * 配置はコアの `InfoBubbleOverlay` と同一の式で、吹き出し側の接続点（[InfoBubbleEntry.tailOffset]）を
 * マーカー投影点 +（infoAnchor − iconAnchor）× iconSize に一致させる。基準点は [LongdoOverlayState.renderPx]
 * （マーカーと同 id）を参照するため、地図ドラッグ・マーカードラッグのどちらにも追従する。
 */
@Composable
internal fun LongdoInfoBubbleOverlay(
    entry: InfoBubbleEntry,
    state: LongdoOverlayState,
) {
    val bitmapIcon = remember(entry.icon) { entry.icon?.toBitmapIcon() }
    val iconSize = bitmapIcon?.size ?: Size.Zero
    val iconAnchor = entry.icon?.anchor ?: Offset(0.5f, 0.5f)
    val infoAnchor = entry.icon?.infoAnchor ?: Offset(0.5f, 0.5f)
    val tailOffset = entry.tailOffset

    val posOffset = state.renderPx[entry.id] ?: return

    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }

    val x =
        posOffset.x +
            (-tailOffset.x * bubbleSize.width) +
            ((0.5f - iconAnchor.x) * iconSize.width) +
            ((infoAnchor.x - 0.5f) * iconSize.width)
    val y =
        posOffset.y +
            (-tailOffset.y * bubbleSize.height) +
            ((0.5f - iconAnchor.y) * iconSize.height) +
            ((infoAnchor.y - 0.5f) * iconSize.height)

    Box(
        modifier =
            Modifier
                .onGloballyPositioned { bubbleSize = it.size }
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) },
    ) {
        entry.content()
    }
}
