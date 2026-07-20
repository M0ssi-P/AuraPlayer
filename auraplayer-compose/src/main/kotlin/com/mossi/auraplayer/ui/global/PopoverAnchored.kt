package com.mossi.auraplayer.ui.global

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun PopoverAnchored(
    modifier: Modifier = Modifier,
    hoverEnabled: Boolean = false,
    gap: Int = 10,
    onChange: (e: MutableState<Boolean>) -> Unit = {},
    edgePadding: Dp = 12.dp,
    popup: @Composable (e: MutableState<Boolean>) -> Unit,
    content: @Composable (e: Boolean) -> Unit
) {
    val anchorInteraction = remember { MutableInteractionSource() }
    val popupInteraction = remember { MutableInteractionSource() }
    val showTooltip = remember { mutableStateOf(false) }
    var popupHeight by remember { mutableStateOf(0) }

    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    var anchorInRoot by remember { mutableStateOf(IntOffset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    val anchorHovered by anchorInteraction.collectIsHoveredAsState()
    val popupHovered by popupInteraction.collectIsHoveredAsState()
    var anchorWidth by remember { mutableStateOf(0) }

    LaunchedEffect(anchorHovered, popupHovered) {
        if (!hoverEnabled) return@LaunchedEffect
        if (anchorHovered || popupHovered) {
            showTooltip.value = true
        } else {
            delay(150)          // grace period to cross the gap
            showTooltip.value = false
        }
    }

    LaunchedEffect(showTooltip.value) { onChange(showTooltip) }

    Box {
        Box(
            Modifier
                .onGloballyPositioned {
                    anchorSize = it.size
                    val b = it.boundsInRoot()
                    anchorInRoot = IntOffset(b.left.toInt(), b.top.toInt())
                    rootSize = it.findRootCoordinates().size
                }
                .clickable(
                    interactionSource = anchorInteraction,
                    indication = null,
                    onClick = { if (!hoverEnabled) showTooltip.value = !showTooltip.value }
                )
                .hoverable(anchorInteraction)
        ) {
            content(showTooltip.value)
        }

        Box(
            Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) {
                    val edge = edgePadding.roundToPx()

                    // centered on the anchor, in root space
                    val centered = anchorInRoot.x + (anchorSize.width - placeable.width) / 2

                    // shift inward if it would overflow either edge
                    val maxX = (rootSize.width - placeable.width - edge).coerceAtLeast(edge)
                    val x = centered.coerceIn(edge.coerceAtMost(maxX), maxX) - anchorInRoot.x

                    // flip below if there isn't room above
                    val needed = placeable.height + gap
                    val y = if (anchorInRoot.y >= needed) -needed else anchorSize.height + gap

                    placeable.place(x, y)
                }
            }
        ) {
            AnimatedVisibility(
                visible = showTooltip.value,
                enter = fadeIn() + scaleIn(initialScale = 0.95f),
                exit = fadeOut() + scaleOut(targetScale = 0.95f)
            ) {
                Box(modifier.hoverable(popupInteraction)) {
                    popup(showTooltip)
                }
            }
        }
    }
}