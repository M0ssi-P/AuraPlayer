/*
 * Linux/X11 surface layer for AuraPlayer -- multi-instance.
 *
 * Each player's AWT Canvas maps to its own X11 Window; mpv reparents its
 * window into it and follows ConfigureNotify itself. The underlay/hole
 * technique is deliberately NOT implemented on Linux (X Shape + AWT +
 * per-WM behavior is not worth the fragility); the Kotlin host uses
 * docked controls on this platform.
 */

#include <X11/Xlib.h>

#include <jawt.h>
#include <jawt_md.h>
#include <stdio.h>
#include <stdlib.h>

#include "native_player.h"

struct AuraSurface {
    Window win;
};

AuraSurface *aura_surface_attach(JNIEnv *env, jobject canvas, int64_t *wid_out)
{
    *wid_out = 0;

    JAWT awt;
    awt.version = JAWT_VERSION_9;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
        awt.version = JAWT_VERSION_1_4;
        if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
            fprintf(stderr, "[AuraPlayer/x11] JAWT_GetAWT failed\n");
            return NULL;
        }
    }

    JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, canvas);
    if (!ds) return NULL;

    Window win = 0;
    jint lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
        if (dsi && dsi->platformInfo) {
            JAWT_X11DrawingSurfaceInfo *x11 =
                (JAWT_X11DrawingSurfaceInfo *)dsi->platformInfo;
            win = (Window)x11->drawable;
            ds->FreeDrawingSurfaceInfo(dsi);
        }
        ds->Unlock(ds);
    }
    awt.FreeDrawingSurface(ds);

    if (!win) {
        fprintf(stderr, "[AuraPlayer/x11] surface attach failed\n");
        return NULL;
    }

    AuraSurface *s = (AuraSurface *)calloc(1, sizeof(AuraSurface));
    s->win = win;
    *wid_out = (int64_t)win;
    return s;
}

void aura_surface_resize(AuraSurface *s, int w, int h)
{
    (void)s; (void)w; (void)h; /* mpv follows ConfigureNotify */
}

void aura_surface_set_visible(AuraSurface *s, int visible)
{
    (void)s; (void)visible;
}

void aura_surface_detach(AuraSurface *s)
{
    free(s); /* the X11 Window belongs to AWT */
}

const char *aura_platform_gpu_context(void) { return NULL;   } /* auto: x11vk */
const char *aura_platform_hwdec(void)       { return "auto"; }

/* EWMH fullscreen -- unchanged from single-instance version */
#define _NET_WM_STATE_REMOVE 0
#define _NET_WM_STATE_ADD    1

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setBorderlessFullscreen(
    JNIEnv *env, jobject obj, jlong handle, jboolean enable)
{
    (void)env; (void)obj;
    Display *dpy = XOpenDisplay(NULL);
    if (!dpy) return;

    Window win = (Window)handle;
    XEvent e = {0};
    e.xclient.type         = ClientMessage;
    e.xclient.window       = win;
    e.xclient.message_type = XInternAtom(dpy, "_NET_WM_STATE", False);
    e.xclient.format       = 32;
    e.xclient.data.l[0]    = enable ? _NET_WM_STATE_ADD : _NET_WM_STATE_REMOVE;
    e.xclient.data.l[1]    = XInternAtom(dpy, "_NET_WM_STATE_FULLSCREEN", False);
    e.xclient.data.l[3]    = 1;

    XSendEvent(dpy, DefaultRootWindow(dpy), False,
               SubstructureRedirectMask | SubstructureNotifyMask, &e);
    XFlush(dpy);
    XCloseDisplay(dpy);
}

/* Underlay is a no-op on Linux; keep the symbol so Kotlin links uniformly. */
JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setUnderlay(
    JNIEnv *env, jobject obj, jlong rootHwnd, jlong videoHwnd,
    jint index, jint x, jint y, jint w, jint h, jboolean enable)
{
    (void)env; (void)obj; (void)rootHwnd; (void)videoHwnd;
    (void)index; (void)x; (void)y; (void)w; (void)h; (void)enable;
}