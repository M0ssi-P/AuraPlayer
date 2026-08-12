package com.mossi.auraplayer.ui.modal2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.Model2
import org.jetbrains.skia.*

/* ------------------------------------------------------------------ */
/* Strong, GC-proof anchors for everything created on Skiko's context. */
/* Skia's Cleaner finalizes GPU objects on a background thread with no */
/* context current — anything collectable here crashes the JVM.        */
/* Never null these out, never close them.                             */
/* ------------------------------------------------------------------ */
object Model2Holder {
    @JvmStatic var ctx: DirectContext? = null
    @JvmStatic var rt: BackendRenderTarget? = null
    @JvmStatic var surface: Surface? = null
    @JvmStatic var player: AuraPlayer? = null
    @JvmStatic var d3d12Res: Long = 0L
    @JvmStatic var ready: Boolean = false
    @JvmStatic var realDev: Long = 0L
    @JvmStatic var queue: Long = 0L
}

private const val VIDEO_W = 1280
private const val VIDEO_H = 720
private const val DXGI_FORMAT_B8G8R8A8_UNORM = 87

/* ---------------------- skiko reflection ---------------------------- */

fun directContextFromPtr(ptr: Long): DirectContext {
    val ctor = DirectContext::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
    ctor.isAccessible = true
    return ctor.newInstance(ptr)
}

private fun walkToRedrawer(window: ComposeWindow): Any? {
    return try {
        val panelField = window.javaClass.getDeclaredField("composePanel")
        panelField.isAccessible = true
        val composePanel = panelField.get(window)
        val containerField = composePanel.javaClass.getDeclaredField("_composeContainer")
        containerField.isAccessible = true
        val composeContainer = containerField.get(composePanel)
        val mediatorField = composeContainer.javaClass.getDeclaredField("mediator")
        mediatorField.isAccessible = true
        val mediator = mediatorField.get(composeContainer)
        val delegateField = mediator.javaClass.getDeclaredField("skiaLayerComponent\$delegate")
        delegateField.isAccessible = true
        val delegate = delegateField.get(mediator)
        val valueField = delegate.javaClass.getDeclaredField("_value")
        valueField.isAccessible = true
        val skiaLayerComponent = valueField.get(delegate)
        val contentComponentField = skiaLayerComponent.javaClass.getDeclaredField("contentComponent")
        contentComponentField.isAccessible = true
        val contentComponent = contentComponentField.get(skiaLayerComponent)
        val skiaLayerClass = contentComponent.javaClass.superclass
        val redrawerManagerField = skiaLayerClass.getDeclaredField("redrawerManager")
        redrawerManagerField.isAccessible = true
        val redrawerManager = redrawerManagerField.get(contentComponent)
        val redrawerField = redrawerManager.javaClass.getDeclaredField("redrawer")
        redrawerField.isAccessible = true
        redrawerField.get(redrawerManager)
    } catch (e: Exception) {
        println("AuraPlayer: walkToRedrawer failed: $e")
        null
    }
}

fun extractRedrawer(window: ComposeWindow): Any? = walkToRedrawer(window)

/** Returns a pointer to SKIKO'S struct, not a COM object.
 *  base[2] = ID3D12Device, base[3] = ID3D12CommandQueue. */
fun extractDirect3DDevicePtr(window: ComposeWindow): Long? {
    val redrawer = walkToRedrawer(window) ?: return null
    if (redrawer.javaClass.simpleName != "Direct3DRedrawer") {
        println("AuraPlayer: unexpected redrawer type: ${redrawer.javaClass.name}")
        return null
    }
    return try {
        val f = redrawer.javaClass.getDeclaredField("device")
        f.isAccessible = true
        f.get(redrawer) as Long
    } catch (e: Exception) {
        println("AuraPlayer: device field failed: $e"); null
    }
}

/** Throws (not returns null) until Skiko has rendered a frame — wrap in runCatching. */
fun extractGrDirectContextPtr(redrawer: Any): Long? {
    return try {
        val ch = redrawer.javaClass.getDeclaredField("contextHandler")
            .apply { isAccessible = true }.get(redrawer)
        var k: Class<*>? = ch.javaClass
        var ctxField: java.lang.reflect.Field? = null
        while (k != null && ctxField == null) {
            ctxField = k.declaredFields.firstOrNull { it.name == "context" }
            k = k.superclass
        }
        val directContext = ctxField!!.apply { isAccessible = true }.get(ch)
        var kk: Class<*>? = directContext.javaClass
        var ptrField: java.lang.reflect.Field? = null
        while (kk != null && ptrField == null) {
            ptrField = kk.declaredFields.firstOrNull { it.name == "_ptr" || it.name == "ptr" }
            kk = kk.superclass
        }
        ptrField!!.apply { isAccessible = true }.get(directContext) as Long
    } catch (e: Exception) {
        println("Aura: extractGrDirectContextPtr failed: $e"); null
    }
}

/* --------------------------- the test ------------------------------- */

