package com.mossi.auraplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.OverlayMode
import javax.swing.SwingUtilities

/**
 * macOS still renders video as a real AWT Canvas positioned as a native
 * window layer (JAWT + enableUnderlay) — Compose never touches its pixels.
 * This is the same host.register()/applyGeometry() flow as before; nothing
 * about the underlying mechanism changes here, it's just re-homed behind
 * the VideoSurfaceStrategy split so AuraPlayerSurface stays platform-agnostic.
 *
 * NOTE: in-app fullscreen (video expanding within the Compose tree, with
 * Compose modals able to draw over it) is NOT free on this path the way it
 * is on Windows — a native Canvas is composited by the OS window server,
 * not by Skia, so a Compose overlay can't reliably sit above it without the
 * same underlay/hole coordination already used for normal placement. If you
 * need in-app fullscreen on macOS, treat it as enlarging + re-centering the
 * canvas hole to the window's content bounds (same mechanism as normal
 * layout, just full-size) rather than expecting free Compose z-order.
 */
internal object MacCanvasSurfaceStrategy : VideoSurfaceStrategy {

    @Composable
    override fun Render(
        player: AuraPlayer,
        modifier: Modifier,
        controls: @Composable BoxScope.() -> Unit,
    ) {
        val density = LocalDensity.current.density
        val host = LocalAuraHost.current
            ?: error("AuraPlayerSurface requires AuraPlayerHost at the root of your window. " +
                    "Wrap your content: AuraPlayerHost(window) { App() }")
        val entry = remember(player) { host.register(player) }
        DisposableEffect(entry) {
            entry.setAttached(true)
            onDispose { host.unregister(entry) }
        }

        val overVideoMode by player.overlayMode.collectAsState()
        val overVideo = overVideoMode == OverlayMode.OVER_VIDEO

        Box(
            modifier = modifier
                .onGloballyPositioned {
                    entry.setBounds(it.boundsInRoot())
                    val work = Runnable {
                        if (!entry.tornDown) {
                            applyGeometry(host, listOf(entry to entry.toGeom()), density) {
                                Box(Modifier.fillMaxSize()) { controls() }
                            }
                        }
                    }
                    if (SwingUtilities.isEventDispatchThread()) work.run()
                    else SwingUtilities.invokeLater(work)
                }
                .then(
                    if (overVideo) {
                        Modifier.drawBehind {
                            drawRect(Color.Black, blendMode = BlendMode.Clear)
                        }
                    } else Modifier
                )
        ) {
            if (overVideo) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Transparent)
                ) { controls() }
            }
        }
    }
}