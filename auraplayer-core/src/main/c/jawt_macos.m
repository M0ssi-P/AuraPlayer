/*
 * macOS surface layer for AuraPlayer.
 *
 * Creates a CAMetalLayer, attaches it to the AWT canvas through
 * JAWT_SurfaceLayers (the only embedding mechanism modern JDKs offer on
 * macOS), and returns its pointer. That pointer goes to mpv as --wid and
 * is consumed by the custom gpu-context=macembed patched into the bundled
 * libmpv, which creates its Vulkan/MoltenVK swapchain directly on this
 * layer. No separate mpv window is ever created.
 *
 * Compile with: clang -fobjc-arc -x objective-c ... (or just name it .m)
 * Frameworks:   -framework AppKit -framework QuartzCore -framework Foundation
 */

#import <Cocoa/Cocoa.h>
#import <QuartzCore/CAMetalLayer.h>
#import <QuartzCore/QuartzCore.h>

#include <jawt.h>
#include <jawt_md.h>

#include "jawt_macos.h"

static CAMetalLayer *g_layer = nil;

/* Run a block on the AppKit main thread, synchronously. */
static void run_on_main(void (^block)(void))
{
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

int64_t aura_surface_attach(JNIEnv *env, jobject canvas)
{
    JAWT awt;
    awt.version = JAWT_VERSION_9;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
#ifdef JAWT_MACOSX_USE_CALAYER
        /* Fallback for older JDKs that require the CALayer flag. */
        awt.version = JAWT_VERSION_1_7 | JAWT_MACOSX_USE_CALAYER;
        if (JAWT_GetAWT(env, &awt) == JNI_FALSE)
#endif
        {
            fprintf(stderr, "[AuraPlayer/mac] JAWT_GetAWT failed\n");
            return 0;
        }
    }

    JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, canvas);
    if (!ds) {
        fprintf(stderr, "[AuraPlayer/mac] GetDrawingSurface failed\n");
        return 0;
    }

    int64_t result = 0;
    jint lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
        if (dsi && dsi->platformInfo) {
            id<JAWT_SurfaceLayers> surfaceLayers =
                (__bridge id<JAWT_SurfaceLayers>)dsi->platformInfo;

            jint w = dsi->bounds.width;
            jint h = dsi->bounds.height;

            run_on_main(^{
                CAMetalLayer *layer = [CAMetalLayer layer];
                CGFloat scale = [NSScreen mainScreen].backingScaleFactor;

                layer.contentsScale   = scale;
                layer.opaque          = YES;
                layer.framebufferOnly = YES;
                layer.frame           = CGRectMake(0, 0, w, h);
                layer.drawableSize    = CGSizeMake(w * scale, h * scale);
                layer.backgroundColor = CGColorGetConstantColor(kCGColorBlack);

                /* AWT hosts this layer over the canvas and retains it. */
                surfaceLayers.layer = layer;
                g_layer = layer; /* our own strong reference (ARC) */
            });

            result = (int64_t)(intptr_t)(__bridge void *)g_layer;
            ds->FreeDrawingSurfaceInfo(dsi);
        } else {
            fprintf(stderr, "[AuraPlayer/mac] no JAWT_SurfaceLayers\n");
        }
        ds->Unlock(ds);
    } else {
        fprintf(stderr, "[AuraPlayer/mac] JAWT lock failed\n");
    }
    awt.FreeDrawingSurface(ds);

    if (result == 0)
        fprintf(stderr, "[AuraPlayer/mac] surface attach failed\n");
    return result;
}

void aura_surface_resize(int w, int h)
{
    CAMetalLayer *layer = g_layer;
    if (!layer || w <= 0 || h <= 0) return;

    /* Async is fine: the macembed context polls drawableSize each frame. */
    dispatch_async(dispatch_get_main_queue(), ^{
        CGFloat scale = layer.superlayer
                            ? layer.superlayer.contentsScale
                            : [NSScreen mainScreen].backingScaleFactor;
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        layer.contentsScale = scale;
        layer.frame         = CGRectMake(0, 0, w, h);
        layer.drawableSize  = CGSizeMake(w * scale, h * scale);
        [CATransaction commit];
    });
}

void aura_surface_set_visible(int visible)
{
    CAMetalLayer *layer = g_layer;
    if (!layer) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        layer.hidden = visible ? NO : YES;
    });
}

void aura_surface_detach(void)
{
    /* Caller guarantees mpv_terminate_destroy() has already returned. */
    CAMetalLayer *layer = g_layer;
    g_layer = nil;
    if (!layer) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        [layer removeFromSuperlayer];
    });
}

const char *aura_platform_gpu_context(void) { return "macembed"; }
const char *aura_platform_hwdec(void)       { return "videotoolbox"; }