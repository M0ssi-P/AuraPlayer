
#ifdef _WIN32

#define COBJMACROS
#include "aura_ctx.h"
#include <mpv/render_gl.h>
#include <stdio.h>

#define AURA_LOG(...) do { fprintf(stderr, "[aura-angle] " __VA_ARGS__); \
                           fprintf(stderr, "\n"); fflush(stderr); } while (0)

/* ---- DLL + symbol loading ---------------------------------------------- */

static int load_egl(AuraEgl *e)
{
    /* Loader (AuraPlayerLoader) should have System.load()ed these already,
     * which puts them in the process; LoadLibrary then just bumps refcount.
     * If not, this searches the normal DLL paths. */
    e->libEGL    = LoadLibraryW(L"libEGL.dll");
    e->libGLESv2 = LoadLibraryW(L"libGLESv2.dll");
    if (!e->libEGL || !e->libGLESv2) {
        AURA_LOG("LoadLibrary failed (libEGL=%p libGLESv2=%p) — "
                 "are the DLLs extracted and loaded before auraplayer.dll?",
                 (void *)e->libEGL, (void *)e->libGLESv2);
        return 0;
    }

#define GET(mod, name, member) \
    do { \
        e->member = (void *)GetProcAddress(e->mod, name); \
        if (!e->member) { AURA_LOG("missing symbol: %s", name); return 0; } \
    } while (0)

    GET(libEGL, "eglGetProcAddress",              GetProcAddress_);
    GET(libEGL, "eglGetError",                    GetError);
    GET(libEGL, "eglInitialize",                  Initialize);
    GET(libEGL, "eglTerminate",                   Terminate);
    GET(libEGL, "eglChooseConfig",                ChooseConfig);
    GET(libEGL, "eglCreateContext",               CreateContext);
    GET(libEGL, "eglDestroyContext",              DestroyContext);
    GET(libEGL, "eglMakeCurrent",                 MakeCurrent);
    GET(libEGL, "eglCreatePbufferSurface",        CreatePbufferSurface);
    GET(libEGL, "eglCreatePbufferFromClientBuffer", CreatePbufferFromClientBuffer);
    GET(libEGL, "eglDestroySurface",              DestroySurface);
    GET(libGLESv2, "glFinish",                    glFinish);
#undef GET

    /* Extensions come through eglGetProcAddress, not GetProcAddress. */
    e->CreateDeviceANGLE = (PFN_eglCreateDeviceANGLE)
        e->GetProcAddress_("eglCreateDeviceANGLE");
    e->GetPlatformDisplayEXT = (PFN_eglGetPlatformDisplayEXT)
        e->GetProcAddress_("eglGetPlatformDisplayEXT");
    if (!e->CreateDeviceANGLE || !e->GetPlatformDisplayEXT) {
        AURA_LOG("ANGLE device-creation extensions missing — a non-ANGLE "
                 "libEGL.dll was loaded from somewhere on the search path");
        return 0;
    }
    return 1;
}

