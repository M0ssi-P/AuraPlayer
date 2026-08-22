package com.mossi.auraplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.SettingsIcon
import com.mossi.auraplayer.ui.control.AuraVolumeSlider
import com.mossi.auraplayer.ui.control.PlayPause
import com.mossi.auraplayer.ui.control.TimeLabel
import com.mossi.auraplayer.ui.global.PopoverAnchored
import com.mossi.auraplayer.ui.logo.Fullscreen
import com.mossi.auraplayer.ui.menu.SettingsSheet
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.PlayerState
import com.mossip.auraplayer.engine.Segment
import kotlinx.coroutines.delay

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AuraControlBar(
    player: AuraPlayer,
    modifier: Modifier = Modifier,
    hideAfterMs: Long = 2500,
    segments: List<Segment> = emptyList(),
    seekBar: @Composable () -> Unit = { DefaultSeekBar(player, segments = segments) },
    leading: @Composable RowScope.() -> Unit = {
        PlayPause(player);
        Spacer(Modifier.width(8.dp));
        AuraVolumeSlider(player)
        Spacer(Modifier.width(8.dp))
        TimeLabel(player)
    },
    trailing: @Composable RowScope.() -> Unit = {
        val isFullscreen = AuraPlaybackCoordinator.fullscreenPlayer === player
        val isPiP = AuraPlaybackCoordinator.pipPlayer === player
        val playerBounds = LocalPlayerBounds.current

        IconButton(onClick = {
            if (isPiP) {
                AuraPiP.close(player)
            } else {
                AuraPiP.open(player)
            }
        }) {
            Icon(painterResource(if (isPiP) "icons/pipout.svg" else "icons/pip.svg"), "Picture in Picture", tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        PopoverAnchored(
            modifier = Modifier,
//            boundsAnchor = { playerBounds },
            popup = { bool ->
                SettingsSheet(player, onDismiss = { })
            }
        ) {
            Icon(painterResource("icons/settings.svg"), null, tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { AuraPlaybackCoordinator.toggleFullscreen(player) }) {
            Icon(painterResource(if(isFullscreen) "icons/fullscreenexit.svg" else "icons/fullscreen.svg"), "Fullscreen", tint = Color.White)
        }
    },
) {
    val state by player.playerState.collectAsState()
    val playing = state == PlayerState.PLAYING

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
            .fillMaxSize()
            .onPointerEvent(PointerEventType.Enter) { inside = true; lastActivity = System.nanoTime() }
            .onPointerEvent(PointerEventType.Exit)  { inside = false }
            .onPointerEvent(PointerEventType.Move)  { lastActivity = System.nanoTime(); }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { AuraPlaybackCoordinator.toggleFullscreen(player) }) {
                    player.setPause(playing)
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

                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    seekBar()
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        leading()
                        Spacer(Modifier.weight(1f))
                        trailing()
                    }
                }
            }
        }
    }
}