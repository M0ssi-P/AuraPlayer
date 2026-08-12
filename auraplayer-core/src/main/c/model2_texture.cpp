#ifdef _WIN32
#include <jni.h>
#include <windows.h>
#include <process.h>
#include <d3d11.h>
#include <d3d11_1.h>
#include <d3d12.h>
#include <dxgi1_2.h>
#include <stdio.h>
#include <stdint.h>
#include <mpv/render_gl.h>
#include "aura_ctx.h"

#define M2_LOG(...) do { fprintf(stderr, "[model2] " __VA_ARGS__); \
                         fprintf(stderr, "\n"); fflush(stderr); } while (0)

static void debug_dump_texture_to_bmp(ID3D11Device *device, ID3D11DeviceContext *context,
                                       ID3D11Texture2D *src_tex, const char *filename)
{
    D3D11_TEXTURE2D_DESC desc;
    src_tex->GetDesc(&desc);

    desc.BindFlags = 0;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    desc.Usage = D3D11_USAGE_STAGING;
    desc.MiscFlags = 0;

    ID3D11Texture2D *staging_tex = nullptr;
    HRESULT hr = device->CreateTexture2D(&desc, nullptr, &staging_tex);
    if (FAILED(hr) || !staging_tex) return;

    context->CopyResource(staging_tex, src_tex);

    D3D11_MAPPED_SUBRESOURCE mapped;
    hr = context->Map(staging_tex, 0, D3D11_MAP_READ, 0, &mapped);
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
            *(int*)&bmp_hdr[2]  = file_size;
            *(int*)&bmp_hdr[18] = width;
            *(int*)&bmp_hdr[22] = -height;
            *(int*)&bmp_hdr[34] = image_size;
            fwrite(bmp_hdr, 1, 54, f);

            unsigned char *src_pixels = (unsigned char *)mapped.pData;
            for (int y = 0; y < height; y++)
                fwrite(src_pixels + (y * mapped.RowPitch), 1, width * 4, f);
            fclose(f);
            M2_LOG("DEBUG SUCCESS: Dumped frame preview to: %s", filename);
        } else {
            M2_LOG("Failed");
        }
        context->Unmap(staging_tex, 0);
    }
    staging_tex->Release();
}

/* ---- 1. Own D3D11 device + ANGLE (Skiko is D3D12, can't be borrowed) ---- */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mossip_auraplayer_engine_Model2_initRender(
    JNIEnv *, jclass, jlong ctxPtr)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c) return JNI_FALSE;

    HRESULT hr = D3D11CreateDevice(
        nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_DEBUG,
        nullptr, 0, D3D11_SDK_VERSION,
        &c->d3d11_dev, nullptr, &c->d3d11_ctx);
    if (FAILED(hr)) { M2_LOG("D3D11CreateDevice failed 0x%08lx", hr); return JNI_FALSE; }

    c->borrowed_device = 0;
    c->m2_thread = nullptr;
    c->m2_evt = nullptr;
    c->m2_w = 0; c->m2_h = 0;
    for (int i = 0; i < 3; i++) { c->m2_surf[i] = EGL_NO_SURFACE; c->m2_tex[i] = nullptr; }
    InterlockedExchange(&c->m2_running, 0);
    InterlockedExchange(&c->m2_front, 0);
    InterlockedExchange(&c->m2_inuse, 0);
    InitializeCriticalSection(&c->wlock);

    if (!aura_angle_init(c)) { M2_LOG("aura_angle_init failed"); return JNI_FALSE; }
    M2_LOG("own D3D11 device + ANGLE ok (dev=%p)", (void *)c->d3d11_dev);
    return JNI_TRUE;
}

/* ---- 2. mpv render context (call AFTER initializeRenderApi) ------------- */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mossip_auraplayer_engine_Model2_createRenderContext(
    JNIEnv *, jclass, jlong ctxPtr)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c || !c->mpv) return JNI_FALSE;
    return aura_angle_create_mpv_render_context(c) ? JNI_TRUE : JNI_FALSE;
}

/* ---- 3. Open a shared handle on Skiko's D3D12 device ------------------- */
extern "C" JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_Model2_openOnD3D12(
    JNIEnv *, jclass, jlong d3d12DevicePtr, jlong sharedHandle)
{
    if (!d3d12DevicePtr || !sharedHandle) return 0;
    ID3D12Device *dev = (ID3D12Device *)(intptr_t)d3d12DevicePtr;
    ID3D12Resource *res = nullptr;
    HRESULT hr = dev->OpenSharedHandle((HANDLE)(intptr_t)sharedHandle,
                                       __uuidof(ID3D12Resource), (void **)&res);
    CloseHandle((HANDLE)(intptr_t)sharedHandle);   /* resource holds its own ref */
    if (FAILED(hr) || !res) { M2_LOG("OpenSharedHandle failed 0x%08lx", hr); return 0; }
    return (jlong)(intptr_t)res;
}

