package com.mossi.auraplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.Density
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.AuraSurfaceEntry
import com.mossip.auraplayer.engine.AuraSurfaceHost
import com.mossip.auraplayer.engine.OverlayMode
import kotlinx.coroutines.flow.distinctUntilChanged
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

class AuraHostState internal constructor(
    internal val window: ComposeWindow,
) : AuraSurfaceHost {

    internal val surfaces = mutableStateListOf<AuraSurfaceEntry>()
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
            window.layeredPane.add(entry.canvas, JLayeredPane.PALETTE_LAYER as Any)
            entry.canvas.setBounds(0, 0, 1, 1)

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
    val rectPx: Rectangle?,     // null = hidden; INTEGER px kills sub-pixel churn
    val fullscreen: Boolean,
)

private fun AuraSurfaceEntry.toGeom(): SurfaceGeom {
    val b = bounds.value as Rect?
    val r = if (!attachedState.value) null
    else if (isFullscreen.value) Rectangle(-1, -1, -1, -1)   // sentinel: whole window
    else b?.let {
        Rectangle(it.left.roundToInt(), it.top.roundToInt(),
            it.width.roundToInt(), it.height.roundToInt())
    }
    return SurfaceGeom(holeIndex, r, isFullscreen.value)
}

@Composable
fun AuraPlayerSurface(
    player: AuraPlayer,
    modifier: Modifier = Modifier,
    controls: @Composable BoxScope.() -> Unit = {}
) {
    val density = LocalDensity.current.density
    val host = LocalAuraHost.current
        ?: error("AuraPlayerSurface requires AuraPlayerHost at the root of your window. " +
                "Wrap your content: AuraPlayerHost(player, audioOnly: Boolean) { App() }")

    val entry = remember(player) { host.register(player) }
    DisposableEffect(entry) {
        entry.setAttached(true)
        onDispose { host.unregister(entry) }
    }

    val overVideo = player.overlayMode == OverlayMode.OVER_VIDEO

    Box(modifier) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .onGloballyPositioned {
                    entry.setBounds(it.boundsInRoot())

                    SwingUtilities.invokeLater {
                        val geom = entry.toGeom()
                        applyGeometry(host, listOf(entry to geom), density )
                    }
                }
                .then(if (overVideo) Modifier.drawBehind {
                    drawRect(Color.Black, blendMode = BlendMode.Clear)
                } else Modifier)
        ) {
            if (overVideo) controls()
        }
        if (!overVideo) Box(Modifier.fillMaxWidth()) { controls() }
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

        val underlay = entry.player.overlayMode == OverlayMode.OVER_VIDEO   // <- the gate

        if (r == null) {
            if (underlay) entry.player.clearUnderlay(host.windowHandle, geom.holeIndex)
            continue
        }

        val full = geom.fullscreen
        val cx = if (full) 0 else (r.x / density).roundToInt()
        val cy = if (full) 0 else (r.y / density).roundToInt()
        val cw = if (full) skikoW else (r.width / density).roundToInt()
        val ch = if (full) skikoH else (r.height / density).roundToInt()
        entry.canvas.setBounds(cx, cy, cw, ch)
        entry.player.updateSurfaceBounds(cx, cy, cw, ch)

        println("UNDERLAY? mode=${entry.player.overlayMode} video=${entry.player.videoHandle} root=${host.windowHandle}")
        if (underlay) {   // ONLY when the platform composites Compose above the video
            val hx = if (full) 0 else r.x
            val hy = if (full) 0 else r.y
            val hw = if (full) (skikoW * density).roundToInt() else r.width
            val hh = if (full) (skikoH * density).roundToInt() else r.height
            entry.player.setUnderlay(host.windowHandle, entry.player.videoHandle,
                geom.holeIndex, hx, hy, hw, hh, true)
        }
    }
}