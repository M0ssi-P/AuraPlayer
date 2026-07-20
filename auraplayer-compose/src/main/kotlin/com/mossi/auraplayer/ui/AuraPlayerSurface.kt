package com.mossi.auraplayer.ui

import androidx.compose.foundation.background
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
import com.mossi.auraplayer.ui.internal.AuraUiLayer
import com.mossip.auraplayer.engine.AuraDComp
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.AuraSurfaceEntry
import com.mossip.auraplayer.engine.AuraSurfaceHost
import com.mossip.auraplayer.engine.OverlayMode
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/* [DCOMP] Backend selector. Region = your existing SetWindowRgn path.
 * Flip the default (or read a system property) when DComp is ready. */
enum class WinVideoBackend { REGION, DCOMP }
var auraWindowsBackend: WinVideoBackend = WinVideoBackend.DCOMP

class AuraHostState internal constructor(
    internal val window: ComposeWindow,
) : AuraSurfaceHost {
    internal val surfaces = mutableListOf<AuraSurfaceEntry>()
    val uiLayers = mutableMapOf<AuraSurfaceEntry, AuraUiLayer>()
    internal val lastUiSize = mutableMapOf<AuraSurfaceEntry, Pair<Int, Int>>()
    private val freeSlots = ArrayDeque<Int>().apply { (0 until 16).forEach(::add) }
    private var windowFullscreenOwner: AuraPlayer? = null
    internal var dcompOwner: AuraPlayer? = null
    private var inputHook: java.awt.event.AWTEventListener? = null

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
        val wantsDComp = isWindows && auraWindowsBackend == WinVideoBackend.DCOMP

        SwingUtilities.invokeLater {
            captureWindowHandle()

            if (wantsDComp && windowHandle != 0L && dcompOwner == null) {
                if (AuraDComp.dcompInit(player.nativeCtx, windowHandle)) {
                    dcompOwner = player
                    entry.usesDComp = true
                    val ui = AuraUiLayer(
                        nativeCtx = entry.player.nativeCtx,
                        acquireBackbuffer = {
                            if (entry.tornDown) -1
                            else AuraDComp.uiAcquireBackbuffer(entry.player.nativeCtx)
                        },
                        backbufferAt = { idx ->
                            if (entry.tornDown) 0L
                            else AuraDComp.uiBackbufferPtrAt(entry.player.nativeCtx, idx)
                        },
                        present = { if (!entry.tornDown) AuraDComp.uiPresent(it) },
                        invalidate = {
                            SwingUtilities.invokeLater {
                                if (!entry.tornDown) uiLayers[entry]?.renderIfNeeded(System.nanoTime())
                            }
                        },
                    )
                    uiLayers[entry] = ui
                    installInputHook()
                    player.initializeRenderApi()
                    entry.initialized = true
                    println("AuraPlayer: DComp backend active (slot ${entry.holeIndex})")
                } else {
                    println("AuraPlayer: DComp init failed — falling back to REGION")
                }
            } else if (wantsDComp && dcompOwner != null) {
                println("AuraPlayer: DComp already owned by another player " +
                        "in this window — falling back to REGION for this surface")
            }

            if (!entry.usesDComp) {
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
        }
        return entry
    }

    internal fun unregister(entry: AuraSurfaceEntry) {
        if (!surfaces.remove(entry)) return
        entry.tornDown = true
        if (entry.player.host === this)
            entry.player.host = null
        freeSlots.addFirst(entry.holeIndex)
        SwingUtilities.invokeLater {
            if (entry.usesDComp) {
                uiLayers.remove(entry)
                AuraDComp.dcompTeardown(entry.player.nativeCtx)
                if (dcompOwner === entry.player) dcompOwner = null
                entry.player.release()
                if (uiLayers.isEmpty()) {
                    inputHook?.let { java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
                    inputHook = null
                }
                entry.uiInitialized = false

            } else {
                entry.player.release()
                uiLayers[entry]?.dispose()
                entry.uiInitialized = false
                if (windowHandle != 0L)
                    entry.player.clearUnderlay(windowHandle, entry.holeIndex)
                if (window.isDisplayable)
                    window.layeredPane.remove(entry.canvas)
            }
        }
    }

    private fun installInputHook() {
        if (inputHook != null) return
        val hook = java.awt.event.AWTEventListener { raw ->
            val e = raw as? java.awt.event.MouseEvent ?: return@AWTEventListener
            val src = e.component ?: return@AWTEventListener
            if (SwingUtilities.getWindowAncestor(src) !== window && src !== window) return@AWTEventListener
            val contentLoc = try {
                window.contentPane.locationOnScreen
            } catch (_: Exception) { return@AWTEventListener }   // window not showing
            for ((entry, ui) in uiLayers) {
                val r = entry.uiVideoRect ?: continue
                ui.onMouse(e, contentLoc.x + r.x, contentLoc.y + r.y)
            }
        }
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
            hook,
            java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK
        )
        inputHook = hook
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

    Box(
        modifier = modifier
            .onGloballyPositioned {
                entry.setBounds(it.boundsInRoot())
                SwingUtilities.invokeLater {
                    if (!entry.tornDown) {
                        applyGeometry(host, listOf(entry to entry.toGeom()), density, {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                            ) {
                                controls()
                            }
                        })
                    }
                }
            }
            .then(
                if (overVideo && (isMac || entry.usesDComp)) {
                    Modifier.drawBehind {
                        // Clears the canvas background so the DComp swapchain is visible beneath
                        drawRect(Color.Black, blendMode = BlendMode.Clear)
                    }
                } else Modifier
            )
    ) {
        // 2. Controls are explicitly placed at the top of the declaration layout stack
        if (overVideo && isMac) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Transparent) // Defend against implicit opaque styling
            ) {
                controls()
            }
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
    controls: @Composable (() -> Unit)
) {
    val skikoW = host.window.contentPane.width
    val skikoH = host.window.contentPane.height

    for ((entry, geom) in list) {
        val r = geom.rectPx

        /* ============================ [DCOMP] ============================ */
        if (entry.usesDComp) {
            if (entry.tornDown) continue
            if (r == null) {
                /* detached: park the video visual offscreen-sized-zero;
                 * cheap and avoids adding a native hide call for v1 */
                AuraDComp.dcompSetVideoRect(entry.player.nativeCtx, 0, 0, 1, 1)
                AuraDComp.dcompDebugFill(entry.player.nativeCtx)
                AuraDComp.dcompCommit(entry.player.nativeCtx)
                continue
            }
            val full = geom.fullscreen
            /* boundsInRoot is PHYSICAL px (Compose px == device px on
             * desktop) and DComp offsets are physical px in the client
             * area — pass through, do NOT divide by density like the
             * canvas path does. Fullscreen: cover the whole client area. */
            val vx = if (full) 0 else (r.x * density).roundToInt()
            val vy = if (full) 0 else (r.y * density).roundToInt()
            val vw = if (full) (skikoW * density).roundToInt() else (r.width * density).roundToInt()
            val vh = if (full) (skikoH * density).roundToInt() else (r.height * density).roundToInt()

            println("GEOM dcomp scaled=($vx,$vy ${vw}x$vh)")
            entry.uiVideoRect = Rectangle(vx, vy, vw, vh)
            AuraDComp.dcompSetVideoRect(entry.player.nativeCtx, vx, vy, vw, vh)
            if (entry.usesDComp && vw > 0 && vh > 0) {
                val ui = host.uiLayers[entry] ?: return
                if (!entry.uiInitialized) {
                    entry.uiInitialized = true
                    if (AuraDComp.uiCreateSwapchainD3d12(entry.player.nativeCtx, vw, vh)) {
                        ui.init(vw, vh, density) { controls.invoke() }
                        ui.renderIfNeeded(System.nanoTime())
                    } else {
                        println("AuraPlayer: UI swapchain creation failed — no overlay")
                    }
                } else {
                    if (host.lastUiSize[entry] != (vw to vh)) {
                        ui.resize(vw, vh)
                        host.lastUiSize[entry] = vw to vh
                    }
                    ui.renderIfNeeded(System.nanoTime())
                }
            }
            AuraDComp.dcompCommit(entry.player.nativeCtx)
//            if (entry.usesDComp && vw > 0 && vh > 0) {
//                val ui = host.uiLayers[entry] ?: return
//                if (!entry.uiInitialized) {
//                    entry.uiInitialized = true
//                    // 1. swapchain FIRST (creates c->ui_sc; needs device+queue+size)
//                    AuraDComp.uiCreateSwapchainD3d12(entry.player.nativeCtx, d3d12Device, queue, w, h)
//                    // 2. THEN the Compose layer (its backbuffer getter reads c->ui_sc)
//                    ui.init(vw, vh, density) { YourControls() }
//                } else {
//                    ui.resize(vw, vh)
//                }
//            }
            /* render context needs mpv + a surface; surface exists after the
             * first rect above. One-shot latch on the entry. */
            if (!entry.dcompRenderStarted) {
                entry.dcompRenderStarted =
                    AuraDComp.dcompCreateRenderContext(entry.player.nativeCtx)
            }
            return
        }

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