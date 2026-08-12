package com.mossi.auraplayer.ui

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import com.mossi.auraplayer.ui.internal.AuraUiLayer
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.AuraSurfaceEntry
import com.mossip.auraplayer.engine.AuraSurfaceHost
import java.awt.Rectangle
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import java.awt.event.HierarchyEvent
import kotlin.math.roundToInt

/**
 * Per-window state.
 *
 *  - OS-level fullscreen (setBorderlessFullscreen): shared by both platforms,
 *    just needs the native window handle and a one-owner-at-a-time lock.
 *  - Canvas registration (register/unregister, applyGeometry): macOS only.
 *    The old Windows/DComp branch that used to live here is gone — Windows
 *    video is now Skia pixels drawn straight into Compose (see
 *    WindowsSkiaSurfaceStrategy), which needs none of this machinery.
 */

class AuraHostState internal constructor(
    internal val window: ComposeWindow,
) : AuraSurfaceHost {
    internal val surfaces = mutableListOf<AuraSurfaceEntry>()
    val uiLayers = mutableMapOf<AuraSurfaceEntry, AuraUiLayer>()
    private val freeSlots = ArrayDeque<Int>().apply { (0 until 16).forEach(::add) }
    private var windowFullscreenOwner: AuraPlayer? = null

    internal var windowHandle: Long = 0L
        private set

    private fun captureWindowHandle() {
        if (windowHandle == 0L && window.isDisplayable)
            windowHandle = window.windowHandle
    }

    /* ------------------- AuraSurfaceHost: OS fullscreen, shared ----------- */

    override fun setFullscreen(player: AuraPlayer, enabled: Boolean) {
        surfaces.firstOrNull { it.player === player }?.setFullScreen(enabled)
    }

    override fun isFullscreen(player: AuraPlayer): Boolean =
        surfaces.firstOrNull { it.player === player }?.isFullscreen?.value == true

    override fun setWindowFullscreen(player: AuraPlayer, enabled: Boolean) {
        if (enabled) {
            if (windowFullscreenOwner != null && windowFullscreenOwner !== player) return
            windowFullscreenOwner = player
        } else {
            if (windowFullscreenOwner !== player) return
            windowFullscreenOwner = null
        }
        SwingUtilities.invokeLater {
            captureWindowHandle()
            if (windowHandle != 0L)
                player.setBorderlessFullscreen(windowHandle, enabled)
        }
    }

    /* ---------------------- mac canvas register/unregister ---------------- */

    internal fun register(player: AuraPlayer): AuraSurfaceEntry {
        surfaces.firstOrNull { it.player === player }?.let { return it }
        val slot = freeSlots.removeFirstOrNull()
            ?: error("AuraPlayer: max 16 simultaneous surfaces per window")
        val entry = AuraSurfaceEntry(player, slot)
        surfaces.add(entry)
        player.host = this

        SwingUtilities.invokeLater {
            captureWindowHandle()
            window.layeredPane.add(entry.canvas, Integer.valueOf(JLayeredPane.FRAME_CONTENT_LAYER - 1))
            entry.canvas.setBounds(0, 0, 640, 360)
            fun tryInit() {
                if (!entry.initialized && entry.canvas.isDisplayable) {
                    captureWindowHandle()
                    player.initialize(entry.canvas, audioOnly = false)
                    entry.initialized = true
                }
            }
            tryInit()
            if (!entry.initialized) {
                entry.canvas.addHierarchyListener { e ->
                    if (e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) tryInit()
                }
            }
        }
        return entry
    }

    internal fun unregister(entry: AuraSurfaceEntry) {
        if (!surfaces.remove(entry)) return
        entry.tornDown = true
        if (entry.player.host === this) entry.player.host = null
        freeSlots.addFirst(entry.holeIndex)
        SwingUtilities.invokeLater {
            entry.cancelResize()
            entry.player.release()
            uiLayers[entry]?.dispose()
            entry.uiInitialized = false
            if (windowHandle != 0L) entry.player.clearUnderlay(windowHandle, entry.holeIndex)
            if (window.isDisplayable) window.layeredPane.remove(entry.canvas)
        }
    }
}

internal data class SurfaceGeom(
    val holeIndex: Int,
    val rectPx: Rectangle?,
    val fullscreen: Boolean,
)

internal fun AuraSurfaceEntry.toGeom(): SurfaceGeom {
    val b = bounds.value as Rect?
    val r = if (!attachedState.value) null
    else if (isFullscreen.value) Rectangle(-1, -1, -1, -1)
    else b?.let {
        Rectangle(it.left.roundToInt(), it.top.roundToInt(),
            it.width.roundToInt(), it.height.roundToInt())
    }
    return SurfaceGeom(holeIndex, r, isFullscreen.value)
}

internal fun applyGeometry(
    host: AuraHostState,
    list: List<Pair<AuraSurfaceEntry, SurfaceGeom>>,
    density: Float,
    controls: @androidx.compose.runtime.Composable (() -> Unit),
) {
    val skikoW = host.window.contentPane.width
    val skikoH = host.window.contentPane.height

    for ((entry, geom) in list) {
        val r = geom.rectPx
        entry.canvas.isVisible = r != null
        val needsHole = entry.player.overlayMode.value ==
                com.mossip.auraplayer.engine.OverlayMode.OVER_VIDEO

        if (r == null) {
            if (needsHole) entry.player.clearUnderlay(host.windowHandle, geom.holeIndex)
            continue
        }

        val full = geom.fullscreen
        val cx = if (full) 0 else (r.x / density).roundToInt()
        val cy = if (full) 0 else (r.y / density).roundToInt()
        val cw = if (full) skikoW else (r.width / density).roundToInt()
        val ch = if (full) skikoH else (r.height / density).roundToInt()
        entry.canvas.setBounds(cx, cy, cw, ch)
        entry.onGeometryPass(cw, ch)

        if (needsHole) {
            val hx = if (full) 0 else r.x
            val hy = if (full) 0 else r.y
            val hw = if (full) (skikoW * density).roundToInt() else r.width
            val hh = if (full) (skikoH * density).roundToInt() else r.height
            entry.player.setUnderlay(host.windowHandle, entry.player.videoHandle,
                geom.holeIndex, hx, hy, hw, hh, true)
        }
    }
}