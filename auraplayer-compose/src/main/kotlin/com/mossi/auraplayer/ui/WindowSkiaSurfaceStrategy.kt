package com.mossi.auraplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.Model2
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext

/**
 * Resolves the Skiko DirectContext + D3D12 device for a given window, once
 * it's actually rendering (Skiko creates its redrawer lazily on first
 * paint). See SkikoAccess for the reflection details — unchanged from the
 * original single-window implementation, just called per-window now.
 */
private class WindowSkikoContext(val gr: DirectContext, val devicePtr: Long)

private val windowContexts = java.util.concurrent.ConcurrentHashMap<ComposeWindow, WindowSkikoContext>()

/** Called when a window (e.g. a closed PiP window) goes away, so a future
 *  window reusing the same object identity slot can't pick up a stale ptr. */
internal fun forgetWindowContext(window: ComposeWindow) {
    windowContexts.remove(window)
}

private suspend fun resolveSkikoContext(window: ComposeWindow): WindowSkikoContext? {
    windowContexts[window]?.let { return it }
    repeat(50) {
        val dev = SkikoAccess.devicePtr(window) ?: 0L
        val ctxPtr = runCatching { SkikoAccess.grContextPtr(window) }.getOrNull()
        if (ctxPtr != null && dev != 0L) {
            val resolved = WindowSkikoContext(SkikoAccess.directContextFromPtr(ctxPtr), dev)
            windowContexts[window] = resolved
            return resolved
        }
        kotlinx.coroutines.delay(100)
    }
    println("AuraPlayer: could not reach Skiko's DirectContext for window — video disabled")
    return null
}

internal object WindowsSkiaSurfaceStrategy : VideoSurfaceStrategy {

    private var debugFrameCounter = 0
    private val paint = org.jetbrains.skia.Paint().apply { isAntiAlias = true }
    private val sampling = org.jetbrains.skia.FilterMipmap(
        org.jetbrains.skia.FilterMode.LINEAR,
        org.jetbrains.skia.MipmapMode.NONE)

    @Composable
    override fun Render(
        player: AuraPlayer,
        modifier: Modifier,
        controls: @Composable BoxScope.() -> Unit,
    ) {
        val window = LocalComposeWindow.current
            ?: error("AuraPlayerSurface (Windows) requires a ComposeWindow — " +
                    "wrap your content in AuraPlayerHost(window) { ... }")

        // enterFullscreen()/exitFullscreen() (used by AuraPlaybackCoordinator
        // for the native setBorderlessFullscreen call) route through
        // player.host. The mac canvas path sets this in
        // AuraHostState.register(); the Windows Skia path has no equivalent
        // registration step, so wire it here instead.
        val hostState = LocalAuraHost.current
        DisposableEffect(player, hostState) {
            if (hostState != null) player.host = hostState
            onDispose { if (player.host === hostState) player.host = null }
        }

        var skiko by remember { mutableStateOf<WindowSkikoContext?>(null) }
        LaunchedEffect(window) { skiko = resolveSkikoContext(window) }
        val gr = skiko?.gr ?: return
        val devicePtr = skiko!!.devicePtr

        DisposableEffect(player, devicePtr) {
            onDispose { WindowsVideoRegistry.retireConsumer(player, devicePtr) }
        }

        var tick by remember { mutableStateOf(0) }
        LaunchedEffect(player) { while (true) { withFrameNanos { }; tick++ } }

        // Cache of the last successfully drawn frame for THIS composable
        // instance. If a given frame hits any transient issue (wiring not
        // ready yet, a size read racing a resize, etc.) we redraw this
        // instead of leaving the Canvas blank — a blank frame shows
        // whatever's behind it (the window's default background) for one
        // frame, which is exactly the white/black flash. Redrawing the last
        // good frame is visually seamless even when something upstream
        // briefly stalls.
        val lastGood = remember { LastGoodFrame() }

        Box(modifier.background(Color.Black)) {
            Canvas(Modifier.fillMaxSize()) {
                tick // recomposition tick

                fun drawFrame(image: org.jetbrains.skia.Image, w: Int, h: Int) {
                    drawIntoCanvas { cv ->
                        val nc = cv.nativeCanvas
                        val srcAspect = w.toFloat() / h
                        val boxAspect = size.width / size.height
                        val scale: Float
                        val offsetX: Float
                        val offsetY: Float
                        if (srcAspect > boxAspect) {
                            scale = size.width / w.toFloat()
                            offsetX = 0f
                            offsetY = (size.height - h * scale) / 2f
                        } else {
                            scale = size.height / h.toFloat()
                            offsetX = (size.width - w * scale) / 2f
                            offsetY = 0f
                        }
                        nc.save()
                        nc.translate(offsetX, offsetY)
                        nc.scale(scale, scale)
                        nc.drawImageRect(
                            image,
                            org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
                            org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
                            sampling, paint, true,
                        )
                        nc.restore()
                    }
                }

                if (WindowsVideoRegistry.shuttingDown) {
                    lastGood.draw(::drawFrame); return@Canvas
                }
                WindowsVideoRegistry.drainDisposals()

                val producer = WindowsVideoRegistry.producerFor(player)
                val consumer = WindowsVideoRegistry.consumerFor(player, devicePtr)
                val wired = runCatching { WindowsVideoRegistry.ensureConsumerWired(consumer, producer, gr) }
                    .onFailure {
                        if (!consumer.warned) {
                            consumer.warned = true
                            println("AuraPlayer: wiring failed: $it")
                        }
                    }
                    .isSuccess

                val i = Model2.acquireFront(player.nativeCtx)
                val s = if (wired) consumer.surface.getOrNull(i) else null

                debugFrameCounter++
                if (debugFrameCounter % 30 == 0) {
//                    println("AuraPlayer DEBUG: tick=$tick wired=$wired i=$i sNull=${s == null} wiredWH=${consumer.wiredW}x${consumer.wiredH}")
                }

                if (s == null || consumer.wiredW == 0 || consumer.wiredH == 0) {
                    lastGood.draw(::drawFrame)   // hold last frame instead of blanking
                    return@Canvas
                }

                s.notifyContentWillChange(ContentChangeMode.RETAIN)
                val img = s.makeImageSnapshot()
                lastGood.update(img, consumer.wiredW, consumer.wiredH)
                drawFrame(img, consumer.wiredW, consumer.wiredH)
            }
            controls()
        }
    }
}

/** Holds the last frame this composable instance successfully drew, so a
 *  transient miss on any given frame can redraw it instead of leaving the
 *  Canvas blank for that frame. */
private class LastGoodFrame {
    private var image: org.jetbrains.skia.Image? = null
    private var w = 0
    private var h = 0

    fun update(img: org.jetbrains.skia.Image, width: Int, height: Int) {
        val old = image
        image = img; w = width; h = height
        if (old !== img) runCatching { old?.close() }
    }

    fun draw(drawFrame: (org.jetbrains.skia.Image, Int, Int) -> Unit) {
        image?.let { drawFrame(it, w, h) }
        // else: nothing has ever drawn successfully yet (very first frame(s)
        // before wiring completes) — background color shows, which is
        // expected and only happens once at startup, not repeatedly.
    }
}