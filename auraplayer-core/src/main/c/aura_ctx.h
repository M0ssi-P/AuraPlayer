#ifndef AURA_CTX_H
#define AURA_CTX_H

#include <jni.h>
#include <mpv/client.h>
#include <mpv/render.h>

#ifdef _WIN32
    #include <windows.h>
    #include <d3d11.h>
    #include <dxgi1_4.h>
    #include <d3d12.h>
    #ifdef __cplusplus
        /* Real header — on MinGW it only compiles as C++.
         * Only dcomp_win.cpp sees this branch. */
        #include <dcomp.h>
    #else
        /* MinGW's dcomp.h has no usable C interface. C files only ever
         * hold POINTERS to these, so opaque declarations are enough. */
        typedef struct IDCompositionDevice IDCompositionDevice;
        typedef struct IDCompositionTarget IDCompositionTarget;
        typedef struct IDCompositionVisual IDCompositionVisual;
    #endif
    #include "aura_egl.h"
#elif defined(__APPLE__)
    #include <OpenGL/OpenGL.h>
    #include <IOSurface/IOSurface.h>
    #include <dispatch/dispatch.h>
    #include <pthread.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

#include "jawt_macos.h"

typedef struct AuraCtx {
    mpv_handle         *mpv;
    mpv_render_context *mpv_rctx;
    jobject             jself;
    AuraSurface        *surface;
    int64_t             wid;
    int                 m2_w;
    int                 m2_h;
    int                 audio_only;

#ifdef _WIN32
    IDXGIAdapter1      *ui_adapter;
    ID3D12Device       *ui_dev;
    ID3D12CommandQueue *ui_queue;
    ID3D12Fence        *ui_fence;
    void   *d3d11_fence;    /* ID3D11Fence*  (opaque; d3d11_4 types absent in MinGW) */
    void   *d3d12_fence;
    UINT64            fence_value;
    HANDLE              ui_fence_event;
    UINT64              ui_fence_value;
    UINT64              ui_buffer_fence_values[2];
    int                 torn_down;
    /* mpv event loop thread (created with _beginthreadex) */
    HANDLE thread;

    /* ---- D3D11 (mpv's device; independent from Skiko's D3D12) -------- */
    ID3D11Device        *d3d11_dev;
    ID3D11DeviceContext *d3d11_ctx;
    int                   borrowed_device;
    ID3D11Texture2D     *video_tex;   /* mpv renders into this            */
    IDXGISwapChain1     *video_sc;    /* composition swapchain, opaque    */


    volatile LONG    m2_pend_w;
    volatile LONG    m2_pend_h;
    EGLSurface       m2_surf[3];
    ID3D11Texture2D *m2_tex[3];
    volatile LONG    m2_front;     /* newest complete frame */
    volatile LONG    m2_inuse;     /* what the consumer is drawing */
    volatile LONG    m2_running;
    HANDLE           m2_evt;
    HANDLE           m2_thread;

    /* ---- ANGLE (runtime-loaded; see aura_egl.h) ----------------------- */
    AuraEgl    egl;                   /* function table + DLL handles      */
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
    volatile LONG    m2_consumer_detached;
    HMODULE              dcomp_dll;      /* dcomp.dll, runtime-loaded     */

    /* ---- Skiko's swapchain — BORROWED, never resized/presented by us -- */
    IDXGISwapChain3 *ui_sc;

    HWND top_hwnd;
    int  video_x, video_y, video_w, video_h;
    CRITICAL_SECTION wlock;

    int hdr_supported;   /* monitor advertises HDR (detected at init) */
    int hdr_active;      /* we created the FP16/scRGB pipeline */

    /* angle render thread */
    HANDLE        render_thread;
    HANDLE        render_wake;        /* set by mpv update callback        */
    volatile LONG render_quit;
    volatile LONG pending_w;      /* resize requests, coalesced by render thread */
    volatile LONG pending_h;
#else
    CGLContextObj        cgl_ctx;
    IOSurfaceRef         m2_surf_io[3];
    unsigned int         m2_tex[3];
    unsigned int         m2_fbo[3];
    int                  m2_front;
    int                  m2_inuse;
    int                  m2_running;
    int                  m2_pend_w;
    int                  m2_pend_h;
    int                  m2_has_thread;
    pthread_t            m2_thread;
    dispatch_semaphore_t m2_sem;
    int                  m2_consumer_detached;
    pthread_t thread;
    int       thread_started;
#endif
} AuraCtx;

#ifdef _WIN32
/* dcomp_win.cpp (C++ inside, plain C exports) */
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
int  aura_ui_create_swapchain(AuraCtx *c, int w, int h);
int aura_ui_create_swapchain_d3d12(AuraCtx *c, int w, int h);
void aura_ui_test_fill(AuraCtx *c);
#endif

#ifdef __cplusplus
}
#endif

#endif /* AURA_CTX_H */