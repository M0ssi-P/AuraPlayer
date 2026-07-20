package com.mossi.auraplayer.ui.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.mossip.auraplayer.engine.AuraDComp
import org.jetbrains.skia.*
import org.jetbrains.skiko.MainUIDispatcher
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext


@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class AuraUiLayer(
    private val nativeCtx: Long,                      // AuraCtx*
    private val acquireBackbuffer: () -> Int,         // fence-wait + return current index, or -1
    private val backbufferAt: (Int) -> Long,          // ID3D12Resource* for index
    private val present: (ctx: Long) -> Unit,                  // Present + Signal + Commit
    private val invalidate: () -> Unit,
) {
    private var scene: ComposeScene? = null
    private var context: DirectContext? = null
    private var width = 0
    private var height = 0
    private var density = 1f

    private val frameRequested = AtomicBoolean(false)
    @Volatile
    private var disposed = false
    private var surfaces = arrayOfNulls<Surface>(2)
    private var targets = arrayOfNulls<BackendRenderTarget>(2)

    /** Build the scene once. Call on the render thread. */
    fun init(w: Int, h: Int, density: Float, content: @Composable () -> Unit) {
        this.density = density
        val s = CanvasLayersComposeScene(
            density = Density(density),
            coroutineContext = MainUIDispatcher,
            invalidate = {
                // Compose says "I need to redraw." Coalesce: only schedule if
                // not already pending. This is what makes it idle-cheap.
                if (frameRequested.compareAndSet(false, true)) invalidate()
            },
        )
        s.setContent(content)
        scene = s
        resize(w, h)
    }

    private fun ensureContext(): DirectContext? {
        context?.let { return it }
        val a = AuraDComp.uiAdapterPtr(nativeCtx)
        val d = AuraDComp.uiDevicePtr(nativeCtx)
        val q = AuraDComp.uiQueuePtr(nativeCtx)
        if (a == 0L || d == 0L || q == 0L) return null
        return DirectContext.makeDirect3D(a, d, q).also { context = it }
    }

    private fun closeSurfaces() {
        for (i in surfaces.indices) { surfaces[i]?.close(); surfaces[i] = null }
        for (i in targets.indices)  { targets[i]?.close();  targets[i] = null }
    }

    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == width && h == height && surfaces[0] != null) return
        width = w; height = h
        scene?.size = IntSize(w, h)

        // Order matters: release every Skia reference to the old buffers,
        // force the context to actually drop them, THEN resize natively.
        closeSurfaces()
        context?.apply { flush(); submit(true) }   // syncCpu = wait until GPU-side refs are gone

        if (!AuraDComp.uiResizeSwapchain(nativeCtx, w, h)) {
            println("AuraPlayer: UI swapchain resize failed")
        }
        frameRequested.set(true)
        invalidate()
    }

    fun renderIfNeeded(nowNanos: Long): Boolean {
        if (disposed) return false
        if (!frameRequested.compareAndSet(true, false)) return false
        val sc = scene ?: return false
        val ctx = ensureContext() ?: run { frameRequested.set(true); return false }

        val idx = acquireBackbuffer()
        if (idx < 0) return false
        val surf = surfaces[idx] ?: run {
            val ptr = backbufferAt(idx)
            if (ptr == 0L) return false
            val rt = BackendRenderTarget.makeDirect3D(width, height, ptr, 87, 1, 1)
            targets[idx] = rt
            Surface.makeFromBackendRenderTarget(
                ctx, rt, SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.BGRA_8888, ColorSpace.sRGB,
            )?.also { surfaces[idx] = it }
        } ?: return false

        if (surf.isClosed) return false
        val canvas = surf.canvas
        canvas.clear(Color.TRANSPARENT)
        sc.render(canvas.asComposeCanvas(), nowNanos)
        surf.flushAndSubmit()
        present(nativeCtx)
        return true
    }

    // ---- input forwarding: AWT mouse -> ComposeScene ----------------------
    // Coordinates must be RELATIVE TO THE UI LAYER (video rect origin), in px.
    fun onMouse(e: MouseEvent, layerOriginX: Int, layerOriginY: Int) {
        val sc = scene ?: return
        val pos = Offset(
            (e.xOnScreen - layerOriginX).toFloat(),
            (e.yOnScreen - layerOriginY).toFloat(),
        )
        val type = when (e.id) {
            MouseEvent.MOUSE_PRESSED -> PointerEventType.Press
            MouseEvent.MOUSE_RELEASED -> PointerEventType.Release
            MouseEvent.MOUSE_MOVED,
            MouseEvent.MOUSE_DRAGGED -> PointerEventType.Move

            MouseEvent.MOUSE_ENTERED -> PointerEventType.Enter
            MouseEvent.MOUSE_EXITED -> PointerEventType.Exit
            else -> return
        }
        val button = when (e.button) {
            MouseEvent.BUTTON1 -> PointerButton.Primary
            MouseEvent.BUTTON2 -> PointerButton.Tertiary
            MouseEvent.BUTTON3 -> PointerButton.Secondary
            else -> null
        }
        sc.sendPointerEvent(eventType = type, position = pos, button = button)
        // sendPointerEvent triggers invalidate() if the UI reacts -> a frame
        // gets scheduled automatically. No manual repaint needed.
    }

    fun dispose() {
        disposed = true
        closeSurfaces()
        context?.close(); context = null
        scene?.close(); scene = null
    }
}