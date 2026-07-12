package com.mossi.auraplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.AuraSurfaceEntry
import com.mossip.auraplayer.engine.AuraSurfaceHost
import com.mossip.auraplayer.engine.OverlayMode
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

class AuraHostState internal constructor(
    internal val window: ComposeWindow,
) : AuraSurfaceHost {
    internal val surfaces = mutableListOf<AuraSurfaceEntry>()
    private val freeSlots = ArrayDeque<Int>().apply { (0 until 16).forEach(::add) }
    private var windowFullscreenOwner: AuraPlayer? = null

    internal var windowHandle: Long = 0L
        private set

    private fun captureWindowHandle() {
        if (windowHandle == 0L && window.isDisplayable)
            windowHandle = window.windowHandle
    }

    /* ------------------- AuraSurfaceHost implementation ------------------- */

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

    /* ---------------------- register / unregister ------------------------- */

    internal fun register(player: AuraPlayer): AuraSurfaceEntry {
        surfaces.firstOrNull { it.player === player }?.let { return it }
        val slot = freeSlots.removeFirstOrNull()
            ?: error("AuraPlayer: max 16 simultaneous surfaces per window")
        val entry = AuraSurfaceEntry(player, slot)
        surfaces.add(entry)
        player.host = this
        SwingUtilities.invokeLater {
            captureWindowHandle()
            val layer: Any = if (isMac)
                Integer.valueOf(JLayeredPane.FRAME_CONTENT_LAYER - 1)
            else
                JLayeredPane.PALETTE_LAYER
            window.layeredPane.add(entry.canvas, layer)
            /* [FIX-SIZE] give mpv a sane surface size at init; the real
             * bounds arrive with the first applyGeometry pass. 1x1 here
             * produced a 1x1 CAMetalLayer/swapchain on macOS. */
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
                    if (e.changeFlags and
                        HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) tryInit()
                }
            }
        }
        return entry
    }

    internal fun unregister(entry: AuraSurfaceEntry) {
        if (!surfaces.remove(entry)) return
        if (entry.player.host === this)
            entry.player.host = null
        freeSlots.addFirst(entry.holeIndex)
        SwingUtilities.invokeLater {
            entry.player.release()
            if (windowHandle != 0L)
                entry.player.clearUnderlay(windowHandle, entry.holeIndex)
            if (window.isDisplayable)
                window.layeredPane.remove(entry.canvas)
        }
    }
}

private data class SurfaceGeom(
    val holeIndex: Int,
    val rectPx: Rectangle?,
    val fullscreen: Boolean,
)

private fun AuraSurfaceEntry.toGeom(): SurfaceGeom {
    val b = bounds.value as Rect?
    val r = if (!attachedState.value) null
    else if (isFullscreen.value) Rectangle(-1, -1, -1, -1)
    else b?.let {
        Rectangle(it.left.roundToInt(), it.top.roundToInt(),
            it.width.roundToInt(), it.height.roundToInt())
    }
    return SurfaceGeom(holeIndex, r, isFullscreen.value)
}

private val isMac = System.getProperty("os.name").lowercase().startsWith("mac")
private val isWindows = System.getProperty("os.name").lowercase().startsWith("win")

@Composable
fun AuraPlayerSurface(
    player: AuraPlayer,
    modifier: Modifier = Modifier,
    controls: @Composable BoxScope.() -> Unit = {}
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

    Box(modifier) {
        Box(
            Modifier
                /* [FIX-MEASURE] the video area must be the FULL surface rect.
                 * The previous align(Center).fillMaxWidth() Box had content-
                 * sized HEIGHT, so bounds reported the controls bar's strip,
                 * and the canvas/layer/hole were sized to the bar. */
                .matchParentSize()
                .onGloballyPositioned {
                    entry.setBounds(it.boundsInRoot())
                    SwingUtilities.invokeLater {
                        applyGeometry(host, listOf(entry to entry.toGeom()), density)
                    }
                }
                /* [FIX-CLEAR] the Clear rect is the macOS alpha mechanism only.
                 * On Windows the hole is SetWindowRgn; Clear there just erases
                 * Compose's own pixels (including your controls). */
                .then(if (overVideo && isMac) Modifier.drawBehind {
                    drawRect(Color.Black, blendMode = BlendMode.Clear)
                } else Modifier)
        )
        if (overVideo) {
            Box(Modifier.matchParentSize()) { controls() }   // over the video
        } else {
            Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) { controls() }
        }
    }
}

internal val LocalAuraHost = staticCompositionLocalOf<AuraHostState?> { null }

@Composable
fun AuraPlayerHost(
    window: ComposeWindow,
    content: @Composable () -> Unit,
) {
    val host = remember(window) { AuraHostState(window) }
    CompositionLocalProvider(LocalAuraHost provides host) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

private fun applyGeometry(
    host: AuraHostState,
    list: List<Pair<AuraSurfaceEntry, SurfaceGeom>>,
    density: Float,
) {
    val skikoW = host.window.contentPane.width
    val skikoH = host.window.contentPane.height

    for ((entry, geom) in list) {
        val r = geom.rectPx
        entry.canvas.isVisible = r != null
        /* [FIX-GATE] the region-cut hole is a WINDOWS mechanism; on macOS the
         * underlay is layer z-order set once at init (enableUnderlay). */
        val needsHole = isWindows && entry.player.overlayMode.value == OverlayMode.OVER_VIDEO

        if (r == null) {
            if (needsHole) entry.player.clearUnderlay(host.windowHandle, geom.holeIndex)
            continue
        }
        val full = geom.fullscreen
        val cx = if (full) 0 else (r.x / density).roundToInt()
        val cy = if (full) 0 else (r.y / density).roundToInt()
        val cw = if (full) skikoW else (r.width / density).roundToInt()
        val ch = if (full) skikoH else (r.height / density).roundToInt()
        println("GEOM canvas=($cx,$cy ${cw}x$ch)")
        entry.canvas.setBounds(cx, cy, cw, ch)
        entry.player.updateSurfaceBounds(cx, cy, cw, ch)

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