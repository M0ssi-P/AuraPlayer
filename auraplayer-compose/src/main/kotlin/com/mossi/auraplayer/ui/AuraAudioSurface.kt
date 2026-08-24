package com.mossi.auraplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.mossip.auraplayer.engine.AuraPlayer

@Composable
fun AuraAudioSurface(
    player: AuraPlayer,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = { AuraAudioControls(player) },
) {
    LaunchedEffect(player) {
        if (!player.isInitialized.value) player.initializeRenderApi(audioOnly = true)
    }

    Box(modifier) { content() }
}