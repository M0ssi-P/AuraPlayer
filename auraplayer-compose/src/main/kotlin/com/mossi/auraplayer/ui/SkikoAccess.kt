package com.mossi.auraplayer.ui

import androidx.compose.ui.awt.ComposeWindow
import org.jetbrains.skia.DirectContext

internal object SkikoAccess {

    fun directContextFromPtr(ptr: Long): DirectContext {
        val ctor = DirectContext::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
        ctor.isAccessible = true
        return ctor.newInstance(ptr)
    }

    private fun walkToRedrawer(window: ComposeWindow): Any? = try {
        val panel = window.javaClass.getDeclaredField("composePanel")
            .apply { isAccessible = true }.get(window)
        val container = panel.javaClass.getDeclaredField("_composeContainer")
            .apply { isAccessible = true }.get(panel)
        val mediator = container.javaClass.getDeclaredField("mediator")
            .apply { isAccessible = true }.get(container)
        val delegate = mediator.javaClass.getDeclaredField("skiaLayerComponent\$delegate")
            .apply { isAccessible = true }.get(mediator)
        val layerComponent = delegate.javaClass.getDeclaredField("_value")
            .apply { isAccessible = true }.get(delegate)
        val content = layerComponent.javaClass.getDeclaredField("contentComponent")
            .apply { isAccessible = true }.get(layerComponent)
        val mgr = content.javaClass.superclass.getDeclaredField("redrawerManager")
            .apply { isAccessible = true }.get(content)
        mgr.javaClass.getDeclaredField("redrawer")
            .apply { isAccessible = true }.get(mgr)
    } catch (e: Exception) {
        println("AuraVideo: walkToRedrawer failed (Compose/skiko version mismatch?): $e")
        null
    }

    /** Pointer to SKIKO'S struct, not a COM object: [2]=ID3D12Device, [3]=queue. */
    fun devicePtr(window: ComposeWindow): Long? {
        val r = walkToRedrawer(window) ?: return null
        if (r.javaClass.simpleName != "Direct3DRedrawer") {
            println("AuraVideo: unsupported redrawer ${r.javaClass.name}")
            return null
        }
        return try {
            r.javaClass.getDeclaredField("device").apply { isAccessible = true }.get(r) as Long
        } catch (e: Exception) { println("AuraVideo: device field: $e"); null }
    }

    /** THROWS until Skiko has rendered a frame — call inside runCatching + retry. */
    fun grContextPtr(window: ComposeWindow): Long? {
        val redrawer = walkToRedrawer(window) ?: return null
        return try {
            val ch = redrawer.javaClass.getDeclaredField("contextHandler")
                .apply { isAccessible = true }.get(redrawer)
            var k: Class<*>? = ch.javaClass
            var ctxField: java.lang.reflect.Field? = null
            while (k != null && ctxField == null) {
                ctxField = k.declaredFields.firstOrNull { it.name == "context" }
                k = k.superclass
            }
            val dc = ctxField!!.apply { isAccessible = true }.get(ch)
            var kk: Class<*>? = dc.javaClass
            var ptrField: java.lang.reflect.Field? = null
            while (kk != null && ptrField == null) {
                ptrField = kk.declaredFields.firstOrNull { it.name == "_ptr" || it.name == "ptr" }
                kk = kk.superclass
            }
            ptrField!!.apply { isAccessible = true }.get(dc) as Long
        } catch (e: Exception) { null }
    }
}