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

internal const val DXGI_FORMAT_B8G8R8A8_UNORM = 87

/** Used only until the real decoded resolution is known (first ~1 frame of
 *  playback) or if a source never reports dwidth/dheight for some reason. */
private const val DEFAULT_W = 1280
private const val DEFAULT_H = 720

/** Sanity bounds so a malformed stream can't request an absurd allocation. */
private const val MIN_DIM = 160
private const val MAX_DIM = 3840 // 4K cap

private fun clampDim(v: Int) = v.coerceIn(MIN_DIM, MAX_DIM)

/**
 * One per AuraPlayer. Owns the native D3D11 device, ANGLE context, mpv
 * render context, and the triple-buffer producer thread. Buffer size now
 * tracks the source video's real resolution (see ensureSizeWatch) instead
 * of a fixed constant — a 4K source gets 4K buffers, a 480p source gets
 * 480p buffers, and every consumer scales that to its own display size.
 */
internal class ProducerResources(val player: AuraPlayer) {
    @Volatile var nativeReady = false
    @Volatile var producerStarted = false
    @Volatile var sizeWatchStarted = false

    /** startProducer()'s handles are usable exactly once. The first window
     *  to wire up consumes these; every later window mints fresh ones. */
    @Volatile var firstHandles: LongArray? = null
    val claimedFirstHandles = java.util.concurrent.atomic.AtomicBoolean(false)

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
}

/** One per (player, window). Wraps the producer's current 3 buffers as Skia
 *  Surfaces sized to whatever they actually are right now — re-wired
 *  whenever that changes (source resolution becomes known, or changes
 *  mid-stream, e.g. an adaptive stream switching quality). */
internal class ConsumerResources(
    val player: AuraPlayer,
    val skikoDevice: Long,
) {
    val rt = arrayOfNulls<BackendRenderTarget>(3)
    val surface = arrayOfNulls<Surface>(3)
    val d3d12Res = LongArray(3)
    var staging: Surface? = null
    @Volatile var wiredW = 0
    @Volatile var wiredH = 0

    /** Must run on the Skiko render thread for the owning window. */
    fun closeOnDrawThread() {
        for (i in 0..2) {
            surface[i]?.close(); surface[i] = null
            rt[i]?.close(); rt[i] = null
            d3d12Res[i] = 0L
        }
        wiredW = 0; wiredH = 0
    }
}

internal object WindowsVideoRegistry {
    private val producers = ConcurrentHashMap<AuraPlayer, ProducerResources>()
    private val consumers = ConcurrentHashMap<Pair<AuraPlayer, Long>, ConsumerResources>()
    private val pendingDispose = ConcurrentLinkedQueue<ConsumerResources>()

    @Volatile var shuttingDown = false
        private set

    fun producerFor(player: AuraPlayer): ProducerResources =
        producers.getOrPut(player) { ProducerResources(player) }

    fun consumerFor(player: AuraPlayer, skikoDevice: Long): ConsumerResources =
        consumers.getOrPut(player to skikoDevice) { ConsumerResources(player, skikoDevice) }

    /** MUST run before AuraPlayer.initializeRenderApi() calls mpv_initialize,
     *  or mpv gives up opening vo=libmpv before a render context exists. */
    private fun ensureProducerNative(p: ProducerResources) {
        if (p.nativeReady) return
        Model2.initRender(p.player.nativeCtx)
        Model2.createRenderContext(p.player.nativeCtx)   // before initializeRenderApi()
        p.player.initializeRenderApi()
        p.nativeReady = true
    }

    private fun ensureProducerStarted(p: ProducerResources) {
        if (p.producerStarted) return
        val handles = Model2.startProducer(p.player.nativeCtx, DEFAULT_W, DEFAULT_H)
        check(handles != null && handles.size == 3) { "AuraPlayer: startProducer failed" }
        p.producerStarted = true
        p.firstHandles = handles
    }

