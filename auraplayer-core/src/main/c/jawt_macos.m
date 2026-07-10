/*
 * macOS surface layer for AuraPlayer -- multi-instance + underlay.
 * Compile with -fobjc-arc.
 *
 * Each player owns its own CAMetalLayer (attached via JAWT_SurfaceLayers,
 * consumed by gpu-context=macembed). enableUnderlay() re-orders THIS
 * player's layer below Skiko's Metal layer so Compose (with a Clear-rect
 * hole) composites on top. No windows are created; the frame keeps its
 * shadow and titlebar.
 */

#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>

#include <jawt.h>
#include <jawt_md.h>
#include <stdio.h>
#include <stdlib.h>

#include "native_player.h"

struct AuraSurface {
    void *layer; /* CAMetalLayer, retained via __bridge_retained */
};

static void run_on_main(void (^block)(void))
{
    if ([NSThread isMainThread]) block();
    else dispatch_sync(dispatch_get_main_queue(), block);
}

AuraSurface *aura_surface_attach(JNIEnv *env, jobject canvas, int64_t *wid_out)
{
    *wid_out = 0;

    JAWT awt;
    awt.version = JAWT_VERSION_9;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
#ifdef JAWT_MACOSX_USE_CALAYER
        awt.version = JAWT_VERSION_1_7 | JAWT_MACOSX_USE_CALAYER;
        if (JAWT_GetAWT(env, &awt) == JNI_FALSE)
#endif
        {
            fprintf(stderr, "[AuraPlayer/mac] JAWT_GetAWT failed\n");
            return NULL;
        }
    }

    JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, canvas);
    if (!ds) return NULL;

    AuraSurface *s = NULL;
    jint lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
        if (dsi && dsi->platformInfo) {
            id<JAWT_SurfaceLayers> surfaceLayers =
                (__bridge id<JAWT_SurfaceLayers>)dsi->platformInfo;

            jint w = dsi->bounds.width;
            jint h = dsi->bounds.height;

            __block void *retained = NULL;
            run_on_main(^{
                CAMetalLayer *layer = [CAMetalLayer layer];
                CGFloat scale = [NSScreen mainScreen].backingScaleFactor;

                layer.contentsScale   = scale;
                layer.opaque          = YES;
                layer.framebufferOnly = YES;
                layer.frame           = CGRectMake(0, 0, w, h);
                layer.drawableSize    = CGSizeMake(w * scale, h * scale);
                layer.backgroundColor = CGColorGetConstantColor(kCGColorBlack);

                surfaceLayers.layer = layer;
                retained = (__bridge_retained void *)layer;
            });

            if (retained) {
                s = (AuraSurface *)calloc(1, sizeof(AuraSurface));
                s->layer = retained;
                *wid_out = (int64_t)(intptr_t)retained;
            }
            ds->FreeDrawingSurfaceInfo(dsi);
        }
        ds->Unlock(ds);
    }
    awt.FreeDrawingSurface(ds);

    if (!s) fprintf(stderr, "[AuraPlayer/mac] surface attach failed\n");
    return s;
}

void aura_surface_resize(AuraSurface *s, int w, int h)
{
    if (!s || !s->layer || w <= 0 || h <= 0) return;
    CAMetalLayer *layer = (__bridge CAMetalLayer *)s->layer;

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

void aura_surface_set_visible(AuraSurface *s, int visible)
{
    if (!s || !s->layer) return;
    CAMetalLayer *layer = (__bridge CAMetalLayer *)s->layer;
    dispatch_async(dispatch_get_main_queue(), ^{
        layer.hidden = visible ? NO : YES;
    });
}

void aura_surface_detach(AuraSurface *s)
{
    if (!s) return;
    if (s->layer) {
        /* transfer ownership into the block; ARC releases it afterwards */
        CAMetalLayer *layer = (__bridge_transfer CAMetalLayer *)s->layer;
        s->layer = NULL;
        dispatch_async(dispatch_get_main_queue(), ^{
            [layer removeFromSuperlayer];
        });
    }
    free(s);
}

const char *aura_platform_gpu_context(void) { return "macembed";     }
const char *aura_platform_hwdec(void)       { return "videotoolbox"; }

/* ------------------------- underlay ------------------------- */

static void find_other_metal_layer(CALayer *layer, CALayer *mine, CALayer **found)
{
    if (*found) return;
    if ([layer isKindOfClass:[CAMetalLayer class]] && layer != mine) {
        *found = layer;
        return;
    }
    for (CALayer *sub in layer.sublayers)
        find_other_metal_layer(sub, mine, found);
}

/*
 * enableUnderlay(videoLayerPtr): put THIS player's layer below Skiko's
 * layer and let Skiko's layer have transparency, so a Compose Clear-rect
 * becomes a see-through hole onto the video.
 */
JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_enableUnderlay(
    JNIEnv *env, jobject obj, jlong videoLayerPtr)
{
    (void)env; (void)obj;
    CAMetalLayer *mine = (__bridge CAMetalLayer *)(void *)(intptr_t)videoLayerPtr;
    if (!mine) return;

    dispatch_async(dispatch_get_main_queue(), ^{
        CALayer *root = mine;
        while (root.superlayer) root = root.superlayer;

        CALayer *skiko = nil;
        find_other_metal_layer(root, mine, &skiko);
        if (!skiko) {
            fprintf(stderr, "[AuraPlayer/mac] Skiko layer not found\n");
            return;
        }

        skiko.opaque    = NO;      /* Compose pixels may be transparent   */
        skiko.zPosition = 0;
        mine.zPosition  = -1000;   /* video sits under Compose            */
    });
}