void debug_dump_texture_to_bmp(ID3D11Device *device, ID3D11DeviceContext *context, ID3D11Texture2D *src_tex, const char *filename) {
    D3D11_TEXTURE2D_DESC desc;
    ID3D11Texture2D_GetDesc(src_tex, &desc);

    // 1. Create a CPU-readable staging texture matching the source specs
    desc.BindFlags = 0;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    desc.Usage = D3D11_USAGE_STAGING;
    desc.MiscFlags = 0;

    ID3D11Texture2D *staging_tex = NULL;
    HRESULT hr = ID3D11Device_CreateTexture2D(device, &desc, NULL, &staging_tex);
    if (FAILED(hr) || !staging_tex) return;

    // 2. Copy GPU surface memory to the staging surface
    ID3D11DeviceContext_CopyResource(context, (ID3D11Resource *)staging_tex, (ID3D11Resource *)src_tex);

    // 3. Map the staging texture to read raw byte array data
    D3D11_MAPPED_SUBRESOURCE mapped;
    hr = ID3D11DeviceContext_Map(context, (ID3D11Resource *)staging_tex, 0, D3D11_MAP_READ, 0, &mapped);
    if (SUCCEEDED(hr)) {
        FILE *f = fopen(filename, "wb");
        if (f) {
            unsigned char bmp_hdr[54] = {
                'B','M', 0,0,0,0, 0,0, 0,0, 54,0,0,0,
                40,0,0,0, 0,0,0,0, 0,0,0,0, 1,0, 32,0,
                0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0
            };

            int width = desc.Width;
            int height = desc.Height;
            int image_size = width * height * 4;
            int file_size = image_size + 54;

            // Pack size headers
            *(int*)&bmp_hdr[2] = file_size;
            *(int*)&bmp_hdr[18] = width;
            *(int*)&bmp_hdr[22] = -height; // Negative for top-down coordinate orientation
            *(int*)&bmp_hdr[34] = image_size;

            fwrite(bmp_hdr, 1, 54, f);

            unsigned char *src_pixels = (unsigned char *)mapped.pData;
            // Handle texture pitch stride differences safely
            for (int y = 0; y < height; y++) {
                fwrite(src_pixels + (y * mapped.RowPitch), 1, width * 4, f);
            }
            fclose(f);
            AURA_LOG("DEBUG SUCCESS: Dumped frame preview asset file to: %s", filename);
        }
        ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)staging_tex, 0);
    }
    ID3D11Texture2D_Release(staging_tex);
}

/* ---- init ---------------------------------------------------------------- */

