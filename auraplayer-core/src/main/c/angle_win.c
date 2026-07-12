/* angle_win.c — ANGLE (GL-on-D3D11) bridge for mpv's render API.
 * Owns: EGL display/context/surface, video_tex, mpv render context,
 * and the render thread that presents frames on the video swapchain.
 * Reference for all of this: mpv's own video/out/opengl/context_angle.c.
 */
#ifdef _WIN32

#define COBJMACROS
#include "aura_ctx.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <mpv/render_gl.h>
#include <stdio.h>

/* ANGLE constants — defined here in case eglext_angle.h isn't on the
 * include path. Values are stable, from the ANGLE extension registry. */
#ifndef EGL_PLATFORM_DEVICE_EXT
#define EGL_PLATFORM_DEVICE_EXT 0x313F
#endif
#ifndef EGL_D3D11_DEVICE_ANGLE
#define EGL_D3D11_DEVICE_ANGLE 0x33A1
#endif
#ifndef EGL_D3D_TEXTURE_ANGLE
#define EGL_D3D_TEXTURE_ANGLE 0x33A3
#endif

typedef EGLDeviceEXT (EGLAPIENTRY *PFN_eglCreateDeviceANGLE)(
    EGLint device_type, void *native_device, const EGLAttrib *attrib_list);
typedef EGLDisplay (EGLAPIENTRY *PFN_eglGetPlatformDisplayEXT)(
    EGLenum platform, void *native_display, const EGLint *attrib_list);

#define AURA_LOG(...) do { fprintf(stderr, "[aura-angle] " __VA_ARGS__); \
                           fprintf(stderr, "\n"); fflush(stderr); } while (0)

int aura_angle_init(AuraCtx *c)
{
    PFN_eglCreateDeviceANGLE createDevice =
        (PFN_eglCreateDeviceANGLE)eglGetProcAddress("eglCreateDeviceANGLE");
    PFN_eglGetPlatformDisplayEXT getPlatformDisplay =
        (PFN_eglGetPlatformDisplayEXT)eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (!createDevice || !getPlatformDisplay) {
        AURA_LOG("ANGLE device-creation extensions missing "
                 "(wrong libEGL.dll on PATH?)");
        return 0;
    }

    /* EGL display wrapping OUR D3D11 device — mpv's GL and the video
     * swapchain then live on one device; no cross-device interop needed. */
    EGLDeviceEXT dev = createDevice(EGL_D3D11_DEVICE_ANGLE,
                                    (void *)c->d3d11_dev, NULL);
    if (!dev) { AURA_LOG("eglCreateDeviceANGLE failed"); return 0; }

    c->egl_dpy = getPlatformDisplay(EGL_PLATFORM_DEVICE_EXT, dev, NULL);
    if (c->egl_dpy == EGL_NO_DISPLAY ||
        !eglInitialize(c->egl_dpy, NULL, NULL)) {
        AURA_LOG("eglInitialize failed: 0x%04x", eglGetError());
        return 0;
    }

    const EGLint cfg_attr[] = {
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE
    };
    EGLint n = 0;
    if (!eglChooseConfig(c->egl_dpy, cfg_attr, &c->egl_cfg, 1, &n) || n < 1) {
        AURA_LOG("eglChooseConfig failed"); return 0;
    }

    const EGLint ctx_attr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    c->egl_ctx = eglCreateContext(c->egl_dpy, c->egl_cfg,
                                  EGL_NO_CONTEXT, ctx_attr);
    if (c->egl_ctx == EGL_NO_CONTEXT) {
        AURA_LOG("eglCreateContext failed: 0x%04x", eglGetError());
        return 0;
    }
    AURA_LOG("init ok");
    return 1;
}

/* Both called under c->wlock (dcomp_win.c takes it). */
void aura_angle_ensure_surface(AuraCtx *c)
{
    if (c->egl_surf || c->video_w <= 0 || c->video_h <= 0)
        return;

    D3D11_TEXTURE2D_DESC td;
    ZeroMemory(&td, sizeof(td));
    td.Width            = c->video_w;
    td.Height           = c->video_h;
    td.Format           = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc.Count = 1;
    td.ArraySize        = 1;
    td.MipLevels        = 1;
    td.BindFlags        = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    HRESULT hr = ID3D11Device_CreateTexture2D(c->d3d11_dev, &td, NULL,
                                              &c->video_tex);
    if (FAILED(hr)) { AURA_LOG("CreateTexture2D failed: 0x%08lx", hr); return; }

    const EGLint pb[] = {
        EGL_WIDTH, c->video_w, EGL_HEIGHT, c->video_h, EGL_NONE
    };
    c->egl_surf = eglCreatePbufferFromClientBuffer(
        c->egl_dpy, EGL_D3D_TEXTURE_ANGLE,
        (EGLClientBuffer)c->video_tex, c->egl_cfg, pb);
    if (c->egl_surf == EGL_NO_SURFACE) {
        AURA_LOG("eglCreatePbufferFromClientBuffer failed: 0x%04x",
                 eglGetError());
        ID3D11Texture2D_Release(c->video_tex);
        c->video_tex = NULL;
        c->egl_surf = NULL;
    }
}

