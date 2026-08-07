/*
 * macOS surface layer for AuraPlayer -- multi-instance + underlay.
 * Compile with -fobjc-arc. Link with:
 *   -framework AppKit -framework QuartzCore -framework Foundation
 *   -framework CoreGraphics -framework Metal            <-- Metal is NEW
 *
 * FIXES in this version:
 *   [FIX-DEV]   layer.device + layer.pixelFormat set explicitly. mpv's own
 *               layer path configures these itself; a caller-supplied layer
 *               must do it. Prime suspect for "plays but invisible".
 *   [FIX-SIZE]  width/height clamped so a 1x1 canvas at init time can never
 *               produce a 1x1 swapchain ("Could not initialize video chain").
 *   [FIX-STUB]  setUnderlay / setBorderlessFullscreen no-op symbols so the
 *               Kotlin class links uniformly on macOS.
 */
#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <Metal/Metal.h>
#include <jawt.h>
#include <jawt_md.h>
#include <stdio.h>
#include <stdlib.h>
#include "jawt_macos.h"

@interface AuraMetalLayer : CAMetalLayer
@end

@implementation AuraMetalLayer
- (void)setDrawableSize:(CGSize)s
{
    /* Drop transient degenerate sizes (JAWT zeroes bounds mid-update);
     * real sizes pass through untouched — bounds stay the authority. */
    if (s.width < 16 || s.height < 16) return;
    [super setDrawableSize:s];
}
@end

static dispatch_source_t g_txn_heartbeat = NULL;

static void start_txn_heartbeat(void)
{
    if (g_txn_heartbeat) return;
    g_txn_heartbeat = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER,
                                             0, 0, dispatch_get_main_queue());
    dispatch_source_set_timer(g_txn_heartbeat,
                              DISPATCH_TIME_NOW,
                              16 * NSEC_PER_MSEC,   /* ~60 Hz */
                              4  * NSEC_PER_MSEC);
    dispatch_source_set_event_handler(g_txn_heartbeat, ^{
        [CATransaction begin];
        [CATransaction commit];
        [CATransaction flush];
    });
    dispatch_resume(g_txn_heartbeat);
}

static void stop_txn_heartbeat(void)
{
    if (!g_txn_heartbeat) return;
    dispatch_source_cancel(g_txn_heartbeat);
    g_txn_heartbeat = NULL;
}

struct AuraSurface {
    void *layer; /* CAMetalLayer, retained (ARC __bridge_retained) */
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

            /* [FIX-SIZE] never create a degenerate layer; the canvas may
             * still be at its placeholder 1x1 bounds when init runs. */
            jint w = dsi->bounds.width  > 16 ? dsi->bounds.width  : 640;
            jint h = dsi->bounds.height > 16 ? dsi->bounds.height : 360;

            __block void *retained = NULL;
            run_on_main(^{
                 CAMetalLayer *layer = [AuraMetalLayer layer];
                 CGFloat scale = [NSScreen mainScreen].backingScaleFactor;

                 /* [FIX-DEV] a caller-supplied layer must bring its own
                 * device + pixel format; mpv only does this for layers
                             * it creates itself. */
                            layer.device      = MTLCreateSystemDefaultDevice();
                            layer.pixelFormat = MTLPixelFormatBGRA8Unorm;

                            layer.contentsScale   = scale;
                            layer.opaque          = YES;
                            layer.framebufferOnly = YES;
                            layer.frame           = CGRectMake(0, 0, w, h);
                            layer.drawableSize    = CGSizeMake(w * scale, h * scale);
                            layer.backgroundColor = CGColorGetConstantColor(kCGColorBlack);
                            CGColorSpaceRef cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
                            layer.colorspace = cs;
                            CGColorSpaceRelease(cs);

                            fprintf(stderr, "[color] layer.colorspace=%p\n", (void *)layer.colorspace);

                            /* stretch the last frame to fill during resize gaps
                             * instead of showing black margins */
                            layer.contentsGravity = kCAGravityResize;

                            surfaceLayers.layer = layer;
                            retained = (__bridge_retained void *)layer;

                            fprintf(stderr,
                                "[AuraPlayer/mac] layer=%p %dx%d scale=%.1f device=%p\n",
                                (__bridge void *)layer, (int)w, (int)h, scale,
                                (__bridge void *)layer.device);
                            fflush(stderr);
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
    fprintf(stderr, "[resize] called w=%d h=%d\n", w, h); fflush(stderr);
    CAMetalLayer *layer = (__bridge CAMetalLayer *)s->layer;
    dispatch_async(dispatch_get_main_queue(), ^{
        CGFloat scale = layer.superlayer
                            ? layer.superlayer.contentsScale
                            : [NSScreen mainScreen].backingScaleFactor;
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        layer.contentsScale = scale;
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

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_enableUnderlay(
    JNIEnv *env, jobject obj, jlong videoLayerPtr)
{
    (void)env; (void)obj;
    CAMetalLayer *mine = (__bridge CAMetalLayer *)(void *)(intptr_t)videoLayerPtr;
    if (!mine) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        /* [FLIP] make top-left anchoring native so height changes don't
         * require JAWT's lagging position correction (bottom-follow wobble). */
        if (mine.superlayer) {
            mine.superlayer.geometryFlipped = YES;
            fprintf(stderr, "[AuraPlayer/mac] flipped superlayer=%p\n",
                    (__bridge void *)mine.superlayer);
        } else {
            fprintf(stderr, "[AuraPlayer/mac] no superlayer yet - flip skipped\n");
        }

        CALayer *root = mine;
        while (root.superlayer) root = root.superlayer;
        CALayer *skiko = nil;
        find_other_metal_layer(root, mine, &skiko);
        if (!skiko) {
            fprintf(stderr, "[AuraPlayer/mac] Skiko layer not found\n");
            return;
        }
        skiko.opaque    = NO;
        skiko.zPosition = 0;
        mine.zPosition  = -1000;
    });
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setLiveResize(
    JNIEnv *env, jobject obj, jlong videoLayerPtr, jboolean active)
{
    (void)env; (void)obj;
    CAMetalLayer *layer = (__bridge CAMetalLayer *)(void *)(intptr_t)videoLayerPtr;
    if (!layer) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (active) {
            layer.presentsWithTransaction = YES;
            start_txn_heartbeat();
        } else {
            layer.presentsWithTransaction = NO;
            stop_txn_heartbeat();
            /* release any present still waiting on a commit */
            [CATransaction begin];
            [CATransaction commit];
            [CATransaction flush];
        }
        fprintf(stderr, "[liveResize] %s\n", active ? "ON" : "OFF");
        fflush(stderr);
    });
}

/* [FIX-STUB] Windows-only concepts; symbols must still exist on macOS. */
JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setUnderlay(
    JNIEnv *env, jobject obj, jlong root, jlong video, jint index,
    jint x, jint y, jint w, jint h, jboolean enable)
{
    (void)env; (void)obj; (void)root; (void)video;
    (void)index; (void)x; (void)y; (void)w; (void)h; (void)enable;
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setBorderlessFullscreen(
    JNIEnv *env, jobject obj, jlong handle, jboolean enable)
{
    (void)env; (void)obj;
    NSWindow *win = (__bridge NSWindow *)(void *)(intptr_t)handle;
    if (!win) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        BOOL isFs = (win.styleMask & NSWindowStyleMaskFullScreen) != 0;
        if ((enable && !isFs) || (!enable && isFs)) {
            win.collectionBehavior |= NSWindowCollectionBehaviorFullScreenPrimary;
            [win toggleFullScreen:nil];
        }
    });
}