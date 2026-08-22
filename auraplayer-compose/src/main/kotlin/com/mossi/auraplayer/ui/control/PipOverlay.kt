package com.mossi.auraplayer.ui.control

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mossi.auraplayer.ui.AuraPiP
import com.mossi.auraplayer.ui.DefaultSeekBar
import com.mossi.auraplayer.ui.pipNoDrag
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.PlayerState
import kotlinx.coroutines.delay

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PipOverlay(
    player: AuraPlayer,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onExpand: (() -> Unit)? = null,
    hideAfterMs: Long = 2500,
) {
    val state by player.playerState.collectAsState()
    val position by player.currentTime.collectAsState()
    val duration by player.duration.collectAsState()
    val playing = state == PlayerState.PLAYING

    // any pointer activity resets the timer; leaving hides immediately
    var lastActivity by remember { mutableStateOf(0L) }
    var inside by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }

    LaunchedEffect(lastActivity, inside, scrubbing, playing) {
        if (!inside) { visible = false; return@LaunchedEffect }
        visible = true
        if (scrubbing || !playing) return@LaunchedEffect   // stay up while paused/dragging
        delay(hideAfterMs)
        visible = false
    }

    Box(
        modifier
            .onPointerEvent(PointerEventType.Enter) { inside = true; lastActivity = System.nanoTime() }
            .onPointerEvent(PointerEventType.Exit)  { inside = false }
            .onPointerEvent(PointerEventType.Move)  { lastActivity = System.nanoTime() }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onExpand?.invoke() }) {
                    lastActivity = System.nanoTime()
                }
            }
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(320)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {

                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.28f to Color.Transparent,
                            0.68f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.75f)
                        )
                    )
                )

                // ---- top bar: title + window actions ----
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    if (onExpand != null) {
                        PipIcon("icons/pipout.svg", "Back to player", 18.dp) { onExpand() }
                    }
                    if (onClose != null) {
                        PipIcon("icons/close.svg", "Close", 18.dp) { onClose() }
                    }
                }

                // ---- centre transport: −10 / play / +10 ----
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PipIcon("icons/backward10.svg", "Back 10s", 26.dp) {
                        player.seek((position - 10.0).coerceAtLeast(0.0))
                    }
                    Box(
                        Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PipIcon(
                            if (playing) "icons/pause.svg" else "icons/play.svg",
                            if (playing) "Pause" else "Play", 28.dp
                        ) { player.setPause(playing) }
                    }
                    PipIcon("icons/forward10.svg", "Forward 10s", 26.dp) {
                        player.seek((position + 10.0).coerceAtMost(duration))
                    }
                }

                // ---- bottom: scrubber + time ----
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    DefaultSeekBar(player, Modifier.pipNoDrag(AuraPiP.pipWindow?.windowHandle ?: 0))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${fmt(position)} / ${fmt(duration)}",
                            color = Color.White, fontSize = 11.sp
                        )
                        Spacer(Modifier.weight(1f))
                        PipVolume(player) { lastActivity = System.nanoTime() }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipIcon(
    icon: String,
    desc: String,
    size: Dp,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.pipNoDrag(AuraPiP.pipWindow?.windowHandle ?: 0).size(size + 14.dp)) {
        Icon(painterResource(icon), desc, tint = Color.White, modifier = Modifier.size(size))
    }
}

/** Compact volume: icon expands to a slider on hover. */
@Composable
private fun PipVolume(player: AuraPlayer, onActivity: () -> Unit) {
    val volume by player.volume.collectAsState()
    val muted by player.isMuted.collectAsState()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var lastAudible by remember { mutableStateOf(100.0) }
    LaunchedEffect(volume) { if (volume > 0.0) lastAudible = volume }

    val shown = if (muted) 0.0 else volume
    val width by animateDpAsState(
        if (hovered) 72.dp else 0.dp, tween(160)
    )

    Row(Modifier.hoverable(interaction), verticalAlignment = Alignment.CenterVertically) {
        PipIcon(
            when {
                shown <= 0.0 -> "icons/volume-muted.svg"
                shown < 50.0 -> "icons/volume-medium.svg"
                else -> "icons/volume-full.svg"
            },
            if (muted) "Unmute" else "Mute", 16.dp
        ) {
            when {
                muted -> player.setMute(false)
                volume <= 0.0 -> player.setVolume(lastAudible)
                else -> player.setMute(true)
            }
            onActivity()
        }
        Box(Modifier.width(width).clipToBounds()) {
            Slider(
                value = shown.toFloat(),
                onValueChange = {
                    if (muted && it > 0f) player.setMute(false)
                    player.setVolume(it.toDouble())
                    onActivity()
                },
                valueRange = 0f..100f,
                modifier = Modifier.width(72.dp).height(16.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                )
            )
        }
    }
}