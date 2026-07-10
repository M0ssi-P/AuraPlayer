package com.mossi.auraplayer.ui.control

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.PlayerState

@Composable
fun PlayPause(
    player: AuraPlayer,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = Color.White,
) {
    val state by player.playerState.collectAsState()
    val isPlaying = state == PlayerState.PLAYING
    val isBusy = state == PlayerState.LOADING || state == PlayerState.BUFFERING

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (isBusy) {
            CircularProgressIndicator(
                Modifier.size(size * 0.6f), color = tint, strokeWidth = 2.dp
            )
        } else {
            IconButton(onClick = { player.setPause(isPlaying) }) {
                // Crossfade so the glyph swap doesn't pop
                Crossfade(isPlaying, animationSpec = tween(120), label = "playpause") { playing ->
//                    Icon(
//                        painterResource(if (playing)"icons/pause.svg" else "icons/play.svg"),
//                        contentDescription = if (playing) "Pause" else "Play",
//                        tint = tint,
//                        modifier = Modifier.size(size * 0.7f),
//                    )
                }
            }
        }
    }
}