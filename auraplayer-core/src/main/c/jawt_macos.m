#import <IOKit/pwr_mgt/IOPMLib.h>
#import <Foundation/Foundation.h>
#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <Metal/Metal.h>
#include <jawt.h>
#include <jawt_md.h>
#include <stdio.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <mpv/render_gl.h>
#include <jni.h>
#include <objc/runtime.h>
#include <OpenGL/OpenGL.h>
#include <OpenGL/CGLIOSurface.h>
#include <OpenGL/gl3.h>
#include <IOSurface/IOSurface.h>
#include <CoreFoundation/CoreFoundation.h>
#include "jawt_macos.h"
#include "aura_ctx.h"

#define M2_LOG(...) do { fprintf(stderr, "[model2-mac] " __VA_ARGS__); \
                         fprintf(stderr, "\n"); fflush(stderr); } while (0)

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

static void m2_on_update(void *p)
{
    AuraCtx *c = (AuraCtx *)p;
    if (c && c->m2_sem) dispatch_semaphore_signal(c->m2_sem);
}

static void *m2_gl_get_proc(void *ctx, const char *name)
{
    (void)ctx;
    static void *fw = NULL;
    if (!fw) fw = dlopen("/System/Library/Frameworks/OpenGL.framework/OpenGL",
                         RTLD_LAZY | RTLD_LOCAL);
    return fw ? dlsym(fw, name) : NULL;
}

/* Must run with the CGL context CURRENT, on the thread that will render:
 * mpv queries GL limits and extensions here. */
static int m2_create_mpv_render_context(AuraCtx *c)
{
    if (c->mpv_rctx) return 1;
    mpv_opengl_init_params gl = { .get_proc_address = m2_gl_get_proc,
                                  .get_proc_address_ctx = NULL };
    int adv = 1;
    mpv_render_param p[] = {
        { MPV_RENDER_PARAM_API_TYPE, (void *)MPV_RENDER_API_TYPE_OPENGL },
        { MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl },
        { MPV_RENDER_PARAM_ADVANCED_CONTROL, &adv },
        { MPV_RENDER_PARAM_INVALID, NULL }
    };
    int r = mpv_render_context_create(&c->mpv_rctx, c->mpv, p);
    if (r < 0) {
        M2_LOG("mpv_render_context_create -> %d (%s)", r, mpv_error_string(r));
        return 0;
    }
    return 1;
}

/* IOSurface props in plain CoreFoundation: ObjC literals are __strong locals
 * and ARC won't let a goto jump past them. */
static IOSurfaceRef m2_new_iosurface(int w, int h)
{
    CFMutableDictionaryRef d = CFDictionaryCreateMutable(
        kCFAllocatorDefault, 4, &kCFTypeDictionaryKeyCallBacks,
        &kCFTypeDictionaryValueCallBacks);
    int bpe = 4, fmt = 'BGRA';
    CFNumberRef nw = CFNumberCreate(NULL, kCFNumberIntType, &w);
    CFNumberRef nh = CFNumberCreate(NULL, kCFNumberIntType, &h);
    CFNumberRef nb = CFNumberCreate(NULL, kCFNumberIntType, &bpe);
    CFNumberRef nf = CFNumberCreate(NULL, kCFNumberIntType, &fmt);
    CFDictionarySetValue(d, kIOSurfaceWidth, nw);
    CFDictionarySetValue(d, kIOSurfaceHeight, nh);
    CFDictionarySetValue(d, kIOSurfaceBytesPerElement, nb);
    CFDictionarySetValue(d, kIOSurfacePixelFormat, nf);
    IOSurfaceRef s = IOSurfaceCreate(d);
    CFRelease(nw); CFRelease(nh); CFRelease(nb); CFRelease(nf); CFRelease(d);
    return s;
}

/* Both helpers assume the CGL context is ALREADY current — callers manage it. */

static void m2_gl_free_targets(AuraCtx *c)
{
    for (int i = 0; i < 3; i++) {
        if (c->m2_fbo[i]) { glDeleteFramebuffers(1, &c->m2_fbo[i]); c->m2_fbo[i] = 0; }
        if (c->m2_tex[i]) { glDeleteTextures(1, &c->m2_tex[i]);     c->m2_tex[i] = 0; }
        if (c->m2_surf_io[i]) { CFRelease(c->m2_surf_io[i]); c->m2_surf_io[i] = NULL; }
    }
}

