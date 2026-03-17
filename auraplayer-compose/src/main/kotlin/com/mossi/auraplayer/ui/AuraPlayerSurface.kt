package com.mossi.auraplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.PlayerState
import kotlinx.coroutines.flow.first
import java.awt.Canvas

@Composable
fun AuraPlayerSurface(auraPlayer: AuraPlayer, audioOnly: Boolean = false, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val isInitialized by auraPlayer.isInitialized.collectAsState()
    val canvas = remember { Canvas().apply {
        background = java.awt.Color.BLACK
    } }

    Box(modifier) {
        SwingPanel(
            background = Color.Black,
            factory = {
                canvas
            },
            update = {
                if (canvas.isDisplayable && canvas.graphicsConfiguration != null) {
                    if (!isInitialized) {
                        auraPlayer.initialize(canvas, audioOnly)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        content()
    }
}