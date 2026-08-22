package com.mossip.auraplayer.engine

object Model2 {
    init { AuraPlayerLoader.load() }
    external fun initRender(ctxPtr: Long): Boolean
    external fun createRenderContext(ctxPtr: Long): Boolean
    external fun wrapNative(d3d12DevicePtr: Long, sharedHandle: Long): Long
    external fun wrapNative(d3d12DevicePtr: Long, sharedHandle: Long, w: Int, h: Int): Long
    external fun skiaDevicePtr(ptr: Long): Long
    external fun skiaD3D12Queue(ptr: Long): Long
    external fun startProducer(ctxPtr: Long, w: Int, h: Int): LongArray?
    external fun stopProducer(ctxPtr: Long)
    external fun acquireFront(ctxPtr: Long): Int

    /** Mints a fresh NT handle to buffer [bufferIndex]'s texture, usable by a
     *  second (or third, ...) D3D12 device — e.g. a PiP window's Skiko
     *  device. Unlike the handles returned by startProducer, this can be
     *  called any number of times; each call is independent. */
    external fun mintSharedHandle(ctxPtr: Long, bufferIndex: Int): Long

    /** Asks the producer to resize its 3 buffers to w x h. Applies on the
     *  producer's own thread, not synchronously — poll getCurrentSize to
     *  know when it's actually live. */
    external fun requestResize(ctxPtr: Long, w: Int, h: Int)

    /** The buffer size actually live right now: [width, height], or [0, 0]
     *  if the producer hasn't created buffers yet. */
    private external fun getCurrentSizeNative(ctxPtr: Long, out: IntArray)

    fun getCurrentSize(ctxPtr: Long): Pair<Int, Int> {
        val out = IntArray(2)
        getCurrentSizeNative(ctxPtr, out)
        return out[0] to out[1]
    }

    external fun decorate(windowHandle: Long, aspect: Float, minW: Int)
    external fun setAspect(windowHandle: Long, aspect: Float)
    external fun setNoDrag(windowHandle: Long, noDrag: Boolean)
    external fun releaseNative(texPtr: Long)
    external fun notifyDetached(ctxPtr: Long)
}