package com.mossi.auraplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import com.mossip.auraplayer.engine.AuraPlayer
import java.awt.Canvas
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.SwingUtilities

@Composable
fun AuraPlayerSurface(
    auraPlayer: AuraPlayer,
    audioOnly: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    val isInitialized by auraPlayer.isInitialized.collectAsState()
    val canvas = remember {
        Canvas().apply { background = java.awt.Color.BLACK }
    }

    // Push the canvas's on-screen rect to native. On the wid/Vulkan embed
    // path this keeps the overlay child window glued to the canvas; on the
    // render-API fallback path it's a harmless no-op.
    fun pushBounds() {
        if (canvas.isShowing && canvas.width > 0 && canvas.height > 0) {
            val p = canvas.locationOnScreen
            auraPlayer.updateSurfaceBounds(p.x, p.y, canvas.width, canvas.height)
        }
    }

    DisposableEffect(canvas) {
        val canvasListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = pushBounds()
            override fun componentMoved(e: ComponentEvent) = pushBounds()
            override fun componentShown(e: ComponentEvent) {
                auraPlayer.setSurfaceVisible(true); pushBounds()
            }
            override fun componentHidden(e: ComponentEvent) =
                auraPlayer.setSurfaceVisible(false)
        }
        // Window RESIZE can move the canvas within the window without firing
        // canvas events (window MOVES are handled natively: child windows
        // follow their parent automatically).
        val windowListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = pushBounds()
        }
        val hierarchyListener = HierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                auraPlayer.setSurfaceVisible(canvas.isShowing)
                if (canvas.isShowing) pushBounds()
            }
        }

        canvas.addComponentListener(canvasListener)
        canvas.addHierarchyListener(hierarchyListener)
        val window = SwingUtilities.getWindowAncestor(canvas)
        window?.addComponentListener(windowListener)

        onDispose {
            canvas.removeComponentListener(canvasListener)
            canvas.removeHierarchyListener(hierarchyListener)
            window?.removeComponentListener(windowListener)
        }
    }

    Box(modifier) {
        SwingPanel(
            background = Color.Black,
            factory = { canvas },
            update = {
                if (canvas.isDisplayable && canvas.graphicsConfiguration != null) {
                    if (!isInitialized) {
                        auraPlayer.initialize(canvas, audioOnly)
                        pushBounds()
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // WARNING (wid/Vulkan path): the video lives in a native child window
        // ABOVE this Compose window, so this content() will be hidden behind
        // the video while that path is active. Controls need to live outside
        // the video rect or in their own floating window. On the render-API
        // fallback path, overlaying here still works as before.
        content()
    }
}