//@Composable
//fun Model2ZOrderTest(window: ComposeWindow) {
//    var status by remember { mutableStateOf("starting") }
//    var tick by remember { mutableStateOf(0) }
//    var ready by remember { mutableStateOf(Model2Holder.ready) }
//
//    // one-time setup — no GPU work happens on this thread
//    LaunchedEffect(Unit) {
//        if (Model2Holder.ready) { ready = true; status = "running"; return@LaunchedEffect }
//        try {
//            var ctxPtr: Long? = null
//            var devicePtr = 0L
//            repeat(50) {
//                devicePtr = extractDirect3DDevicePtr(window) ?: 0L
//                val redrawer = extractRedrawer(window)
//                val p = redrawer?.let { r -> runCatching { extractGrDirectContextPtr(r) }.getOrNull() }
//                if (p != null && devicePtr != 0L) { ctxPtr = p; return@repeat }
//                kotlinx.coroutines.delay(100)
//            }
//            val cp = ctxPtr ?: run { status = "no ctx after retries"; return@LaunchedEffect }
//            Model2Holder.ctx = directContextFromPtr(cp)
//
//            val player = AuraPlayer()
//            Model2Holder.player = player
//
//            Model2.initRender(player.nativeCtx)
//            player.initializeRenderApi()
//            Model2.createRenderContext(player.nativeCtx)
//
//            val handle = Model2.createSharedTarget(player.nativeCtx, VIDEO_W, VIDEO_H)
//            val realDev = Model2.skiaD3D12Device(devicePtr)
//            Model2Holder.realDev = realDev
//            Model2Holder.queue = Model2.skiaD3D12Queue(devicePtr)
//            val res = Model2.openOnD3D12(realDev, handle)
//            println("MODEL2: handle=$handle d3d12res=$res")
//            if (res == 0L) { status = "openOnD3D12 failed"; return@LaunchedEffect }
//            Model2Holder.d3d12Res = res
//
//            player.loadFile("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
//
//            kotlinx.coroutines.delay(1500)
//            println("MODEL2: duration=${player.duration.value} paused=${player.playerState.value}")
//
//            Model2Holder.ready = true
//            ready = true
//            status = "running"
//        } catch (e: Throwable) {
//            status = "exception: $e"
//            println("MODEL2 FAIL: $e")
//        }
//    }
//
//    // repaint driver — no GPU calls here
//    LaunchedEffect(ready) {
//        if (!ready) return@LaunchedEffect
//        while (true) { withFrameNanos { }; tick++ }
//    }
//
//    Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
//        Box(Modifier.offset(40.dp, 40.dp).size(240.dp, 140.dp).background(Color.Blue))
//
//        if (ready) {
//            Canvas(Modifier.offset(80.dp, 80.dp).size(320.dp, 180.dp)) {
//                tick // read so this draw re-runs each frame
//
//                val gr = Model2Holder.ctx ?: return@Canvas
//                val p = Model2Holder.player ?: return@Canvas
//                val res = Model2Holder.d3d12Res
//                if (res == 0L) return@Canvas
//
//                // create once, on Skiko's render thread, then keep forever
//                if (Model2Holder.surface == null) {
//                    val r = BackendRenderTarget.makeDirect3D(
//                        VIDEO_W, VIDEO_H, res, DXGI_FORMAT_B8G8R8A8_UNORM, 1, 1
//                    )
//                    Model2Holder.rt = r
//                    Model2Holder.surface = Surface.makeFromBackendRenderTarget(
//                        gr, r, SurfaceOrigin.TOP_LEFT,
//                        SurfaceColorFormat.BGRA_8888, ColorSpace.sRGB
//                    )
//                    println("MODEL2: surface=${Model2Holder.surface != null}")
//                }
//
//                val s = Model2Holder.surface ?: run { println("MODEL2: surface null"); return@Canvas }
//                if (tick % 60 == 0) println("MODEL2: about to renderFrame ctx=${p.nativeCtx}")
//                try {
//                    Model2.renderFrame(p.nativeCtx)
//                    println("MODEL2: renderFrame ctx=${p.nativeCtx}")
//                    if (tick == 150) {
//                        try {
//                            val info = ImageInfo(VIDEO_W, VIDEO_H, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
//                            val bmp = Bitmap()
//                            bmp.allocPixels(info)
//                            val ok = s.readPixels(bmp, 0, 0)
//                            println("MODEL2: readPixels=$ok")
//                            if (ok) {
//                                val img = Image.makeFromBitmap(bmp)
//                                val data = img.encodeToData(EncodedImageFormat.PNG)
//                                java.io.File("C:/temp/skia_read.png").writeBytes(data!!.bytes)
//                                println("MODEL2: wrote skia_read.png")
//                                img.close()
//                            }
//                            bmp.close()
//                        } catch (e: Throwable) { println("MODEL2: readPixels failed: $e") }
//                    }
////                    if (tick == 150) {
////                        Model2.dumpD3D12(Model2Holder.realDev, Model2Holder.queue,
////                            Model2Holder.d3d12Res, VIDEO_W, VIDEO_H)
////                    }
//                } catch (e: Throwable) {
//                    println("MODEL2: renderFrame threw $e")
//                }
//                s.notifyContentWillChange(ContentChangeMode.RETAIN)
//                drawIntoCanvas { cv -> s.draw(cv.nativeCanvas, 0, 0, null) }
//            }
//        }
//
//        Box(Modifier.offset(200.dp, 140.dp).size(200.dp, 120.dp)
//            .background(Color.Green.copy(alpha = 0.7f)))
//
//        Text(status, Modifier.align(Alignment.TopStart), color = Color.White)
//    }
//}