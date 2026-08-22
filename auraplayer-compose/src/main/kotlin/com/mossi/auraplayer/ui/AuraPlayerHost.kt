package com.mossi.auraplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import com.mossip.auraplayer.engine.AuraPlayer
import java.util.concurrent.ConcurrentHashMap

internal val LocalAuraHost = staticCompositionLocalOf<AuraHostState?> { null }
internal val LocalComposeWindow = staticCompositionLocalOf<ComposeWindow?> { null }
val LocalPlayerBounds = compositionLocalOf<LayoutCoordinates?> { null }

/**
 * Must be the first/outermost element in your window's content — everything
 * else (your app UI, every AuraPlayerSurface call site) lives inside it.
 *
 * It does two things:
 *  1. Publishes the ComposeWindow + a per-window AuraHostState so any
 *     AuraPlayerSurface anywhere in `content` can find them.
 *  2. Owns a single "fullscreen slot" above `content` in z-order. When
 *     AuraPlaybackCoordinator.fullscreenPlayer is set, that player's video
 *     renders here — full window, above everything, so any modal opened by
 *     the app's own controls composes naturally on top of it (this is the
 *     thing the old DComp hole-punch approach could never do).
 *
 * Usage:
 *   AuraPlayerHost(window) {
 *       MyAppScreen()   // contains AuraPlayerSurface(player) somewhere inside
 *   }
 */
@Composable
fun AuraPlayerHost(
    window: ComposeWindow,
    isPip: Boolean = false,
    content: @Composable () -> Unit,
) {
    val host = remember(window) { AuraHostState(window) }

    CompositionLocalProvider(
        LocalAuraHost provides host,
        LocalComposeWindow provides window,
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            val fsPlayer = if (isPip) {
                AuraPlaybackCoordinator.pipPlayer
            } else {
                AuraPlaybackCoordinator.fullscreenPlayer
            }
            // Fullscreen overlay: last in the tree = top z-order. A player
            // rendered inline (via AuraPlayerSurface) hides itself while it
            // is the fullscreen owner — see AuraPlayerSurface below — so
            // there is never a duplicate live view of the same player.
            if (fsPlayer != null) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    platformStrategy().Render(
                        player = fsPlayer,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // App-provided controls for the fullscreen player are
                        // supplied by whichever AuraPlayerSurface call site
                        // owns it — see fullscreenControls below.
                        if (isPip) {
                            pipControls[fsPlayer]?.invoke(this)
                        } else {
                            fullscreenControls[fsPlayer]?.invoke(this)
                        }
                    }
                }
            }
        }
    }
}

/** Lets the inline AuraPlayerSurface call site hand its `controls` lambda to
 *  the fullscreen overlay when that player becomes the fullscreen owner,
 *  without threading it through the coordinator itself. */
internal val fullscreenControls = ConcurrentHashMap<AuraPlayer, @Composable BoxScope.() -> Unit>()
internal val pipControls = ConcurrentHashMap<AuraPlayer, @Composable BoxScope.() -> Unit>()