/* handlesOut may be NULL (producer-side resize doesn't need the handles back;
 * Kotlin re-mints them via mintSharedHandle after noticing the size change). */
static int m2_gl_make_targets(AuraCtx *c, int w, int h, jlong *handlesOut)
{
    for (int i = 0; i < 3; i++) {
        IOSurfaceRef surf = m2_new_iosurface(w, h);
        if (!surf) { M2_LOG("IOSurfaceCreate %d failed", i); m2_gl_free_targets(c); return 0; }

        glGenTextures(1, &c->m2_tex[i]);
        glBindTexture(GL_TEXTURE_RECTANGLE, c->m2_tex[i]);
        CGLError e = CGLTexImageIOSurface2D(c->cgl_ctx, GL_TEXTURE_RECTANGLE,
                                            GL_RGBA, w, h, GL_BGRA,
                                            GL_UNSIGNED_INT_8_8_8_8_REV, surf, 0);
        if (e != kCGLNoError) {
            M2_LOG("TexImageIOSurface2D %d = %d", i, e);
            CFRelease(surf); m2_gl_free_targets(c); return 0;
        }

        glGenFramebuffers(1, &c->m2_fbo[i]);
        glBindFramebuffer(GL_FRAMEBUFFER, c->m2_fbo[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_RECTANGLE, c->m2_tex[i], 0);
        GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (st != GL_FRAMEBUFFER_COMPLETE) {
            M2_LOG("FBO %d incomplete 0x%04x", i, st);
            CFRelease(surf); m2_gl_free_targets(c); return 0;
        }

        c->m2_surf_io[i] = surf;
        if (handlesOut) handlesOut[i] = (jlong)(intptr_t)surf;
    }
    c->m2_w = w; c->m2_h = h;
    M2_LOG("targets ready %dx%d (fbo %u/%u/%u)", w, h,
           c->m2_fbo[0], c->m2_fbo[1], c->m2_fbo[2]);
    return 1;
}

static void *m2_producer(void *arg)
{
    AuraCtx *c = (AuraCtx *)arg;

    /* current for this thread's whole lifetime */
    CGLSetCurrentContext(c->cgl_ctx);

    if (!m2_create_mpv_render_context(c)) {
        M2_LOG("producer: render context failed");
        CGLSetCurrentContext(NULL);
        return NULL;
    }
    mpv_render_context_set_update_callback(c->mpv_rctx, m2_on_update, c);
    M2_LOG("producer: render context ready");

    while (__atomic_load_n(&c->m2_running, __ATOMIC_ACQUIRE)) {
        dispatch_semaphore_wait(c->m2_sem,
            dispatch_time(DISPATCH_TIME_NOW, 100 * NSEC_PER_MSEC));
        if (!__atomic_load_n(&c->m2_running, __ATOMIC_ACQUIRE)) break;

        /* resize between frames — no producer restart, so no flicker */
        int pw = __atomic_load_n(&c->m2_pend_w, __ATOMIC_ACQUIRE);
        int ph = __atomic_load_n(&c->m2_pend_h, __ATOMIC_ACQUIRE);
        if (pw > 0 && ph > 0 && (pw != c->m2_w || ph != c->m2_h) &&
             __atomic_load_n(&c->m2_consumer_detached, __ATOMIC_ACQUIRE)) {
             m2_gl_free_targets(c);
             if (!m2_gl_make_targets(c, pw, ph, NULL)) {
                  M2_LOG("producer: resize to %dx%d failed", pw, ph);
                  break;
             }
             __atomic_store_n(&c->m2_front, 0, __ATOMIC_RELEASE);
             __atomic_store_n(&c->m2_inuse, 0, __ATOMIC_RELEASE);
             __atomic_store_n(&c->m2_consumer_detached, 0, __ATOMIC_RELEASE);
             M2_LOG("producer resized to %dx%d", pw, ph);
        }

        /* consume the notification, then render unconditionally:
         * MPV_RENDER_UPDATE_FRAME is one-shot and cleared by update(). */
        mpv_render_context_update(c->mpv_rctx);

        /* a buffer that is neither published nor being drawn */
        int front = __atomic_load_n(&c->m2_front, __ATOMIC_ACQUIRE);
        int inuse = __atomic_load_n(&c->m2_inuse, __ATOMIC_ACQUIRE);
        int back = 0;
        while (back == front || back == inuse) back++;
        if (!c->m2_fbo[back]) continue;

        mpv_opengl_fbo fbo = {
            .fbo = (int)c->m2_fbo[back],   /* real FBO id — no default framebuffer */
            .w = c->m2_w,
            .h = c->m2_h,
            .internal_format = 0
        };
        int flip = 0;
        mpv_render_param p[] = {
            { MPV_RENDER_PARAM_OPENGL_FBO, &fbo },
            { MPV_RENDER_PARAM_FLIP_Y, &flip },
            { MPV_RENDER_PARAM_INVALID, NULL }
        };
        mpv_render_context_render(c->mpv_rctx, p);
        mpv_render_context_report_swap(c->mpv_rctx);

        glFlush();   /* macOS analogue of d3d11_ctx->Flush() */

        __atomic_store_n(&c->m2_front, back, __ATOMIC_RELEASE);   /* publish */
    }

    CGLSetCurrentContext(NULL);
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_notifyDetached(
    JNIEnv *env, jclass cls, jlong ctxPtr)
{
    (void)env; (void)cls;
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c) return;
    __atomic_store_n(&c->m2_consumer_detached, 1, __ATOMIC_RELEASE);
    if (c->m2_sem) dispatch_semaphore_signal(c->m2_sem);
}

/* ========================= model-2: JNI ========================= */

JNIEXPORT jboolean JNICALL
Java_com_mossip_auraplayer_engine_Model2_initRender(
    JNIEnv *env, jclass cls, jlong ctxPtr)
{
    (void)env; (void)cls;
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c) return JNI_FALSE;
    /* AuraCtx isn't zero-initialised — clear model-2 state up front. */
    c->cgl_ctx = NULL;
    c->m2_sem = NULL;
    c->m2_has_thread = 0;
    c->m2_w = 0; c->m2_h = 0;
    for (int i = 0; i < 3; i++) {
        c->m2_surf_io[i] = NULL; c->m2_tex[i] = 0; c->m2_fbo[i] = 0;
    }
    __atomic_store_n(&c->m2_consumer_detached, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&c->m2_front, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&c->m2_inuse, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&c->m2_running, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&c->m2_pend_w, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&c->m2_pend_h, 0, __ATOMIC_RELEASE);
    return JNI_TRUE;
}

