package com.mossi.auraplayer.ui

import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.Model2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Same defaults/clamping as WindowsVideoRegistry — kept duplicated rather
 *  than shared so each platform file can be read/modified independently. */
private const val DEFAULT_W = 1280
private const val DEFAULT_H = 720
private const val MIN_DIM = 160
private const val MAX_DIM = 3840

private fun clampDimMac(v: Int) = v.coerceIn(MIN_DIM, MAX_DIM)

/** One per AuraPlayer. Owns the CGL context, mpv render context, and the
 *  triple-buffer producer thread (native side: m2_mac_producer). */
internal class MacProducerResources(val player: AuraPlayer) {
    @Volatile var nativeReady = false
    @Volatile var producerStarted = false
    @Volatile var sizeWatchStarted = false

    /** startProducer()'s handles (IOSurface ptrs) are usable exactly once —
     *  the first window to wire up consumes these; every later window mints
     *  fresh wraps via mintSharedHandle. */
    @Volatile var firstHandles: LongArray? = null
    val claimedFirstHandles = java.util.concurrent.atomic.AtomicBoolean(false)

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
}

/** One per (player, window). Wraps the producer's current 3 IOSurface-backed
 *  buffers as Skia Surfaces on THIS window's Metal device. */
internal class MacConsumerResources(
    val player: AuraPlayer,
    val skikoDevice: Long,
) {
    val rt = arrayOfNulls<BackendRenderTarget>(3)
    val surface = arrayOfNulls<Surface>(3)
    val mtlTex = LongArray(3) // retained id<MTLTexture> ptrs from wrapNative
    @Volatile var wiredW = 0
    @Volatile var wiredH = 0

    @Volatile var warned = false

    /** Must run on the Skiko render thread for the owning window. */
    fun closeOnDrawThread() {
        for (i in 0..2) {
            surface[i]?.close(); surface[i] = null
            rt[i]?.close(); rt[i] = null
            mtlTex[i] = 0L
        }
        wiredW = 0; wiredH = 0
    }
}

internal object MacVideoRegistry {
    private val producers = ConcurrentHashMap<AuraPlayer, MacProducerResources>()
    private val consumers = ConcurrentHashMap<Pair<AuraPlayer, Long>, MacConsumerResources>()
    private val pendingDispose = ConcurrentLinkedQueue<MacConsumerResources>()

    @Volatile var shuttingDown = false
        private set

    fun producerFor(player: AuraPlayer): MacProducerResources =
        producers.getOrPut(player) { MacProducerResources(player) }

    fun consumerFor(player: AuraPlayer, skikoDevice: Long): MacConsumerResources =
        consumers.getOrPut(player to skikoDevice) { MacConsumerResources(player, skikoDevice) }

    /** MUST run before mpv_initialize, same reasoning as Windows. On mac
     *  Model2.initRender is a no-op (the CGL context is made lazily inside
     *  the producer thread) — TODO/verify: confirm initializeRenderApi()
     *  doesn't itself require a live mpv render context at call time. */
    private fun ensureProducerNative(p: MacProducerResources) {
        if (p.nativeReady) return
        require(!p.player.isAudioOnly) {
            "AuraPlayer: this player was initialized audio-only and has no video " +
                    "pipeline — don't compose AuraPlayerSurface with it."
        }
        Model2.initRender(p.player.nativeCtx)
        p.player.initializeRenderApi()
        p.nativeReady = true
    }

    private fun ensureProducerStarted(p: MacProducerResources) {
        if (p.producerStarted) return
        val handles = Model2.startProducer(p.player.nativeCtx, DEFAULT_W, DEFAULT_H)
        check(handles != null && handles.size == 3) { "AuraPlayer: startProducer failed" }
        p.producerStarted = true
        p.firstHandles = handles
    }

    /** Same pattern as Windows: watch for the real source resolution, then
     *  requestResize. Producer thread applies it async; consumers notice via
     *  getCurrentSize and re-wire (see ensureConsumerWired). */
    private fun ensureSizeWatch(p: MacProducerResources) {
        if (p.sizeWatchStarted) return
        p.sizeWatchStarted = true
        p.scope.launch {
            p.player.videoSize.collect { size ->
                val (w, h) = size ?: return@collect
                p.firstHandles = null
                Model2.requestResize(p.player.nativeCtx, clampDimMac(w), clampDimMac(h))
            }
        }
    }

