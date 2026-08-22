package com.mossi.auraplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import com.mossi.auraplayer.ui.control.PipOverlay
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.Model2
import java.awt.Dimension
import javax.swing.SwingUtilities

/**
 * Opens (or focuses) a small always-on-top window showing `player`'s video.
 * Windows-only for now — see MacCanvasSurfaceStrategy's note on why PiP
 * needs a real IOSurface-sharing path on macOS instead of this.
 *
 * The PiP window is a fully independent Skiko window with its own D3D12
 * device; WindowsVideoRegistry.ensureConsumerWired mints fresh shared
 * handles for it against the SAME producer already decoding for the main
 * window (or starts that producer if this is the first view of the player
 * at all) — no second decode, no second mpv instance.
 */
object AuraPiP {

    internal var pipWindow: ComposeWindow? = null

    fun open(player: AuraPlayer, width: Int = 400, height: Int = 225) {
        if (pipWindow != null) {
            // Re-target the existing PiP window at a different player rather
            // than stacking multiple PiP windows — matches "only one PiP at
            // a time" from AuraPlaybackCoordinator.
            AuraPlaybackCoordinator.requestPiP(player)
            pipWindow?.toFront()
            return
        }

        val win = ComposeWindow().apply {
            title = "Picture in Picture"
            isAlwaysOnTop = true
            isResizable = true
            minimumSize = Dimension(160, 90)
            setSize(width, height)
            setLocationByPlatform(true)
        }
        pipWindow = win

        AuraPlaybackCoordinator.pipWindowCloser = { closeInternal() }

        win.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                AuraPlaybackCoordinator.exitPiP(player)
            }
        })

        AuraPlaybackCoordinator.requestPiP(player)
        win.setContent {
            val aspect by player.aspect.collectAsState()
            LaunchedEffect(aspect) {
                if (aspect > 0f) Model2.setAspect(win.windowHandle, aspect)
            }
            AuraPlayerHost(win, true) {
                AuraPlayerSurface(player, Modifier.fillMaxSize(), controls = {
                    PipOverlay(
                        player,
                        Modifier.fillMaxSize(),
                        onClose = { AuraPlaybackCoordinator.exitPiP(player) },
                        onExpand = { AuraPlaybackCoordinator.exitPiP(player) }
                    )
                })
            }
        }
        win.isVisible = true

        SwingUtilities.invokeLater {
            Model2.decorate(win.windowHandle, 0f, 160)
        }
    }

    fun close(player: AuraPlayer) {
        AuraPlaybackCoordinator.exitPiP(player)
    }

    private fun closeInternal() {
        pipWindow?.let { win ->
            windowContextsCleanup(win)
            win.dispose()
        }
        pipWindow = null
    }
}

@Composable
fun Modifier.pipNoDrag(handle: Long): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { Model2.setNoDrag(handle, hovered) }
    return this.hoverable(interaction)
}

/** Best-effort: drop this window's cached Skiko context so a later PiP open
 *  for a different window doesn't accidentally reuse a stale one. Internal
 *  detail of WindowsSkiaSurfaceStrategy's window->context cache. */
private fun windowContextsCleanup(window: ComposeWindow) {
    if (isWindows) runCatching { forgetWindowContext(window) }
    if (isMac) runCatching { forgetMacWindowContext(window) }
}