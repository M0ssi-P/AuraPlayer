#ifndef AURA_CTX_H
#define AURA_CTX_H

#include <jni.h>

#include <mpv/client.h>
#include <mpv/render.h>

#ifdef _WIN32
    #include <windows.h>
    #include <d3d11.h>
    #include <dxgi1_4.h>
    #include <dcomp.h>
    #include <EGL/egl.h>
else
    #include <pthread.h>
#endif
#include "jawt_macos.h"

typedef struct AuraCtx {
    mpv_handle         *mpv;
    mpv_render_context *mpv_rctx;
    jobject      jself;
    AuraSurface *surface;
    int64_t      wid;

    #ifdef _WIN32
        /* ---- D3D11 (mpv's device; independent from Skiko's D3D12) -------- */
        ID3D11Device        *d3d11_dev;
        ID3D11DeviceContext *d3d11_ctx;
        ID3D11Texture2D     *video_tex;   /* mpv renders into this            */
        IDXGISwapChain1     *video_sc;    /* composition swapchain, opaque    */

        /* ---- ANGLE (GL context backed by d3d11_dev) ----------------------- */
        EGLDisplay egl_dpy;
        EGLConfig  egl_cfg;
        EGLContext egl_ctx;
        EGLSurface egl_surf;              /* pbuffer wrapping video_tex        */

        /* ---- DirectComposition ------------------------------------------- */
        IDCompositionDevice *dcomp_dev;
        IDCompositionTarget *dcomp_target;
        IDCompositionVisual *root_visual;
        IDCompositionVisual *video_visual;   /* bottom layer */
        IDCompositionVisual *ui_visual;      /* top layer    */

        /* ---- Skiko's swapchain — BORROWED, never resized/presented by us -- */
        IDXGISwapChain3 *ui_sc;

        HWND top_hwnd;
        int  video_x, video_y, video_w, video_h;
        CRITICAL_SECTION wlock;

        /* render thread */
        HANDLE render_thread;
        HANDLE render_wake;               /* event set by mpv update callback  */
        volatile LONG render_quit;
    #else
        pthread_t    thread;
        int          thread_started;
    #endif
} AuraCtx;

#ifdef _WIN32
/* dcomp_win.c */
int  aura_dcomp_init(AuraCtx *c, HWND top_level_hwnd);
int  aura_dcomp_set_video_rect(AuraCtx *c, int x, int y, int w, int h);
void aura_dcomp_attach_ui(AuraCtx *c, IDXGISwapChain3 *sc);
void aura_dcomp_commit(AuraCtx *c);
void aura_dcomp_debug_fill(AuraCtx *c);   /* step-1 smoke test */
void aura_dcomp_teardown(AuraCtx *c);

/* angle_win.c */
int  aura_angle_init(AuraCtx *c);
void aura_angle_ensure_surface(AuraCtx *c);    /* call under wlock */
void aura_angle_release_surface(AuraCtx *c);   /* call under wlock */
int  aura_angle_create_mpv_render_context(AuraCtx *c);
void aura_angle_start_render_thread(AuraCtx *c);
void aura_angle_stop_render_thread(AuraCtx *c);
void aura_angle_teardown(AuraCtx *c);
#endif

#endif /* AURA_CTX_H */