void aura_angle_release_surface(AuraCtx *c)
{
    eglMakeCurrent(c->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (c->egl_surf)  { eglDestroySurface(c->egl_dpy, c->egl_surf); c->egl_surf = NULL; }
    if (c->video_tex) { ID3D11Texture2D_Release(c->video_tex);      c->video_tex = NULL; }
}

/* ---- mpv render context ------------------------------------------------ */

static void *get_proc_address(void *ctx, const char *name)
{
    (void)ctx;
    return (void *)eglGetProcAddress(name);
}

static void on_mpv_render_update(void *data)
{
    AuraCtx *c = (AuraCtx *)data;
    SetEvent(c->render_wake);   /* wake the render thread */
}

int aura_angle_create_mpv_render_context(AuraCtx *c)
{
    /* A context must be current while mpv probes GL during creation. */
    if (!eglMakeCurrent(c->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE,
                        c->egl_ctx)) {
        /* Surfaceless may be unsupported; retry with a 1x1 pbuffer. */
        const EGLint tiny[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        EGLSurface s = eglCreatePbufferSurface(c->egl_dpy, c->egl_cfg, tiny);
        eglMakeCurrent(c->egl_dpy, s, s, c->egl_ctx);
    }

    mpv_opengl_init_params gl_init = {
        .get_proc_address = get_proc_address,
        .get_proc_address_ctx = NULL,
    };
    int advanced = 1;
    mpv_render_param params[] = {
        { MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_OPENGL },
        { MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init },
        { MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced },
        { 0 }
    };
    int r = mpv_render_context_create(&c->mpv_rctx, c->mpv, params);
    if (r < 0) {
        AURA_LOG("mpv_render_context_create failed: %s", mpv_error_string(r));
        return 0;
    }
    mpv_render_context_set_update_callback(c->mpv_rctx,
                                           on_mpv_render_update, c);
    AURA_LOG("mpv render context ok");
    return 1;
}

/* ---- render thread ------------------------------------------------------ */

static void render_one_frame(AuraCtx *c)
{
    EnterCriticalSection(&c->wlock);
    if (!c->egl_surf || !c->video_sc || !c->mpv_rctx) {
        LeaveCriticalSection(&c->wlock);
        return;
    }

    eglMakeCurrent(c->egl_dpy, c->egl_surf, c->egl_surf, c->egl_ctx);

    mpv_opengl_fbo fbo = { .fbo = 0, .w = c->video_w, .h = c->video_h };
    int flip = 1;
    mpv_render_param p[] = {
        { MPV_RENDER_PARAM_OPENGL_FBO, &fbo },
        { MPV_RENDER_PARAM_FLIP_Y, &flip },
        { 0 }
    };
    mpv_render_context_render(c->mpv_rctx, p);
    glFinish();   /* v1: crude but correct barrier before CopyResource */

    ID3D11Texture2D *back = NULL;
    IDXGISwapChain1_GetBuffer(c->video_sc, 0, &IID_ID3D11Texture2D,
                              (void **)&back);
    if (back) {
        ID3D11DeviceContext_CopyResource(c->d3d11_ctx,
                                         (ID3D11Resource *)back,
                                         (ID3D11Resource *)c->video_tex);
        ID3D11Texture2D_Release(back);
        IDXGISwapChain1_Present(c->video_sc, 1, 0);
    }
    LeaveCriticalSection(&c->wlock);
}

static DWORD WINAPI render_thread_main(LPVOID arg)
{
    AuraCtx *c = (AuraCtx *)arg;
    while (!InterlockedCompareExchange(&c->render_quit, 0, 0)) {
        WaitForSingleObject(c->render_wake, 100 /* ms, teardown poll */);
        if (InterlockedCompareExchange(&c->render_quit, 0, 0))
            break;
        if (!c->mpv_rctx)
            continue;
        uint64_t flags = mpv_render_context_update(c->mpv_rctx);
        if (flags & MPV_RENDER_UPDATE_FRAME)
            render_one_frame(c);
    }
    eglMakeCurrent(c->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return 0;
}

void aura_angle_start_render_thread(AuraCtx *c)
{
    c->render_quit = 0;
    c->render_wake = CreateEventW(NULL, FALSE, FALSE, NULL);
    c->render_thread = CreateThread(NULL, 0, render_thread_main, c, 0, NULL);
    AURA_LOG("render thread started");
}

void aura_angle_stop_render_thread(AuraCtx *c)
{
    if (!c->render_thread)
        return;
    InterlockedExchange(&c->render_quit, 1);
    SetEvent(c->render_wake);
    WaitForSingleObject(c->render_thread, 5000);
    CloseHandle(c->render_thread); c->render_thread = NULL;
    CloseHandle(c->render_wake);   c->render_wake = NULL;
    AURA_LOG("render thread stopped");
}

void aura_angle_teardown(AuraCtx *c)
{
    /* Order matters: thread -> mpv rctx -> surface -> context -> display. */
    aura_angle_stop_render_thread(c);
    if (c->mpv_rctx) {
        mpv_render_context_free(c->mpv_rctx);
        c->mpv_rctx = NULL;
    }
    EnterCriticalSection(&c->wlock);
    aura_angle_release_surface(c);
    LeaveCriticalSection(&c->wlock);
    if (c->egl_ctx != EGL_NO_CONTEXT) {
        eglDestroyContext(c->egl_dpy, c->egl_ctx);
        c->egl_ctx = EGL_NO_CONTEXT;
    }
    if (c->egl_dpy != EGL_NO_DISPLAY) {
        eglTerminate(c->egl_dpy);
        c->egl_dpy = EGL_NO_DISPLAY;
    }
    AURA_LOG("teardown done");
}

#endif /* _WIN32 */