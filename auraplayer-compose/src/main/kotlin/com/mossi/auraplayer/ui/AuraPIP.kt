package com.mossi.auraplayer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.mossip.auraplayer.engine.AuraPlayer
import java.awt.Dimension

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

    private var pipWindow: ComposeWindow? = null

    fun open(player: AuraPlayer, width: Int = 400, height: Int = 225) {
        check(isWindows) { "AuraPiP.open: Windows only for now" }

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

        win.setContent {
            AuraPlayerHost(win) {
                AuraPlayerSurface(player, Modifier.fillMaxSize())
            }
        }

        AuraPlaybackCoordinator.requestPiP(player)
        win.isVisible = true
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

/** Best-effort: drop this window's cached Skiko context so a later PiP open
 *  for a different window doesn't accidentally reuse a stale one. Internal
 *  detail of WindowsSkiaSurfaceStrategy's window->context cache. */
private fun windowContextsCleanup(window: ComposeWindow) {
    runCatching { forgetWindowContext(window) }
}