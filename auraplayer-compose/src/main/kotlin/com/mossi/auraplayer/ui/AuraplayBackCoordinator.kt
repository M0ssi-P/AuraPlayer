package com.mossi.auraplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mossip.auraplayer.engine.AuraPlayer

/**
 * Process-wide (not per-window) coordination for the two "exclusive" video
 * presentation modes:
 *
 *  - in-app fullscreen: exactly one player may occupy the full-window
 *    overlay slot at a time (see AuraPlayerHost).
 *  - picture-in-picture: exactly one player may live in a floating PiP
 *    window at a time.
 *
 * Being a singleton (not scoped to a single AuraHostState/window) means
 * these invariants hold even if the app has multiple top-level windows.
 */
object AuraPlaybackCoordinator {

    var fullscreenPlayer by mutableStateOf<AuraPlayer?>(null)
        private set

    var pipPlayer by mutableStateOf<AuraPlayer?>(null)
        private set

    /** The AWT/Compose window currently hosting PiP, if any. Owned by AuraPiP.kt. */
    internal var pipWindowCloser: (() -> Unit)? = null

    fun requestFullscreen(player: AuraPlayer) {
        if (pipPlayer === player) exitPiP(player)
        fullscreenPlayer = player
        player.enterFullscreen()   // native setBorderlessFullscreen(true) via AuraHostState
    }

    fun exitFullscreen(player: AuraPlayer) {
        if (fullscreenPlayer === player) fullscreenPlayer = null
        player.exitFullscreen()    // native setBorderlessFullscreen(false)
    }

    fun toggleFullscreen(player: AuraPlayer) {
        if (fullscreenPlayer === player) exitFullscreen(player) else requestFullscreen(player)
    }

    fun requestPiP(player: AuraPlayer) {
        if (fullscreenPlayer === player) exitFullscreen(player)
        pipPlayer = player
    }

    /** Called by AuraPiP when its window closes (by user action or by request). */
    fun exitPiP(player: AuraPlayer) {
        if (pipPlayer === player) {
            pipPlayer = null
            pipWindowCloser?.invoke()
            pipWindowCloser = null
        }
    }

    /** Call when a player is fully disposed so it can't be left "stuck" as
     *  the fullscreen/PiP owner after it no longer exists. */
    fun onPlayerRetired(player: AuraPlayer) {
        if (fullscreenPlayer === player) fullscreenPlayer = null
        if (pipPlayer === player) exitPiP(player)
    }
}