/* ---- 4. Skiko struct accessors ----------------------------------------
 * extractDirect3DDevicePtr returns a pointer to SKIKO'S OWN STRUCT, not a
 * COM object: [2] = ID3D12Device*, [3] = ID3D12CommandQueue*. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_Model2_skiaD3D12Device(JNIEnv*, jclass, jlong p)
{
    if (!p) return 0;
    return (jlong)((uintptr_t *)(intptr_t)p)[2];
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_Model2_skiaD3D12Queue(JNIEnv*, jclass, jlong p)
{
    if (!p) return 0;
    return (jlong)((uintptr_t *)(intptr_t)p)[3];
}

/* ================= 5. Triple-buffered producer =========================
 * mpv renders on its own thread at video rate, independent of Compose, so
 * playback continues while the UI isn't compositing (e.g. a window drag).
 * Three buffers: the producer never writes the published frame (m2_front)
 * nor the one the consumer is currently drawing (m2_inuse).
 * ====================================================================== */

static HANDLE m2_make_target(AuraCtx *c, int i, int w, int h)
{
    D3D11_TEXTURE2D_DESC td = {};
    td.Width = (UINT)w; td.Height = (UINT)h;
    td.MipLevels = 1; td.ArraySize = 1;
    td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_DEFAULT;
    td.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    td.MiscFlags = D3D11_RESOURCE_MISC_SHARED_NTHANDLE | D3D11_RESOURCE_MISC_SHARED;

    if (FAILED(c->d3d11_dev->CreateTexture2D(&td, nullptr, &c->m2_tex[i]))) {
        M2_LOG("target %d: CreateTexture2D failed", i);
        return nullptr;
    }

    IDXGIResource1 *dxres = nullptr;
    if (FAILED(c->m2_tex[i]->QueryInterface(__uuidof(IDXGIResource1), (void**)&dxres))) {
        M2_LOG("target %d: QI IDXGIResource1 failed", i);
        return nullptr;
    }
    HANDLE shared = nullptr;
    HRESULT hr = dxres->CreateSharedHandle(nullptr,
                    DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE,
                    nullptr, &shared);
    dxres->Release();
    if (FAILED(hr) || !shared) { M2_LOG("target %d: CreateSharedHandle failed", i); return nullptr; }

    const EGLint pb[] = { EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE };
    c->m2_surf[i] = c->egl.CreatePbufferFromClientBuffer(
        c->egl_dpy, EGL_D3D_TEXTURE_ANGLE,
        (EGLClientBuffer)c->m2_tex[i], c->egl_cfg, pb);
    if (c->m2_surf[i] == EGL_NO_SURFACE) {
        M2_LOG("target %d: pbuffer failed 0x%04x", i, c->egl.GetError());
        return nullptr;
    }
    return shared;
}

static void m2_free_target(AuraCtx *c, int i)
{
    if (c->m2_surf[i] != EGL_NO_SURFACE) {
        c->egl.DestroySurface(c->egl_dpy, c->m2_surf[i]);
        c->m2_surf[i] = EGL_NO_SURFACE;
    }
    if (c->m2_tex[i]) { c->m2_tex[i]->Release(); c->m2_tex[i] = nullptr; }
}

/* mpv signals a new frame — must be cheap, runs on mpv's thread */
static void m2_on_update(void *p)
{
    AuraCtx *c = (AuraCtx *)p;
    if (c && c->m2_evt) SetEvent(c->m2_evt);
}

