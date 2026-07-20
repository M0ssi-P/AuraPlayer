package com.mossip.auraplayer.engine

/**
 * JNI surface for the Windows DirectComposition backend.
 * Symbol names in native_render.c must match this package + object name.
 */
object AuraDComp {

    /** Create D3D11 device, DComp tree, and ANGLE context. Call once per player. */
    external fun dcompInit(ctx: Long, topLevelHwnd: Long): Boolean

    /**
     * Create mpv's GL render context on the ANGLE context and start the
     * render thread. Call AFTER mpv_initialize and AFTER the first
     * dcompSetVideoRect (so a surface exists).
     */
    external fun dcompCreateRenderContext(ctx: Long): Boolean

    /** Position/size of the video layer, PHYSICAL px relative to window client area. */
    external fun dcompSetVideoRect(ctx: Long, x: Int, y: Int, w: Int, h: Int)

    /** Hand the patched Skiko's composition swapchain (IDXGISwapChain3*) to the tree. */
    external fun dcompAttachUiSwapchain(ctx: Long, swapchainPtr: Long)

    /** Atomically publish staged changes to DWM. Batch rect+resize under one commit. */
    external fun dcompCommit(ctx: Long)

    external fun uiCreateSwapchainD3d12(ctx: Long, w: Int, h: Int): Boolean
    external fun uiResizeSwapchain(ctx: Long, w: Int, h: Int): Boolean
    external fun uiAdapterPtr(ctx: Long): Long
    external fun uiDevicePtr(ctx: Long): Long
    external fun uiQueuePtr(ctx: Long): Long

    external fun uiCreateSwapchain(ctx: Long, w: Int, h: Int): Boolean
    external fun uiTestFill(ctx: Long)
    external fun uiAcquireBackbuffer(ctx: Long): Int
    external fun uiBackbufferPtrAt(ctx: Long, index: Int): Long
    external fun uiPresent(ctx: Long)

    /** Step-1 smoke test: fills the video layer teal. Remove once video works. */
    external fun dcompDebugFill(ctx: Long)

    /** Full reverse-order teardown: render thread, mpv rctx, EGL, DComp, D3D11. */
    external fun dcompTeardown(ctx: Long)
}