/** CGL context + three IOSurface targets + producer thread.
 *  Returns long[3] of IOSurfaceRef, or null on failure. */
JNIEXPORT jlongArray JNICALL
Java_com_mossip_auraplayer_engine_Model2_startProducer(
    JNIEnv *env, jclass cls, jlong ctxPtr, jint w, jint h)
{
    (void)cls;
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c || !c->mpv || w <= 0 || h <= 0) return NULL;

    if (!c->cgl_ctx) {
        CGLPixelFormatAttribute attrs[] = {
            kCGLPFAAccelerated,
            kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
            kCGLPFAColorSize, (CGLPixelFormatAttribute)24,
            kCGLPFAAlphaSize, (CGLPixelFormatAttribute)8,
            (CGLPixelFormatAttribute)0
        };
        CGLPixelFormatObj pf = NULL; GLint npix = 0;
        if (CGLChoosePixelFormat(attrs, &pf, &npix) != kCGLNoError || !pf) {
            M2_LOG("ChoosePixelFormat failed"); return NULL;
        }
        CGLError e = CGLCreateContext(pf, NULL, &c->cgl_ctx);
        CGLDestroyPixelFormat(pf);
        if (e != kCGLNoError || !c->cgl_ctx) { M2_LOG("CreateContext=%d", e); return NULL; }
    }

    jlong handles[3];
    CGLSetCurrentContext(c->cgl_ctx);
    int ok = m2_gl_make_targets(c, w, h, handles);
    CGLSetCurrentContext(NULL);
    if (!ok) return NULL;

    __atomic_store_n(&c->m2_front, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&c->m2_inuse, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&c->m2_pend_w, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&c->m2_pend_h, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&c->m2_running, 1, __ATOMIC_RELEASE);
    c->m2_sem = dispatch_semaphore_create(0);

    if (pthread_create(&c->m2_thread, NULL, m2_producer, c) != 0) {
        M2_LOG("pthread_create failed");
        __atomic_store_n(&c->m2_running, 0, __ATOMIC_RELEASE);
        CGLSetCurrentContext(c->cgl_ctx);
        m2_gl_free_targets(c);
        CGLSetCurrentContext(NULL);
        return NULL;
    }
    c->m2_has_thread = 1;
    M2_LOG("producer started %dx%d", w, h);

    jlongArray out = (*env)->NewLongArray(env, 3);
    (*env)->SetLongArrayRegion(env, out, 0, 3, handles);
    return out;
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_stopProducer(
    JNIEnv *env, jclass cls, jlong ctxPtr)
{
    (void)env; (void)cls;
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c || !c->m2_has_thread) return;   /* never started */

    if (c->mpv_rctx) mpv_render_context_set_update_callback(c->mpv_rctx, NULL, NULL);
    __atomic_store_n(&c->m2_running, 0, __ATOMIC_RELEASE);
    if (c->m2_sem) dispatch_semaphore_signal(c->m2_sem);
    pthread_join(c->m2_thread, NULL);
    c->m2_has_thread = 0;
    c->m2_sem = NULL;     /* ARC releases the semaphore */

    CGLSetCurrentContext(c->cgl_ctx);
    m2_gl_free_targets(c);
    CGLSetCurrentContext(NULL);
    M2_LOG("producer stopped");
}

