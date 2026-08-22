package com.mossi.auraplayer.ui.control

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mossip.auraplayer.engine.AuraPlayer

@Composable
fun AuraVolumeSlider(
    player: AuraPlayer,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    sliderWidth: Int = 110,
) {
    val volume by player.volume.collectAsState()
    val muted by player.isMuted.collectAsState()

    // hover is tracked on the whole Row, so moving onto the slider keeps it open
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val expanded = hovered || dragging

    var lastAudible by remember { mutableStateOf(100.0) }
    LaunchedEffect(volume) { if (volume > 0.0) lastAudible = volume }

    val shown = if (muted) 0.0 else volume
    val width by animateDpAsState(
        targetValue = if (expanded) sliderWidth.dp else 0.dp,
        animationSpec = tween(180)
    )

    Row(
        modifier = modifier.hoverable(interaction),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            when {
                muted -> player.setMute(false)
                volume <= 0.0 -> player.setVolume(lastAudible)
                else -> player.setMute(true)
            }
        }) {
            Icon(
                 painter = when {
                    shown <= 0.0 -> painterResource("icons/volume-muted.svg")
                    shown < 33.0 -> painterResource("icons/volume-medium.svg")
                    shown < 66.0 -> painterResource("icons/volume-medium.svg")
                    else -> painterResource("icons/volume-full.svg")
                },
                contentDescription = if (muted) "Unmute" else "Mute",
                tint = tint
            )
        }

        // fixed-width slider revealed by an animated, clipping parent
        Box(Modifier.width(width).clipToBounds()) {
            Slider(
                value = shown.toFloat(),
                onValueChange = {
                    dragging = true
                    if (muted && it > 0f) player.setMute(false)
                    player.setVolume(it.toDouble())
                },
                onValueChangeFinished = { dragging = false },
                valueRange = 0f..100f,
                modifier = Modifier.width(sliderWidth.dp).padding(end = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = tint,
                    activeTrackColor = tint,
                    inactiveTrackColor = tint.copy(alpha = 0.3f)
                )
            )
        }
    }
}