static unsigned __stdcall m2_producer(void *arg)
{
    AuraCtx *c = (AuraCtx *)arg;
    AuraEgl *e = &c->egl;

    if (!c->mpv_rctx) {
        M2_LOG("producer: no render context (should have been created earlier)");
        return 0;
    }

    if (!e->MakeCurrent(c->egl_dpy, c->m2_surf[0], c->m2_surf[0], c->egl_ctx)) {
        M2_LOG("producer: initial MakeCurrent failed 0x%04x", e->GetError());
        return 0;
    }

    mpv_render_context_set_update_callback(c->mpv_rctx, m2_on_update, c);
    M2_LOG("producer: render context ready");

    while (InterlockedCompareExchange(&c->m2_running, 1, 1)) {
        WaitForSingleObject(c->m2_evt, 100);
        if (!InterlockedCompareExchange(&c->m2_running, 1, 1)) break;

        LONG pw = InterlockedCompareExchange(&c->m2_pend_w, 0, 0);
        LONG ph = InterlockedCompareExchange(&c->m2_pend_h, 0, 0);
        if (pw > 0 && ph > 0 && (pw != c->m2_w || ph != c->m2_h)) {
            e->MakeCurrent(c->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            for (int i = 0; i < 3; i++) m2_free_target(c, i);
            for (int i = 0; i < 3; i++) m2_make_target(c, i, pw, ph);
            c->m2_w = pw; c->m2_h = ph;
            M2_LOG("producer resized to %dx%d", (int)pw, (int)ph);
        }

        mpv_render_context_update(c->mpv_rctx);
//        if (!(flags & MPV_RENDER_UPDATE_FRAME)) continue;

        /* a buffer that is neither published nor being read */
        int front = (int)InterlockedCompareExchange(&c->m2_front, 0, 0);
        int inuse = (int)InterlockedCompareExchange(&c->m2_inuse, 0, 0);
        int back = 0;
        while (back == front || back == inuse) back++;
        if (c->m2_surf[back] == EGL_NO_SURFACE) continue;

        if (!e->MakeCurrent(c->egl_dpy, c->m2_surf[back], c->m2_surf[back], c->egl_ctx)) {
            static int mc = 0;
            if (mc++ < 10) M2_LOG("producer MakeCurrent failed 0x%04x", e->GetError());
               continue;
        }

        mpv_opengl_fbo fbo = {};
        fbo.fbo = 0;
        fbo.w = c->m2_w;
        fbo.h = c->m2_h;
        fbo.internal_format = 0x8058;   /* GL_RGBA8 */
        int flip = 0;
        mpv_render_param p[] = {
            { MPV_RENDER_PARAM_OPENGL_FBO, &fbo },
            { MPV_RENDER_PARAM_FLIP_Y, &flip },
            { MPV_RENDER_PARAM_INVALID, nullptr }
        };
        mpv_render_context_render(c->mpv_rctx, p);
        mpv_render_context_report_swap(c->mpv_rctx);

        /* glFinish, not glFlush: the publish below must mean "safe to read".
         * It runs on this thread, so it costs the UI nothing. */
        void (__stdcall *pFinish)(void) =
           (void (__stdcall *)(void))e->GetProcAddress_("glFinish");

        M2_LOG("pre-finish");
        if (pFinish) pFinish();
        M2_LOG("post-finish pre-flush");
        if (c->d3d11_ctx) c->d3d11_ctx->Flush();
        M2_LOG("post-flush pre-makecurrent");
        e->MakeCurrent(c->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        M2_LOG("post-makecurrent pre-dump back=%d tex=%p", back, (void*)c->m2_tex[back]);

        static int dumped = 0;
        if (dumped++ < 3 && c->m2_tex[back]) {
            char path[64];
            snprintf(path, sizeof(path), "C:/temp/m2_producer_%d.bmp", dumped);
            debug_dump_texture_to_bmp(c->d3d11_dev, c->d3d11_ctx, c->m2_tex[back], path);
            M2_LOG("post-dump");
        }

        InterlockedExchange(&c->m2_front, back);   /* publish */
        M2_LOG("published back=%d", back);
    }
    return 0;
}

/** Creates three targets and starts the producer.
 *  Returns long[3] of shared NT handles, or null on failure. */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_mossip_auraplayer_engine_Model2_startProducer(
    JNIEnv *env, jclass, jlong ctxPtr, jint w, jint h)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c || !c->mpv || w <= 0 || h <= 0) return nullptr;
    c->m2_w = w; c->m2_h = h;

    HANDLE hs[3];
    for (int i = 0; i < 3; i++) c->m2_surf[i] = EGL_NO_SURFACE;
    for (int i = 0; i < 3; i++) {
        hs[i] = m2_make_target(c, i, w, h);
        if (!hs[i]) {
            for (int j = 0; j < 3; j++) m2_free_target(c, j);
            return nullptr;
        }
    }

    InterlockedExchange(&c->m2_front, 0);
    InterlockedExchange(&c->m2_inuse, 0);
    InterlockedExchange(&c->m2_running, 1);
    c->m2_evt = CreateEvent(nullptr, FALSE, FALSE, nullptr);

    c->m2_thread = (HANDLE)_beginthreadex(nullptr, 0, m2_producer, c, 0, nullptr);
    if (!c->m2_thread) { M2_LOG("producer thread failed"); return nullptr; }

    jlong vals[3] = { (jlong)(intptr_t)hs[0],
                      (jlong)(intptr_t)hs[1],
                      (jlong)(intptr_t)hs[2] };
    jlongArray out = env->NewLongArray(3);
    env->SetLongArrayRegion(out, 0, 3, vals);
    M2_LOG("producer started %dx%d", w, h);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_stopProducer(JNIEnv*, jclass, jlong ctxPtr)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c) return;
    if (!c->m2_thread && !c->m2_evt) return;   // never started

    if (c->mpv_rctx) mpv_render_context_set_update_callback(c->mpv_rctx, nullptr, nullptr);
    InterlockedExchange(&c->m2_running, 0);
    if (c->m2_evt) SetEvent(c->m2_evt);
    if (c->m2_thread) {
        WaitForSingleObject(c->m2_thread, 2000);
        CloseHandle(c->m2_thread);
        c->m2_thread = nullptr;
    }
    if (c->m2_evt) { CloseHandle(c->m2_evt); c->m2_evt = nullptr; }

    for (int i = 0; i < 3; i++) m2_free_target(c, i);
    M2_LOG("producer stopped");
}

