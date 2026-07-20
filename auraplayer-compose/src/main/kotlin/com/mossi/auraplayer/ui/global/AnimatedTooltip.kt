package com.mossi.auraplayer.ui.global

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup

@Composable
fun AnimatedTooltip(
    modifier: Modifier = Modifier,
    visible: MutableState<Boolean>,
    hoverEnabled: Boolean = false,
    offset: IntOffset,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    LaunchedEffect(isHovered) {
        if(hoverEnabled) {
            visible.value = isHovered
        }
    }

    Popup(
        offset = offset,
        onDismissRequest = {
            visible.value = false
        }
    ) {
        AnimatedVisibility(
            visible = visible.value,
            enter = fadeIn() + scaleIn(initialScale = 0.95f),
            exit = fadeOut(animationSpec = tween(
                delayMillis = 100
            )) + scaleOut(targetScale = 0.95f, animationSpec = tween(
                delayMillis = 100
            ))
        ) {
            Box(
                modifier = modifier
                    .hoverable(interactionSource = interactionSource)
            ) {
                content()
            }
        }
    }
}