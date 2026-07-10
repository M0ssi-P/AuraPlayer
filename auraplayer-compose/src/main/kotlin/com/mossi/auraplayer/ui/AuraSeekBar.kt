package com.mossi.auraplayer.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.Segment

@Composable
fun DefaultSeekBar(
    player: AuraPlayer,
    modifier: Modifier = Modifier,
    segments: List<Segment> = emptyList(),
    accent: Color = Color(0xFFE53935),
) {
    val position by player.currentTime.collectAsState()
    val duration by player.duration.collectAsState()
    val buffer by player.bufferDuration.collectAsState()

    AuraSeekBar(
        position = position,
        duration = duration,
        buffered = position + buffer,   // mpv reports cache *ahead* of position
        segments = segments,
        onSeek = player::seek,
        modifier = modifier,
    )
}

@Composable
fun AuraSeekBar(
    position: Double,
    duration: Double,
    buffered: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    segments: List<Segment> = emptyList(),
    accent: Color = Color(0xFFE53935),
    gap: Dp = 2.dp,
    trackHeight: Dp = 3.dp,
    activeHeight: Dp = 5.dp,
) {
    var hovered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var scrubTime by remember { mutableStateOf<Double?>(null) }

    val active = hovered || dragging
    val barHeight by animateDpAsState(if (active) activeHeight else trackHeight, tween(120))
    val knobRadius by animateDpAsState(if (active) 6.dp else 0.dp, tween(120))

    // While dragging, show the scrub position rather than the (lagging) player time.
    val shown = scrubTime ?: position
    val safeDuration = duration.coerceAtLeast(0.001)

    val segs = remember(segments, safeDuration) {
        if (segments.isNotEmpty()) segments else listOf(Segment(0.0, safeDuration))
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(activeHeight * 3)          // generous hit area, thin visuals
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        when (e.type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit  -> hovered = false
                            else -> Unit
                        }
                    }
                }
            }
            .pointerInput(safeDuration) {
                detectTapGestures { off ->
                    onSeek((off.x / size.width).coerceIn(0f, 1f) * safeDuration)
                }
            }
            .pointerInput(safeDuration) {
                detectDragGestures(
                    onDragStart = { off ->
                        dragging = true
                        scrubTime = (off.x / size.width).coerceIn(0f, 1f) * safeDuration
                    },
                    onDrag = { change, _ ->
                        scrubTime = (change.position.x / size.width)
                            .coerceIn(0f, 1f) * safeDuration
                    },
                    onDragEnd = {
                        scrubTime?.let(onSeek)
                        scrubTime = null
                        dragging = false
                    },
                    onDragCancel = { scrubTime = null; dragging = false },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val gapPx = gap.toPx()
            val hPx = barHeight.toPx()
            val y = center.y

            segs.forEach { seg ->
                val x0 = (seg.start / safeDuration * size.width).toFloat()
                val x1 = (seg.end / safeDuration * size.width).toFloat() - gapPx
                if (x1 <= x0) return@forEach

                fun bar(fromT: Double, toT: Double, color: Color) {
                    val a = (fromT / safeDuration * size.width).toFloat().coerceIn(x0, x1)
                    val b = (toT / safeDuration * size.width).toFloat().coerceIn(x0, x1)
                    if (b > a) drawRoundRect(
                        color = color,
                        topLeft = Offset(a, y - hPx / 2),
                        size = Size(b - a, hPx),
                        cornerRadius = CornerRadius(hPx / 2),
                    )
                }

                bar(seg.start, seg.end, Color.White.copy(alpha = 0.28f))   // track
                bar(seg.start, buffered, Color.White.copy(alpha = 0.50f))  // buffered
                bar(seg.start, shown, accent)                              // played
            }

            if (knobRadius > 0.dp) {
                val px = (shown / safeDuration * size.width).toFloat()
                drawCircle(accent, knobRadius.toPx(), Offset(px, y))
            }
        }
    }
}