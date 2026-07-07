/*
 * Windows surface layer for AuraPlayer.
 *
 * An AWT Canvas is a real heavyweight window on Windows, so JAWT hands us
 * its HWND directly. mpv (vo=gpu-next, gpu-api=vulkan) creates its own
 * child window inside that HWND and tracks the parent's size itself, so
 * resize/visibility/detach are no-ops here.
 */

#include <windows.h>

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
            fprintf(stderr, "[AuraPlayer/win] JAWT_GetAWT failed\n");
            return 0;
        }
    }

    JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, canvas);
    if (!ds) {
        fprintf(stderr, "[AuraPlayer/win] GetDrawingSurface failed\n");
        return 0;
    }

    int64_t result = 0;
    jint lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
        if (dsi && dsi->platformInfo) {
            JAWT_Win32DrawingSurfaceInfo *win =
                (JAWT_Win32DrawingSurfaceInfo *)dsi->platformInfo;
            result = (int64_t)(intptr_t)win->hwnd;
            ds->FreeDrawingSurfaceInfo(dsi);
        }
        ds->Unlock(ds);
    }
    awt.FreeDrawingSurface(ds);

    if (result == 0)
        fprintf(stderr, "[AuraPlayer/win] surface attach failed\n");
    return result;
}

void aura_surface_resize(int w, int h)
{
    (void)w; (void)h; /* mpv's child window follows the parent HWND */
}

void aura_surface_set_visible(int visible)
{
    (void)visible; /* visibility follows the AWT canvas automatically */
}

void aura_surface_detach(void)
{
    /* Nothing to release: the HWND belongs to AWT. */
}

const char *aura_platform_gpu_context(void) { return NULL;   } /* auto: winvk */
const char *aura_platform_hwdec(void)       { return "auto"; }