    /** Once, per producer: as soon as mpv reports the real resolution,
     *  request a resize to match it. The producer thread applies this
     *  asynchronously; consumers detect the change via getCurrentSize and
     *  re-wire themselves (see ensureConsumerWired) — no coordination
     *  needed between this and however many consumers exist. */
    private fun ensureSizeWatch(p: ProducerResources) {
        if (p.sizeWatchStarted) return
        p.sizeWatchStarted = true
        p.scope.launch {
            p.player.videoSize.collect { size ->
                val (w, h) = size ?: return@collect
                p.firstHandles = null   // about to become stale once the resize lands
                Model2.requestResize(p.player.nativeCtx, clampDim(w), clampDim(h))
            }
        }
    }

    /** Wraps this window's CURRENT view of the producer's buffers at
     *  whatever size is live right now. Safe to call repeatedly — it's a
     *  no-op once wired at the current size, and re-wires automatically
     *  when the live size changes. Must run on this window's Skiko render
     *  thread (same thread as `gr`'s frame draws). */
    fun ensureConsumerWired(c: ConsumerResources, p: ProducerResources, gr: DirectContext) {
        ensureProducerNative(p)
        ensureProducerStarted(p)
        ensureSizeWatch(p)

        val (liveW, liveH) = Model2.getCurrentSize(p.player.nativeCtx)
        val targetW = if (liveW > 0) liveW else DEFAULT_W
        val targetH = if (liveH > 0) liveH else DEFAULT_H
        if (c.wiredW == targetW && c.wiredH == targetH) return  // already current

        // Build the new set fully BEFORE touching the old one — swapping
        // only once everything is ready means there's never a frame where
        // consumer.surface[i] is null/stale, which is what caused the
        // visible flash/flicker on a quality switch or any other resize.
        val dev = Model2.skiaD3D12Device(c.skikoDevice)
        val newRt = arrayOfNulls<BackendRenderTarget>(3)
        val newSurface = arrayOfNulls<Surface>(3)
        val newRes = LongArray(3)
        for (i in 0..2) {
            val handle = p.firstHandles?.takeIf { p.claimedFirstHandles.compareAndSet(false, true) }
                ?.get(i)
                ?: Model2.mintSharedHandle(p.player.nativeCtx, i)
            check(handle != 0L) { "AuraPlayer: mintSharedHandle failed for buffer $i" }

            val r12 = Model2.openOnD3D12(dev, handle)
            check(r12 != 0L) { "AuraPlayer: openOnD3D12 failed for buffer $i" }
            newRes[i] = r12

            val rt = BackendRenderTarget.makeDirect3D(
                targetW, targetH, r12, DXGI_FORMAT_B8G8R8A8_UNORM, 1, 1)
            newRt[i] = rt
            newSurface[i] = Surface.makeFromBackendRenderTarget(
                gr, rt, SurfaceOrigin.TOP_LEFT, SurfaceColorFormat.BGRA_8888, ColorSpace.sRGB)
                ?: error("AuraPlayer: surface wrap failed for buffer $i")
        }

        // Swap: keep references to the old set so we can close it after.
        val oldRt = arrayOfNulls<BackendRenderTarget>(3).also { it.indices.forEach { i -> it[i] = c.rt[i] } }
        val oldSurface = arrayOfNulls<Surface>(3).also { it.indices.forEach { i -> it[i] = c.surface[i] } }
        for (i in 0..2) { c.rt[i] = newRt[i]; c.surface[i] = newSurface[i]; c.d3d12Res[i] = newRes[i] }
        c.wiredW = targetW; c.wiredH = targetH

        // Now safe to close the old set — nothing references it anymore.
        for (i in 0..2) { oldSurface[i]?.close(); oldRt[i]?.close() }
    }

    /** Call when a window stops displaying a player (Compose dispose). */
    fun retireConsumer(player: AuraPlayer, skikoDevice: Long) {
        consumers.remove(player to skikoDevice)?.let { pendingDispose.add(it) }
    }

    /** Call when the player itself is fully disposed (not just one window's
     *  view of it) — tears down every consumer plus the shared producer. */
    fun retirePlayer(player: AuraPlayer) {
        consumers.keys.filter { it.first === player }
            .forEach { retireConsumer(it.first, it.second) }
        producers.remove(player)?.let {
            it.scope.cancel()
            Model2.stopProducer(it.player.nativeCtx)
        }
    }

    /** Call once per frame per window, on that window's draw thread, before
     *  drawing — closes any consumers that were retired from other threads. */
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