/** Publishes which buffer the consumer is about to draw, and returns it. */
extern "C" JNIEXPORT jint JNICALL
Java_com_mossip_auraplayer_engine_Model2_acquireFront(JNIEnv*, jclass, jlong ctxPtr)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c) return 0;
    LONG f = InterlockedCompareExchange(&c->m2_front, 0, 0);
    InterlockedExchange(&c->m2_inuse, f);
    return (jint)f;
}

/* ============================================================================
 * Needed for PiP: a second window's D3D12 device needs its own opened copy
 * of each of the 3 shared textures. Model2_openOnD3D12 closes the handle it
 * receives right after opening it (correct for a single consumer), so it
 * can't be reused for a second consumer — mint a FRESH handle instead.
 * CreateSharedHandle can be called repeatedly on the same D3D11 resource;
 * each call returns an independent handle referencing the same texture.
 * ========================================================================= */

extern "C" JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_Model2_mintSharedHandle(
    JNIEnv*, jclass, jlong ctxPtr, jint bufferIndex)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c || bufferIndex < 0 || bufferIndex > 2 || !c->m2_tex[bufferIndex])
        return 0;

    IDXGIResource1 *dxres = nullptr;
    if (FAILED(c->m2_tex[bufferIndex]->QueryInterface(__uuidof(IDXGIResource1), (void**)&dxres)))
        return 0;

    HANDLE h = nullptr;
    HRESULT hr = dxres->CreateSharedHandle(nullptr,
                    DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE, nullptr, &h);
    dxres->Release();

    if (FAILED(hr) || !h) {
        M2_LOG("mintSharedHandle: CreateSharedHandle failed for buffer %d 0x%08lx",
               bufferIndex, hr);
        return 0;
    }
    return (jlong)(intptr_t)h;
}

/* ============================================================================
 * Native-resolution buffers (no more fixed TARGET_W/TARGET_H). The producer
 * thread already has full resize support (m2_pend_w/m2_pend_h -> it frees
 * and recreates all 3 buffers on its own thread) — these two exports are
 * the only missing plumbing: a way to REQUEST a resize once mpv reports the
 * real decoded size, and a way to READ the buffer size that's actually live
 * right now, so consumers know when to re-wire their Skia wrapping.
 * ========================================================================= */

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_requestResize(
    JNIEnv*, jclass, jlong ctxPtr, jint w, jint h)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    if (!c || w <= 0 || h <= 0) return;
    InterlockedExchange(&c->m2_pend_w, w);
    InterlockedExchange(&c->m2_pend_h, h);
}

/* m2_w/m2_h are plain ints, written only by the producer thread right after
 * it finishes recreating buffers at a new size. Reading them from another
 * thread without a fence is technically a data race, but aligned 32-bit
 * reads/writes are atomic in practice on every platform this ships to, and
 * the worst case of a stale read here is "consumer notices the resize one
 * frame late" — not a torn/garbage value. Not worth the extra complexity of
 * making them volatile LONG + Interlocked for that guarantee. */
extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_Model2_getCurrentSizeNative(
    JNIEnv *env, jclass, jlong ctxPtr, jintArray out)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctxPtr;
    jint vals[2] = { 0, 0 };
    if (c) { vals[0] = c->m2_w; vals[1] = c->m2_h; }
    env->SetIntArrayRegion(out, 0, 2, vals);
}

#endif