/** Publishes which buffer the consumer is about to draw, and returns it. */
JNIEXPORT jint JNICALL
Java_com_mossip_auraplayer_engine_Model2_acquireFront(
    JNIEnv *env, jclass cls, jlong ctxPtr)
{
    (void)env; (void)cls;
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c) return 0;
    int f = __atomic_load_n(&c->m2_front, __ATOMIC_ACQUIRE);
    __atomic_store_n(&c->m2_inuse, f, __ATOMIC_RELEASE);
    return (jint)f;
}

/** IOSurfaceRef → id<MTLTexture> on Skiko's device.
 *  Windows counterpart wraps a shared NT handle as an ID3D12Resource. */
JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_Model2_wrapNative(
    JNIEnv *env, jclass cls, jlong handle, jlong devicePtr, jint w, jint h)
{
    (void)env; (void)cls;
    if (!handle || !devicePtr) return 0;
    IOSurfaceRef surf = (IOSurfaceRef)(intptr_t)handle;
    id<MTLDevice> dev = (__bridge id<MTLDevice>)(void *)(intptr_t)devicePtr;

    MTLTextureDescriptor *d =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                           width:w
                                                          height:h
                                                       mipmapped:NO];
    d.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    d.storageMode = MTLStorageModeManaged;   /* IOSurface-backed on Intel */

    id<MTLTexture> tex = [dev newTextureWithDescriptor:d iosurface:surf plane:0];
    if (!tex) { M2_LOG("newTextureWithDescriptor:iosurface: returned nil"); return 0; }

    M2_LOG("wrapped IOSurface %p -> MTLTexture %p", surf, (__bridge void *)tex);
    return (jlong)(intptr_t)(__bridge_retained void *)tex;   /* +1 — Skia holds it */
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_releaseNative(
    JNIEnv *env, jclass cls, jlong texPtr)
{
    (void)env; (void)cls;
    if (!texPtr) return;
    id<MTLTexture> tex = (__bridge_transfer id<MTLTexture>)(void *)(intptr_t)texPtr;
    tex = nil;   /* ARC releases the +1 from wrapNative */
}

/** Kotlin already pulled the real id<MTLDevice> out of MetalRedrawer.adapter.ptr,
 *  so this is identity. (On Windows it digs the device out of skiko's struct.) */
JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_Model2_skiaDevicePtr(
    JNIEnv *env, jclass cls, jlong p)
{
    (void)env; (void)cls;
    return p;
}

/** Ask the producer to swap to a new size between frames. Non-blocking:
 *  the consumer keeps drawing the old buffers until the swap lands. */
JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_requestResize(
    JNIEnv *env, jclass cls, jlong ctxPtr, jint w, jint h)
{
    (void)env; (void)cls;
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c || w <= 0 || h <= 0) return;
    __atomic_store_n(&c->m2_pend_w, w, __ATOMIC_RELEASE);
    __atomic_store_n(&c->m2_pend_h, h, __ATOMIC_RELEASE);
    if (c->m2_sem) dispatch_semaphore_signal(c->m2_sem);   /* wake it now */
}

