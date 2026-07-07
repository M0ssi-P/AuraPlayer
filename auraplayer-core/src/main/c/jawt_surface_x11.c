/*
 * Linux/X11 surface layer for AuraPlayer.
 *
 * An AWT Canvas maps to a real X11 Window, so JAWT hands us its drawable
 * id. mpv (vo=gpu-next, gpu-api=vulkan) reparents its own window into it
 * and follows ConfigureNotify events itself, so resize/visibility/detach
 * are no-ops here.
 *
 * Note: this is the X11 path. Under Wayland, run the JVM via XWayland
 * (the default for AWT apps) -- native Wayland has no foreign-window
 * embedding for mpv to use.
 */

#include <X11/Xlib.h>

#include <jawt.h>
#include <jawt_md.h>
#include <stdio.h>

#include "jawt_macos.h"

int64_t aura_surface_attach(JNIEnv *env, jobject canvas)
{
    JAWT awt;
    awt.version = JAWT_VERSION_9;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
        awt.version = JAWT_VERSION_1_4;
        if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
            fprintf(stderr, "[AuraPlayer/x11] JAWT_GetAWT failed\n");
            return 0;
        }
    }

    JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, canvas);
    if (!ds) {
        fprintf(stderr, "[AuraPlayer/x11] GetDrawingSurface failed\n");
        return 0;
    }

    int64_t result = 0;
    jint lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
        if (dsi && dsi->platformInfo) {
            JAWT_X11DrawingSurfaceInfo *x11 =
                (JAWT_X11DrawingSurfaceInfo *)dsi->platformInfo;
            result = (int64_t)x11->drawable;
            ds->FreeDrawingSurfaceInfo(dsi);
        }
        ds->Unlock(ds);
    }
    awt.FreeDrawingSurface(ds);

    if (result == 0)
        fprintf(stderr, "[AuraPlayer/x11] surface attach failed\n");
    return result;
}

void aura_surface_resize(int w, int h)
{
    (void)w; (void)h; /* mpv follows ConfigureNotify on the parent window */
}

void aura_surface_set_visible(int visible)
{
    (void)visible;
}

void aura_surface_detach(void)
{
    /* Nothing to release: the X11 Window belongs to AWT. */
}

const char *aura_platform_gpu_context(void) { return NULL;   } /* auto: x11vk */
const char *aura_platform_hwdec(void)       { return "auto"; }