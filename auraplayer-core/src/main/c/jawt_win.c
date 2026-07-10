/*
 * Windows surface layer for AuraPlayer -- multi-instance + underlay.
 *
 * NO NEW WINDOWS are ever created here. The pieces already exist inside
 * your one frame:
 *   frame HWND
 *    +-- Skiko child HWND      (Compose draws here)
 *    +-- Canvas child HWND     (per player; AWT created it)
 *         +-- mpv child HWND   (mpv created it because wid was set)
 *
 * The underlay only (a) z-orders the Canvas BELOW Skiko's child and
 * (b) reshapes Skiko's existing child with SetWindowRgn so a rectangle
 * is cut out of it, letting the video below show through. The frame
 * keeps its titlebar, shadow, taskbar entry -- untouched.
 */

#include <windows.h>

#include <jawt.h>
#include <jawt_md.h>
#include <stdio.h>

#include "jawt_macos.h"

struct AuraSurface {
    HWND hwnd;   /* the AWT Canvas child window */
};

/* ------------------------- JAWT attach ------------------------- */

AuraSurface *aura_surface_attach(JNIEnv *env, jobject canvas, int64_t *wid_out)
{
    *wid_out = 0;

    JAWT awt;
    awt.version = JAWT_VERSION_9;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
        awt.version = JAWT_VERSION_1_4;
        if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
            fprintf(stderr, "[AuraPlayer/win] JAWT_GetAWT failed\n");
            return NULL;
        }
    }

    JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, canvas);
    if (!ds) return NULL;

    HWND hwnd = NULL;
    jint lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
        if (dsi && dsi->platformInfo) {
            JAWT_Win32DrawingSurfaceInfo *win =
                (JAWT_Win32DrawingSurfaceInfo *)dsi->platformInfo;
            hwnd = win->hwnd;
            ds->FreeDrawingSurfaceInfo(dsi);
        }
        ds->Unlock(ds);
    }
    awt.FreeDrawingSurface(ds);

    if (!hwnd) {
        fprintf(stderr, "[AuraPlayer/win] surface attach failed\n");
        return NULL;
    }

    fprintf(stderr, "[C] attach -> hwnd=%p\n", (void*)hwnd);
    fflush(stderr);
    AuraSurface *s = (AuraSurface *)calloc(1, sizeof(AuraSurface));
    s->hwnd = hwnd;
    *wid_out = (int64_t)(intptr_t)hwnd;
    return s;
}

void aura_surface_resize(AuraSurface *s, int w, int h)
{
    (void)s; (void)w; (void)h; /* mpv's child window follows the Canvas HWND */
}

void aura_surface_set_visible(AuraSurface *s, int visible)
{
    if (s && s->hwnd)
        ShowWindow(s->hwnd, visible ? SW_SHOWNA : SW_HIDE);
}

void aura_surface_detach(AuraSurface *s)
{
    free(s); /* the HWND belongs to AWT */
}

const char *aura_platform_gpu_context(void) { return NULL;   } /* auto: winvk */
const char *aura_platform_hwdec(void)       { return "auto"; }

/* ------------------------- underlay (holes) ------------------------- */

#define AURA_MAX_HOLES 16

static RECT g_holes[AURA_MAX_HOLES];
static BOOL g_holeUsed[AURA_MAX_HOLES];
static HWND g_skiko = NULL;

typedef struct { HWND skiko; HWND exclude_root; } FindCtx;

static BOOL CALLBACK enum_children(HWND child, LPARAM lp)
{
    FindCtx *f = (FindCtx *)lp;
    /* Skip any of our video canvases (they're the excluded subtree). */
    if (child == f->exclude_root || IsChild(f->exclude_root, child))
        return TRUE;

    RECT frame, c;
    GetClientRect(GetParent(child), &frame);
    GetClientRect(child, &c);
    /* Skiko's child spans (almost) the whole client area. */
    if ((c.right - c.left) >= (frame.right - frame.left) - 8 &&
        (c.bottom - c.top) >= (frame.bottom - frame.top) - 8)
        f->skiko = child;
    return TRUE;
}

static void apply_region(void)
{
    if (!g_skiko || !IsWindow(g_skiko)) return;

    RECT rc;
    GetClientRect(g_skiko, &rc);
    HRGN rgn = CreateRectRgn(0, 0, rc.right, rc.bottom);
    for (int i = 0; i < AURA_MAX_HOLES; i++) {
        if (!g_holeUsed[i]) continue;
        HRGN hole = CreateRectRgn(g_holes[i].left,  g_holes[i].top,
                                  g_holes[i].right, g_holes[i].bottom);
        CombineRgn(rgn, rgn, hole, RGN_DIFF);
        DeleteObject(hole);
    }
    SetWindowRgn(g_skiko, rgn, TRUE); /* system takes ownership of rgn */
}

/*
 * setUnderlay(rootHwnd, videoHwnd, index, x, y, w, h, enable)
 *   rootHwnd : frame handle (window.windowHandle from Kotlin)
 *   videoHwnd: this player's canvas (the wid returned at attach)
 *   index    : the player's slot in the host list (0..15)
 *   x,y,w,h  : hole rect in Skiko-client coordinates, DEVICE pixels
 */
JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setUnderlay(
    JNIEnv *env, jobject obj, jlong rootHwnd, jlong videoHwnd,
    jint index, jint x, jint y, jint w, jint h, jboolean enable)
{
    (void)env; (void)obj;
    if (index < 0 || index >= AURA_MAX_HOLES) return;

    HWND root  = (HWND)(intptr_t)rootHwnd;
    HWND video = (HWND)(intptr_t)videoHwnd;
    if (!IsWindow(root) || !IsWindow(video)) return;

    if (!g_skiko || !IsWindow(g_skiko)) {
        FindCtx f = { NULL, video };
        EnumChildWindows(root, enum_children, (LPARAM)&f);
        g_skiko = f.skiko;
        if (!g_skiko) {
            fprintf(stderr, "[AuraPlayer/win] Skiko child window not found\n");
            return;
        }
    }

    /* video (and its AWT ancestors up to the frame) below Skiko's child */
    HWND top = video;
    while (GetParent(top) && GetParent(top) != root)
        top = GetParent(top);

    fprintf(stderr, "[underlay] root=%p video=%p parent(video)=%p\n",
                root, video, GetParent(video));
        if (!g_skiko) { fprintf(stderr, "[underlay] SKIKO NOT FOUND\n"); return; }
        fprintf(stderr, "[underlay] skiko=%p\n", g_skiko);

        RECT sr; GetWindowRect(g_skiko, &sr);
        RECT vr; GetWindowRect(video, &vr);
        fprintf(stderr, "[underlay] skiko screen rect %d,%d %dx%d\n",
                sr.left, sr.top, sr.right-sr.left, sr.bottom-sr.top);
        fprintf(stderr, "[underlay] video screen rect %d,%d %dx%d\n",
                vr.left, vr.top, vr.right-vr.left, vr.bottom-vr.top);
        fprintf(stderr, "[underlay] hole %d,%d %dx%d enable=%d\n", x, y, w, h, enable);

        /* z-order: video directly below skiko, no parent walking */
        SetWindowPos(video, g_skiko, 0, 0, 0, 0,
                     SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    SetWindowPos(top, g_skiko, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);

    if (enable) {
        RECT r = { x, y, x + w, y + h };
        g_holes[index] = r;
        g_holeUsed[index] = TRUE;
    } else {
        g_holeUsed[index] = FALSE;
    }
    apply_region();
}

/* ------------------------- borderless fullscreen ------------------------- */

static LONG_PTR g_fsStyle = 0, g_fsExStyle = 0;
static RECT g_fsRect;
static BOOL g_wasMaximized = FALSE;

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setBorderlessFullscreen(
    JNIEnv *env, jobject obj, jlong hwndPtr, jboolean enable)
{
    (void)env; (void)obj;
    HWND hwnd = (HWND)(intptr_t)hwndPtr;
    if (!hwnd || !IsWindow(hwnd)) return;

    if (enable) {
        g_wasMaximized = IsZoomed(hwnd);
        if (g_wasMaximized)
            ShowWindow(hwnd, SW_RESTORE);

        g_fsStyle   = GetWindowLongPtr(hwnd, GWL_STYLE);
        g_fsExStyle = GetWindowLongPtr(hwnd, GWL_EXSTYLE);
        GetWindowRect(hwnd, &g_fsRect);

        MONITORINFO mi = { .cbSize = sizeof(mi) };
        GetMonitorInfo(MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), &mi);

        SetWindowLongPtr(hwnd, GWL_STYLE,
            (g_fsStyle & ~(WS_CAPTION | WS_THICKFRAME)) | WS_POPUP);
        SetWindowLongPtr(hwnd, GWL_EXSTYLE,
            g_fsExStyle & ~(WS_EX_DLGMODALFRAME | WS_EX_WINDOWEDGE |
                            WS_EX_CLIENTEDGE   | WS_EX_STATICEDGE));
        SetWindowPos(hwnd, HWND_TOP,
            mi.rcMonitor.left, mi.rcMonitor.top,
            mi.rcMonitor.right  - mi.rcMonitor.left,
            mi.rcMonitor.bottom - mi.rcMonitor.top,
            SWP_FRAMECHANGED | SWP_NOOWNERZORDER | SWP_SHOWWINDOW);
    } else {
        SetWindowLongPtr(hwnd, GWL_STYLE,   g_fsStyle);
        SetWindowLongPtr(hwnd, GWL_EXSTYLE, g_fsExStyle);
        SetWindowPos(hwnd, HWND_NOTOPMOST,
            g_fsRect.left, g_fsRect.top,
            g_fsRect.right - g_fsRect.left,
            g_fsRect.bottom - g_fsRect.top,
            SWP_FRAMECHANGED | SWP_NOOWNERZORDER | SWP_SHOWWINDOW);
        if (g_wasMaximized)
            ShowWindow(hwnd, SW_MAXIMIZE);
    }

    /* Skiko's client size changed -> holes must be re-applied by the host
       (it re-sends bounds on resize, which calls setUnderlay again). */
}