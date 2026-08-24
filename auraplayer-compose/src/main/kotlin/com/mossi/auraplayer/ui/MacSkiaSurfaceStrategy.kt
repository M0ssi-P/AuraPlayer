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
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect.Companion.makeWH

private class MacSkikoContext(val gr: DirectContext, val devicePtr: Long)

private val macWindowContexts = java.util.concurrent.ConcurrentHashMap<ComposeWindow, MacSkikoContext>()

internal fun forgetMacWindowContext(window: ComposeWindow) {
    macWindowContexts.remove(window)
}

private suspend fun resolveMacSkikoContext(window: ComposeWindow): MacSkikoContext? {
    macWindowContexts[window]?.let { return it }
    repeat(50) {
        val dev = SkikoAccess.devicePtr(window) ?: 0L
        val ctxPtr = runCatching { SkikoAccess.grContextPtr(window) }.getOrNull()
        if (ctxPtr != null && dev != 0L) {
            val resolved = MacSkikoContext(SkikoAccess.directContextFromPtr(ctxPtr), dev)
            macWindowContexts[window] = resolved
            return resolved
        }
        kotlinx.coroutines.delay(100)
    }
    println("AuraPlayer: could not reach Skiko's DirectContext for window — video disabled")
    return null
}

internal object MacSkiaSurfaceStrategy : VideoSurfaceStrategy {

    private var debugFrameCounter = 0
    private val paint = Paint().apply { isAntiAlias = true }
    private val sampling = FilterMipmap(
        FilterMode.LINEAR,
        MipmapMode.NONE
    )

    @Composable
    override fun Render(
        player: AuraPlayer,
        modifier: Modifier,
        controls: @Composable BoxScope.() -> Unit,
    ) {
        val window = LocalComposeWindow.current
            ?: error("AuraPlayerSurface (macOS) requires a ComposeWindow — " +
                    "wrap your content in AuraPlayerHost(window) { ... }")

        // Fullscreen wiring is orthogonal to which surface strategy draws
        // the pixels — same block as Windows, needed here too.
        val hostState = LocalAuraHost.current
        DisposableEffect(player, hostState) {
            if (hostState != null) player.host = hostState
            onDispose { if (player.host === hostState) player.host = null }
        }

        var skiko by remember { mutableStateOf<MacSkikoContext?>(null) }
        LaunchedEffect(window) { skiko = resolveMacSkikoContext(window) }
        val gr = skiko?.gr ?: return
        val devicePtr = skiko!!.devicePtr

        DisposableEffect(player, devicePtr) {
            onDispose { MacVideoRegistry.retireConsumer(player, devicePtr) }
        }

        var tick by remember { mutableStateOf(0) }
        LaunchedEffect(player) { while (true) { withFrameNanos { }; tick++ } }

        val lastGood = remember { LastGoodFrameMac() }

        Box(modifier.background(Color.Black)) {
            Canvas(Modifier.fillMaxSize()) {
                tick

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
                            makeWH(w.toFloat(), h.toFloat()),
                            makeWH(w.toFloat(), h.toFloat()),
                            sampling, paint, true,
                        )
                        nc.restore()
                    }
                }

                if (MacVideoRegistry.shuttingDown) {
                    lastGood.draw(::drawFrame); return@Canvas
                }
                MacVideoRegistry.drainDisposals()

                val producer = MacVideoRegistry.producerFor(player)
                val consumer = MacVideoRegistry.consumerFor(player, devicePtr)
                val wired = runCatching { MacVideoRegistry.ensureConsumerWired(consumer, producer, gr) }
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
//                    println("AuraPlayer DEBUG(mac): tick=$tick wired=$wired i=$i sNull=${s == null} wiredWH=${consumer.wiredW}x${consumer.wiredH}")
                }

                if (s == null || consumer.wiredW == 0 || consumer.wiredH == 0) {
                    lastGood.draw(::drawFrame)
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

private class LastGoodFrameMac {
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
    }
}