    /** Must run on this window's Skiko render thread. */
    fun ensureConsumerWired(c: MacConsumerResources, p: MacProducerResources, gr: DirectContext) {
        ensureProducerNative(p)
        ensureProducerStarted(p)
        ensureSizeWatch(p)

        val (liveW, liveH) = Model2.getCurrentSize(p.player.nativeCtx)
        val targetW = if (liveW > 0) liveW else DEFAULT_W
        val targetH = if (liveH > 0) liveH else DEFAULT_H
        if (c.wiredW == targetW && c.wiredH == targetH) return

        // Build fully before swapping — same reasoning as Windows: avoids a
        // frame where consumer.surface[i] is null/stale mid-resize.
        val dev = Model2.skiaDevicePtr(c.skikoDevice)
        val newRt = arrayOfNulls<BackendRenderTarget>(3)
        val newSurface = arrayOfNulls<Surface>(3)
        val newTex = LongArray(3)
        for (i in 0..2) {
            val handle = p.firstHandles?.takeIf { p.claimedFirstHandles.compareAndSet(false, true) }
                ?.get(i)
                ?: Model2.mintSharedHandle(p.player.nativeCtx, i)
            check(handle != 0L) { "AuraPlayer: mintSharedHandle failed for buffer $i" }

            val tex = Model2.wrapNative(handle, dev, targetW, targetH)
            check(tex != 0L) { "AuraPlayer: wrapNative (Metal) failed for buffer $i" }
            newTex[i] = tex

            // TODO/verify: TOP_LEFT matches mpv's FLIP_Y=1 in m2_mac_producer.
            // If the first real frame renders upside-down or mirrored, this
            // is the value to flip — nothing else in this function.
            val rt = BackendRenderTarget.makeMetal(targetW, targetH, tex)
            newRt[i] = rt
            newSurface[i] = Surface.makeFromBackendRenderTarget(
                gr, rt, SurfaceOrigin.TOP_LEFT, SurfaceColorFormat.BGRA_8888, ColorSpace.sRGB)
                ?: error("AuraPlayer: surface wrap failed for buffer $i")
        }

        val oldRt = arrayOfNulls<BackendRenderTarget>(3).also { it.indices.forEach { i -> it[i] = c.rt[i] } }
        val oldSurface = arrayOfNulls<Surface>(3).also { it.indices.forEach { i -> it[i] = c.surface[i] } }
        for (i in 0..2) { c.rt[i] = newRt[i]; c.surface[i] = newSurface[i]; c.mtlTex[i] = newTex[i] }
        c.wiredW = targetW; c.wiredH = targetH

        for (i in 0..2) { oldSurface[i]?.close(); oldRt[i]?.close() }
    }

    fun retireConsumer(player: AuraPlayer, skikoDevice: Long) {
        consumers.remove(player to skikoDevice)?.let { pendingDispose.add(it) }
    }

    fun retirePlayer(player: AuraPlayer) {
        consumers.keys.filter { it.first === player }
            .forEach { retireConsumer(it.first, it.second) }
        producers.remove(player)?.let {
            it.scope.cancel()
            Model2.stopProducer(it.player.nativeCtx)
        }
    }

    fun drainDisposals() {
        while (true) {
            val c = pendingDispose.poll() ?: return
            runCatching { c.closeOnDrawThread() }
                .onFailure { println("AuraPlayer: consumer dispose failed: $it") }
        }
    }

    fun shutdown() {
        shuttingDown = true
        producers.values.forEach { runCatching { it.scope.cancel() } }
        producers.values.forEach { runCatching { Model2.stopProducer(it.player.nativeCtx) } }
        producers.values.forEach { runCatching { it.player.release() } }
        consumers.values.forEach { c -> runCatching {
            for (i in 0..2) { c.surface[i]?.close(); c.rt[i]?.close() }
        } }
        producers.clear(); consumers.clear(); pendingDispose.clear()
    }
}