/* m2_w/m2_h are plain ints, written only by the producer thread right after
 * it finishes recreating buffers at a new size. Reading them from another
 * thread without a fence is technically a data race, but aligned 32-bit
 * reads/writes are atomic in practice on every platform this ships to, and
 * the worst case of a stale read here is "consumer notices the resize one
 * frame late" — not a torn/garbage value. */
JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_getCurrentSizeNative(
    JNIEnv *env, jclass cls, jlong ctxPtr, jintArray out)
{
    (void)cls;
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    jint vals[2] = { 0, 0 };
    if (c) { vals[0] = c->m2_w; vals[1] = c->m2_h; }
    (*env)->SetIntArrayRegion(env, out, 0, 2, vals);
}

/** Current IOSurfaceRef for a buffer index — used to re-wrap after a resize. */
JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_Model2_mintSharedHandle(
    JNIEnv *env, jclass cls, jlong ctxPtr, jint index)
{
    (void)env; (void)cls;
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c || index < 0 || index > 2) return 0;
    return (jlong)(intptr_t)c->m2_surf_io[index];
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_decorate(
    JNIEnv *env, jclass cls, jlong nsWindowPtr, jfloat aspect, jint minW)
{
    (void)env; (void)cls;
    NSWindow *win = (__bridge NSWindow *)(void *)(intptr_t)nsWindowPtr;
    if (!win) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        win.titlebarAppearsTransparent = YES;
        win.titleVisibility = NSWindowTitleHidden;
        win.styleMask |= NSWindowStyleMaskFullSizeContentView;
        win.movableByWindowBackground = YES;      /* drag anywhere */
        [win standardWindowButton:NSWindowCloseButton].hidden = YES;
        [win standardWindowButton:NSWindowMiniaturizeButton].hidden = YES;
        [win standardWindowButton:NSWindowZoomButton].hidden = YES;
        if (aspect > 0) win.aspectRatio = NSMakeSize(aspect * 100.0, 100.0);
        win.minSize = NSMakeSize(minW, aspect > 0 ? minW / aspect : 90);
        NSRect f = win.frame;
        [win setFrame:NSMakeRect(f.origin.x, f.origin.y, f.size.width, f.size.height + 1)
          display:NO];
        [win setFrame:f display:YES];
        [win.contentView setNeedsLayout:YES];
        [win.contentView layoutSubtreeIfNeeded];
    });
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_setAspect(
    JNIEnv *env, jclass cls, jlong nsWindowPtr, jfloat aspect)
{
    (void)env; (void)cls;
    NSWindow *win = (__bridge NSWindow *)(void *)(intptr_t)nsWindowPtr;
    if (!win || aspect <= 0) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        win.aspectRatio = NSMakeSize(aspect * 100.0, 100.0);
    });
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_setNoDrag(
    JNIEnv *env, jclass cls, jlong nsWindowPtr, jboolean noDrag)
{
    (void)env; (void)cls;
    NSWindow *win = (__bridge NSWindow *)(void *)(intptr_t)nsWindowPtr;
    if (!win) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        win.movableByWindowBackground = noDrag ? NO : YES;
    });
}

/* ---- diagnostics ---- */

JNIEXPORT jstring JNICALL
Java_com_mossip_auraplayer_engine_Model2_objcClassName(
    JNIEnv *env, jclass cls, jlong p)
{
    (void)cls;
    if (!p) return NULL;
    uintptr_t v = (uintptr_t)p;
    if (v < 0x1000 || (v >> 48) != 0) return NULL;
    const char *n = object_getClassName((__bridge id)(void *)v);
    return n ? (*env)->NewStringUTF(env, n) : NULL;
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_macTestClear(
    JNIEnv *env, jclass cls, jlong ctxPtr, jint index,
    jfloat r, jfloat g, jfloat b)
{
    (void)env; (void)cls;
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c || !c->cgl_ctx || index < 0 || index > 2 || !c->m2_fbo[index]) return;

    CGLSetCurrentContext(c->cgl_ctx);
    glBindFramebuffer(GL_FRAMEBUFFER, c->m2_fbo[index]);
    glViewport(0, 0, c->m2_w, c->m2_h);
    glClearColor(r, g, b, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glFlush();
    CGLSetCurrentContext(NULL);
    M2_LOG("cleared buf %d to %.1f,%.1f,%.1f", index, r, g, b);
}