int aura_angle_init(AuraCtx *c)
{
    AuraEgl *e = &c->egl;
    if (!load_egl(e))
        return 0;

    /* EGL display wrapping OUR D3D11 device — mpv's GL and the video
     * swapchain then live on one device; no cross-device interop needed. */
    EGLDeviceEXT dev = e->CreateDeviceANGLE(EGL_D3D11_DEVICE_ANGLE,
                                            (void *)c->d3d11_dev, NULL);
    if (!dev) { AURA_LOG("eglCreateDeviceANGLE failed"); return 0; }

    c->egl_dpy = e->GetPlatformDisplayEXT(EGL_PLATFORM_DEVICE_EXT, dev, NULL);
    if (c->egl_dpy == EGL_NO_DISPLAY ||
        !e->Initialize(c->egl_dpy, NULL, NULL)) {
        AURA_LOG("eglInitialize failed: 0x%04x", e->GetError());
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
    if (!e->ChooseConfig(c->egl_dpy, cfg_attr, &c->egl_cfg, 1, &n) || n < 1) {
        AURA_LOG("eglChooseConfig failed");
        return 0;
    }

    const EGLint ctx_attr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    c->egl_ctx = e->CreateContext(c->egl_dpy, c->egl_cfg,
                                  EGL_NO_CONTEXT, ctx_attr);
    if (c->egl_ctx == EGL_NO_CONTEXT) {
        AURA_LOG("eglCreateContext failed: 0x%04x", e->GetError());
        return 0;
    }
    AURA_LOG("init ok (runtime-loaded ANGLE)");
    return 1;
}

/* ---- surface lifecycle (both called under c->wlock) ---------------------- */

void aura_angle_ensure_surface(AuraCtx *c)
{
    AuraEgl *e = &c->egl;
    if (c->egl_surf || c->video_w <= 0 || c->video_h <= 0)
        return;
    if (!c->video_sc) return;   /* need the swapchain to know the format */

    /* Derive the format FROM the swapchain — CopyResource requires
     * identical formats and fails SILENTLY on mismatch (black screen). */
    DXGI_SWAP_CHAIN_DESC1 scd;
    ZeroMemory(&scd, sizeof(scd));
    IDXGISwapChain1_GetDesc1(c->video_sc, &scd);

    D3D11_TEXTURE2D_DESC td;
    ZeroMemory(&td, sizeof(td));
    td.Width            = c->video_w;
    td.Height           = c->video_h;
    td.Format           = scd.Format;
    td.SampleDesc.Count = 1;
    td.ArraySize        = 1;
    td.MipLevels        = 1;
    td.BindFlags        = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    HRESULT hr = ID3D11Device_CreateTexture2D(c->d3d11_dev, &td, NULL,
                                              &c->video_tex);
    if (FAILED(hr)) { AURA_LOG("CreateTexture2D failed: 0x%08lx", hr); return; }
    AURA_LOG("video_tex %dx%d format=%d (matches swapchain)",
             c->video_w, c->video_h, (int)scd.Format);

    const EGLint pb[] = {
        EGL_WIDTH, c->video_w, EGL_HEIGHT, c->video_h, EGL_NONE
    };
    c->egl_surf = e->CreatePbufferFromClientBuffer(
        c->egl_dpy, EGL_D3D_TEXTURE_ANGLE,
        (EGLClientBuffer)c->video_tex, c->egl_cfg, pb);
    if (c->egl_surf == EGL_NO_SURFACE) {
        AURA_LOG("eglCreatePbufferFromClientBuffer failed: 0x%04x",
                 e->GetError());
        ID3D11Texture2D_Release(c->video_tex);
        c->video_tex = NULL;
    }
}

void aura_angle_release_surface(AuraCtx *c)
{
    AuraEgl *e = &c->egl;
    if (!e->MakeCurrent)   /* init never ran */
        return;
    e->MakeCurrent(c->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (c->egl_surf)  { e->DestroySurface(c->egl_dpy, c->egl_surf); c->egl_surf = NULL; }
    if (c->video_tex) { ID3D11Texture2D_Release(c->video_tex);      c->video_tex = NULL; }
}

/* ---- mpv render context -------------------------------------------------- */

static void *get_proc_address(void *ctx, const char *name)
{
    AuraCtx *c = (AuraCtx *)ctx;
    return c->egl.GetProcAddress_(name);
}

static void on_mpv_render_update(void *data)
{
    AuraCtx *c = (AuraCtx *)data;
    SetEvent(c->render_wake);
}

int aura_angle_create_mpv_render_context(AuraCtx *c)
{
    AuraEgl *e = &c->egl;

    /* A context must be current while mpv probes GL during creation. */
    if (!e->MakeCurrent(c->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE,
                        c->egl_ctx)) {
        /* Surfaceless may be unsupported; retry with a 1x1 pbuffer. */
        const EGLint tiny[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        EGLSurface s = e->CreatePbufferSurface(c->egl_dpy, c->egl_cfg, tiny);
        e->MakeCurrent(c->egl_dpy, s, s, c->egl_ctx);
    }

    mpv_opengl_init_params gl_init = {
        .get_proc_address = get_proc_address,
        .get_proc_address_ctx = c,
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
    mpv_render_context_set_update_callback(c->mpv_rctx, on_mpv_render_update, c);
    e->MakeCurrent(c->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    AURA_LOG("mpv render context ok");
    return 1;
}

/* ---- render thread -------------------------------------------------------- */

static void render_one_frame(AuraCtx *c)
{
    if (getenv("AURA_NO_PRESENT")) return;
    AuraEgl *e = &c->egl;
    EnterCriticalSection(&c->wlock);
    if (!c->egl_surf || !c->video_sc || !c->mpv_rctx) {
        LeaveCriticalSection(&c->wlock);
        return;
    }

    if (!e->MakeCurrent(c->egl_dpy, c->egl_surf, c->egl_surf, c->egl_ctx)) {
        AURA_LOG("MakeCurrent FAILED on render thread: 0x%04x", e->GetError());
        InterlockedExchange(&c->render_quit, 1);
        LeaveCriticalSection(&c->wlock);
        return;
    }

    mpv_opengl_fbo fbo = { .fbo = 0, .w = c->video_w, .h = c->video_h };
    int flip = 0;
    mpv_render_param p[] = {
        { MPV_RENDER_PARAM_OPENGL_FBO, &fbo },
        { MPV_RENDER_PARAM_FLIP_Y, &flip },
        { 0 }
    };
    mpv_render_context_render(c->mpv_rctx, p);
    void (__stdcall *pFlush)(void) =
            (void (__stdcall *)(void))c->egl.GetProcAddress_("glFlush");
        if (pFlush) pFlush();

    e->MakeCurrent(c->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    LeaveCriticalSection(&c->wlock);

    ID3D11Texture2D *back = NULL;
    HRESULT hr = IDXGISwapChain1_GetBuffer(c->video_sc, 0, &IID_ID3D11Texture2D, (void **)&back);

        if (SUCCEEDED(hr) && back) {
            // Copy from the texture ANGLE rendered into over to our DirectComposition SwapChain backbuffer
            ID3D11DeviceContext_CopyResource(c->d3d11_ctx,
                                             (ID3D11Resource *)back,
                                             (ID3D11Resource *)c->video_tex);

            ID3D11Texture2D_Release(back);

            HRESULT hr = IDXGISwapChain1_Present(c->video_sc, 1, 0);
            if (hr == 0x887A0005 /*DEVICE_REMOVED*/ || hr == 0x887A0007 /*DEVICE_RESET*/) {
                    HRESULT why = ID3D11Device_GetDeviceRemovedReason(c->d3d11_dev);
                    AURA_LOG("FATAL: device removed (0x%08lx) — stopping render thread", why);
                    InterlockedExchange(&c->render_quit, 1);   /* stop cleanly, don't spin */
                    return;
            }
        }
}

static int apply_pending_resize(AuraCtx *c)
{
    LONG w = InterlockedCompareExchange(&c->pending_w, 0, 0);
    LONG h = InterlockedCompareExchange(&c->pending_h, 0, 0);
    if (w <= 0 || h <= 0) return 0;
    if (w == c->video_w && h == c->video_h) return 0;

    EnterCriticalSection(&c->wlock);
    aura_angle_release_surface(c);
    if (c->video_sc)
        IDXGISwapChain1_ResizeBuffers(c->video_sc, 0, (UINT)w, (UINT)h,
                                      DXGI_FORMAT_UNKNOWN, 0);
    c->video_w = (int)w;
    c->video_h = (int)h;
    aura_angle_ensure_surface(c);
    LeaveCriticalSection(&c->wlock);
    return 1;
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
        int resized = apply_pending_resize(c);
        uint64_t flags = mpv_render_context_update(c->mpv_rctx);
        if (resized || (flags & MPV_RENDER_UPDATE_FRAME))
            render_one_frame(c);
    }
    c->egl.MakeCurrent(c->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE,
                       EGL_NO_CONTEXT);
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
    AuraEgl *e = &c->egl;

    // 1. Stop the render thread loop first before doing anything else
    aura_angle_stop_render_thread(c);

    // 2. Free mpv render context while critical sections are still completely valid
    if (c->mpv_rctx) {
        mpv_render_context_free(c->mpv_rctx);
        c->mpv_rctx = nullptr;
    }

    EnterCriticalSection(&c->wlock);
    aura_angle_release_surface(c);
    LeaveCriticalSection(&c->wlock);

    // 3. Destroy context, but DO NOT FreeLibrary yet!
    // Move FreeLibrary to the absolute end of the dcomp sequence.
    if (e->DestroyContext && c->egl_ctx != EGL_NO_CONTEXT) {
        e->DestroyContext(c->egl_dpy, c->egl_ctx);
        c->egl_ctx = EGL_NO_CONTEXT;
    }
    if (e->Terminate && c->egl_dpy != EGL_NO_DISPLAY) {
        e->Terminate(c->egl_dpy);
        c->egl_dpy = EGL_NO_DISPLAY;
    }
    AURA_LOG("ANGLE subsystem unlinked.");
}

#endif /* _WIN32 */