package com.mossi.auraplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mossip.auraplayer.engine.AuraPlayer

/**
 * Renders `player`'s video at this position in the layout.
 *
 * Call this exactly where you want the video to normally sit — a feed item,
 * a detail screen, wherever. Fullscreen and PiP are handled transparently:
 * when this player becomes the fullscreen or PiP owner (via the controls
 * you provide calling AuraPlaybackCoordinator, typically through a button),
 * this call site shows a placeholder instead of a live second view, and the
 * real video moves to AuraPlayerHost's overlay slot (fullscreen) or a
 * separate floating window (PiP) — see AuraPlayerHost.kt / AuraPiP.kt.
 *
 * `controls` receives the same BoxScope either way, so your control bar
 * code doesn't need to know which mode it's rendering in.
 */
@Composable
fun AuraPlayerSurface(
    player: AuraPlayer,
    modifier: Modifier = Modifier,
    controls: @Composable BoxScope.() -> Unit = {},
) {
    // Publish this call site's controls so the fullscreen overlay (owned by
    // AuraPlayerHost, which has no idea what your controls look like) can
    // render them when this player is promoted to fullscreen.
    DisposableEffect(player, controls) {
        fullscreenControls[player] = controls
        onDispose { fullscreenControls.remove(player, controls) }
    }

    val isFullscreenHere = AuraPlaybackCoordinator.fullscreenPlayer === player
    val isPiPHere = AuraPlaybackCoordinator.pipPlayer === player

    if (isFullscreenHere || isPiPHere) {
        // The live view now lives elsewhere (host overlay / PiP window).
        // Reserve the layout space with an inert placeholder so surrounding
        // UI doesn't jump, but don't stand up a second producer/consumer.
        Box(modifier.background(Color.Black))
        return
    }

    val strategy = remember { platformStrategy() }
    strategy.Render(player, modifier, controls)
}