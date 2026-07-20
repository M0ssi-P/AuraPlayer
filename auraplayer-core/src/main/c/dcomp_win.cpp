/* dcomp_win.cpp — DirectComposition tree for AuraPlayer (Windows only).
 *
 * C++ ON PURPOSE: MinGW's dcomp.h has no usable C interface (Microsoft
 * never shipped one), so this file compiles as C++ and exports plain C
 * functions. It REPLACES dcomp_win.c — delete that file.
 *
 * dcomp.dll and DCompositionCreateDevice are loaded at runtime, so no
 * -ldcomp import library is needed (w64devkit may not ship one).
 */
#ifdef _WIN32
#include <jni.h>
#include <windows.h>
#include <d3d11.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <dxgi1_6.h>
#include <dcomp.h>
#include <stdio.h>

#include "aura_ctx.h"

#define AURA_LOG(...) do { fprintf(stderr, "[aura-dcomp] " __VA_ARGS__); \
                           fprintf(stderr, "\n"); fflush(stderr); } while (0)

/* IID defined manually — MinGW's uuid libs don't reliably carry it. */
static const GUID AURA_IID_IDCompositionDevice =
    { 0xC37EA93A, 0xE7AA, 0x450D,
      { 0xB1, 0x6F, 0x97, 0x46, 0xCB, 0x04, 0x07, 0xF3 } };

typedef HRESULT (WINAPI *PFN_DCompositionCreateDevice)(
    IDXGIDevice *dxgiDevice, REFIID iid, void **dcompositionDevice);

static int detect_hdr_output(AuraCtx *c)
{
    int hdr = 0;
    IDXGIDevice  *dxgi = nullptr;
    IDXGIAdapter *adapter = nullptr;

    if (FAILED(c->d3d11_dev->QueryInterface(__uuidof(IDXGIDevice), (void**)&dxgi)))
        return 0;
    dxgi->GetAdapter(&adapter);
    dxgi->Release();
    if (!adapter) return 0;

    /* Find the output containing our window (nearest by intersection). */
    HMONITOR mon = MonitorFromWindow(c->top_hwnd, MONITOR_DEFAULTTONEAREST);
    IDXGIOutput *out = nullptr;
    for (UINT i = 0; adapter->EnumOutputs(i, &out) == S_OK; ++i) {
        DXGI_OUTPUT_DESC od;
        out->GetDesc(&od);
        if (od.Monitor == mon) {
            IDXGIOutput6 *out6 = nullptr;
            if (SUCCEEDED(out->QueryInterface(__uuidof(IDXGIOutput6), (void**)&out6))) {
                DXGI_OUTPUT_DESC1 d1;
                if (SUCCEEDED(out6->GetDesc1(&d1)))
                    hdr = (d1.ColorSpace == DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020);
                out6->Release();
            }
            out->Release();
            break;
        }
        out->Release();
        out = nullptr;
    }
    adapter->Release();
    AURA_LOG("HDR output: %s", hdr ? "YES" : "no");
    return hdr;
}

extern "C" int aura_dcomp_init(AuraCtx *c, HWND top_level_hwnd)
{
    HRESULT hr;
    c->top_hwnd = top_level_hwnd;
    InitializeCriticalSection(&c->wlock);

    hr = D3D11CreateDevice(
        nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT,   /* required by ANGLE + DComp */
        nullptr, 0, D3D11_SDK_VERSION,
        &c->d3d11_dev, nullptr, &c->d3d11_ctx);
    if (FAILED(hr)) { AURA_LOG("D3D11CreateDevice failed: 0x%08lx", hr); return 0; }

    c->dcomp_dll = LoadLibraryW(L"dcomp.dll");
    if (!c->dcomp_dll) { AURA_LOG("dcomp.dll not found"); return 0; }
    PFN_DCompositionCreateDevice createDevice =
        (PFN_DCompositionCreateDevice)GetProcAddress(c->dcomp_dll,
                                                     "DCompositionCreateDevice");
    if (!createDevice) { AURA_LOG("DCompositionCreateDevice missing"); return 0; }

    IDXGIDevice *dxgi = nullptr;
    hr = c->d3d11_dev->QueryInterface(__uuidof(IDXGIDevice), (void **)&dxgi);
    if (FAILED(hr)) { AURA_LOG("QI IDXGIDevice failed: 0x%08lx", hr); return 0; }

    hr = createDevice(dxgi, AURA_IID_IDCompositionDevice,
                      (void **)&c->dcomp_dev);
    dxgi->Release();
    if (FAILED(hr)) { AURA_LOG("DCompositionCreateDevice failed: 0x%08lx", hr); return 0; }

    /* topmost = TRUE: our tree composites in FRONT of the HWND's legacy
     * (AWT-painted) content. We can't use WS_EX_NOREDIRECTIONBITMAP because
     * AWT created the window. */
    hr = c->dcomp_dev->CreateTargetForHwnd(c->top_hwnd, TRUE, &c->dcomp_target);
    if (FAILED(hr)) { AURA_LOG("CreateTargetForHwnd failed: 0x%08lx", hr); return 0; }

    c->dcomp_dev->CreateVisual(&c->root_visual);
    c->dcomp_dev->CreateVisual(&c->video_visual);
    c->dcomp_dev->CreateVisual(&c->ui_visual);
    if (!c->root_visual || !c->video_visual || !c->ui_visual) {
        AURA_LOG("CreateVisual failed");
        return 0;
    }

    /* z-order: video at the bottom, UI above it */
    c->root_visual->AddVisual(c->video_visual, FALSE, nullptr);
    c->root_visual->AddVisual(c->ui_visual, TRUE, c->video_visual);
    c->dcomp_target->SetRoot(c->root_visual);

    c->hdr_supported = detect_hdr_output(c);

    AURA_LOG("init ok (hwnd=%p)", (void *)c->top_hwnd);
    return 1;
}

static int create_video_swapchain(AuraCtx *c, int w, int h)
{
    HRESULT hr;
    IDXGIDevice   *dxgi    = nullptr;
    IDXGIAdapter  *adapter = nullptr;
    IDXGIFactory2 *factory = nullptr;

    c->d3d11_dev->QueryInterface(__uuidof(IDXGIDevice), (void **)&dxgi);
    dxgi->GetAdapter(&adapter);
    adapter->GetParent(__uuidof(IDXGIFactory2), (void **)&factory);

    DXGI_SWAP_CHAIN_DESC1 d;
    ZeroMemory(&d, sizeof(d));
    d.Width            = (UINT)w;
    d.Height           = (UINT)h;
    d.Format           = DXGI_FORMAT_B8G8R8A8_UNORM;
    d.SampleDesc.Count = 1;
    d.BufferUsage      = DXGI_USAGE_RENDER_TARGET_OUTPUT | DXGI_USAGE_SHADER_INPUT;
    d.BufferCount      = 2;
    d.SwapEffect       = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL; /* required for composition */
    d.AlphaMode        = DXGI_ALPHA_MODE_PREMULTIPLIED;           /* video layer is opaque */
    d.Scaling          = DXGI_SCALING_STRETCH;             /* required for composition */

    hr = factory->CreateSwapChainForComposition(c->d3d11_dev, &d, nullptr,
                                                &c->video_sc);
    factory->Release();
    adapter->Release();
    dxgi->Release();

    if (FAILED(hr)) {
        AURA_LOG("CreateSwapChainForComposition (video) failed: 0x%08lx", hr);
        return 0;
    }

    if (0 && SUCCEEDED(hr) && c->hdr_supported) {
        IDXGISwapChain3 *sc3 = nullptr;
        if (SUCCEEDED(c->video_sc->QueryInterface(__uuidof(IDXGISwapChain3), (void**)&sc3))) {
            /* scRGB: linear FP16, 709 primaries, values >1.0 = HDR headroom.
            * DWM converts to the display's native HDR signal. */
            HRESULT ch = sc3->SetColorSpace1(DXGI_COLOR_SPACE_RGB_FULL_G10_NONE_P709);
            c->hdr_active = SUCCEEDED(ch);
            sc3->Release();
            AURA_LOG("HDR swapchain: %s", c->hdr_active ? "scRGB active" : "SetColorSpace1 failed, SDR");
        }
    }

    c->video_visual->SetContent(c->video_sc);
    return 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mossip_auraplayer_engine_AuraDComp_uiAcquireBackbuffer(
        JNIEnv *env, jclass clazz, jlong ctx)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctx;
    if (!c || c->torn_down || !c->ui_sc || !c->ui_fence) return -1;
    UINT idx = c->ui_sc->GetCurrentBackBufferIndex();
    UINT64 needed = c->ui_buffer_fence_values[idx];
    if (c->ui_fence->GetCompletedValue() < needed) {
        c->ui_fence->SetEventOnCompletion(needed, c->ui_fence_event);
        WaitForSingleObject(c->ui_fence_event, INFINITE);
    }
    return (jint)idx;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_AuraDComp_uiBackbufferPtrAt(
        JNIEnv *env, jclass clazz, jlong ctx, jint idx)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctx;
    if (!c || c->torn_down || !c->ui_sc || idx < 0) return 0;
    ID3D12Resource *back = nullptr;
    if (FAILED(c->ui_sc->GetBuffer((UINT)idx,
            __uuidof(ID3D12Resource), (void **)&back)) || !back)
        return 0;
    back->Release();
    return (jlong)(intptr_t)back;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraDComp_uiPresent(
        JNIEnv *env, jclass clazz, jlong ctx)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctx;
    if (!c || c->torn_down || !c->ui_sc) return;
    UINT idx = c->ui_sc->GetCurrentBackBufferIndex();
    c->ui_sc->Present(1, 0);
    if (c->dcomp_dev) c->dcomp_dev->Commit();
    if (c->ui_fence && c->ui_queue) {
        c->ui_fence_value++;
        c->ui_queue->Signal(c->ui_fence, c->ui_fence_value);
        c->ui_buffer_fence_values[idx] = c->ui_fence_value;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_AuraDComp_uiAdapterPtr(
        JNIEnv *env, jclass clazz, jlong ctx)
{
    return (jlong)(intptr_t)((AuraCtx *)(intptr_t)ctx)->ui_adapter;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_AuraDComp_uiDevicePtr(
        JNIEnv *env, jclass clazz, jlong ctx)
{
    return (jlong)(intptr_t)((AuraCtx *)(intptr_t)ctx)->ui_dev;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mossip_auraplayer_engine_AuraDComp_uiQueuePtr(
        JNIEnv *env, jclass clazz, jlong ctx)
{
    return (jlong)(intptr_t)((AuraCtx *)(intptr_t)ctx)->ui_queue;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mossip_auraplayer_engine_AuraDComp_uiResizeSwapchain(
        JNIEnv *env, jclass clazz, jlong ctx, jint w, jint h)
{
    AuraCtx *c = (AuraCtx *)(intptr_t)ctx;
    if (!c || c->torn_down || !c->ui_sc || w <= 0 || h <= 0) return JNI_FALSE;

    /* GPU must be completely done with the old buffers first. */
    if (c->ui_fence && c->ui_queue) {
        c->ui_fence_value++;
        c->ui_queue->Signal(c->ui_fence, c->ui_fence_value);
        if (c->ui_fence->GetCompletedValue() < c->ui_fence_value) {
            c->ui_fence->SetEventOnCompletion(c->ui_fence_value, c->ui_fence_event);
            WaitForSingleObject(c->ui_fence_event, INFINITE);
        }
    }

    HRESULT hr = c->ui_sc->ResizeBuffers(0, (UINT)w, (UINT)h, DXGI_FORMAT_UNKNOWN, 0);
    if (FAILED(hr)) {
        AURA_LOG("ui ResizeBuffers failed 0x%08lx (stale buffer refs?)", hr);
        return JNI_FALSE;
    }
    c->ui_buffer_fence_values[0] = 0;
    c->ui_buffer_fence_values[1] = 0;
    return JNI_TRUE;
}

extern "C" int aura_dcomp_set_video_rect(AuraCtx *c, int x, int y, int w, int h)
{
    if (w <= 0 || h <= 0) {
        AURA_LOG("set_video_rect: called after teardown, ignoring");
        return 0;
    };
        c->video_x = x;
        c->video_y = y;

        /* First call ever: create the swapchain synchronously so a surface
         * exists before the render context is created. After that, resizes
         * are handed to the render thread. */
        if (!c->video_sc) {
            EnterCriticalSection(&c->wlock);
            if (!c->video_sc && create_video_swapchain(c, w, h)) {
                c->video_w = w; c->video_h = h;
                c->pending_w = w; c->pending_h = h;
                aura_angle_ensure_surface(c);
            }
            LeaveCriticalSection(&c->wlock);
        } else {
            InterlockedExchange(&c->pending_w, w);
            InterlockedExchange(&c->pending_h, h);
            if (c->render_wake) SetEvent(c->render_wake);   /* redraw at new size */
        }

        c->video_visual->SetOffsetX((float)x);
        c->video_visual->SetOffsetY((float)y);
        if (c->ui_visual) {
            c->ui_visual->SetOffsetX((float)x);
            c->ui_visual->SetOffsetY((float)y);
        }
        return 1;
}

extern "C" void aura_dcomp_attach_ui(AuraCtx *c, IDXGISwapChain3 *sc)
{
    if (!sc) return;
    if (c->ui_sc)
        c->ui_sc->Release();
    c->ui_sc = sc;
    sc->AddRef();
    HRESULT hr = c->ui_visual->SetContent(sc);
    if (FAILED(hr)) {
        AURA_LOG("ui SetContent FAILED: 0x%08lx", hr);
    } else {
        AURA_LOG("ui swapchain attached (%p)", (void *)sc);
    }
}

extern "C" void aura_dcomp_commit(AuraCtx *c)
{
    static int commit_n = 0;
    AURA_LOG("commit #%d — ui_sc=%p video_sc=%p", commit_n++,
                 (void *)c->ui_sc, (void *)c->video_sc);
    if (c->dcomp_dev)
        c->dcomp_dev->Commit();
}

/* Step-1 smoke test: fill the video swapchain teal and present it.
 * If the window turns teal after dcompInit + dcompSetVideoRect +
 * dcompDebugFill + dcompCommit, the whole tree works. Remove later. */
extern "C" void aura_dcomp_debug_fill(AuraCtx *c)
{
    if (!c->video_sc) { AURA_LOG("debug_fill: no video swapchain yet"); return; }
    EnterCriticalSection(&c->wlock);

    ID3D11Texture2D *back = nullptr;
    c->video_sc->GetBuffer(0, __uuidof(ID3D11Texture2D), (void **)&back);
    if (back) {
        ID3D11RenderTargetView *rtv = nullptr;
        c->d3d11_dev->CreateRenderTargetView(back, nullptr, &rtv);
        if (rtv) {
            const FLOAT teal[4] = { 0.11f, 0.62f, 0.46f, 1.0f };
            c->d3d11_ctx->ClearRenderTargetView(rtv, teal);
            rtv->Release();
        }
        back->Release();
        c->video_sc->Present(0, 0);
    }
    LeaveCriticalSection(&c->wlock);
    AURA_LOG("debug fill presented");
}

extern "C" void aura_dcomp_teardown(AuraCtx *c)
{
    if (c->torn_down) return;   /* second call = no-op, wlock is dead */
    c->torn_down = 1;

    /* Wait for the GPU to finish all UI frames before freeing anything. */
    if (c->ui_fence && c->ui_queue) {
        c->ui_fence_value++;
        c->ui_queue->Signal(c->ui_fence, c->ui_fence_value);
        if (c->ui_fence->GetCompletedValue() < c->ui_fence_value) {
            c->ui_fence->SetEventOnCompletion(c->ui_fence_value, c->ui_fence_event);
            WaitForSingleObject(c->ui_fence_event, INFINITE);
        }
    }

    EnterCriticalSection(&c->wlock);

    // 1. Clear layout linkages first to prevent hardware visual parsing
    if (c->dcomp_target) {
        c->dcomp_target->SetRoot(nullptr);
    }

    // 2. Clear visual layers sequentially
    if (c->ui_visual)    { c->ui_visual->Release();    c->ui_visual = nullptr; }
    if (c->video_visual) { c->video_visual->Release(); c->video_visual = nullptr; }
    if (c->root_visual)  { c->root_visual->Release();  c->root_visual = nullptr; }
    if (c->dcomp_target) { c->dcomp_target->Release(); c->dcomp_target = nullptr; }
    if (c->dcomp_dev)    { c->dcomp_dev->Release();    c->dcomp_dev = nullptr; }

    // 3. Release SwapChains safely
    if (c->ui_sc)        { c->ui_sc->Release();        c->ui_sc = nullptr; }
    if (c->video_sc)     { c->video_sc->Release();     c->video_sc = nullptr; }

    // 3b. Release OUR D3D12 UI objects (created in aura_ui_create_own_device)
    if (c->ui_fence)       { c->ui_fence->Release();     c->ui_fence = nullptr; }
    if (c->ui_fence_event) { CloseHandle(c->ui_fence_event); c->ui_fence_event = nullptr; }
    if (c->ui_queue)       { c->ui_queue->Release();     c->ui_queue = nullptr; }
    if (c->ui_dev)         { c->ui_dev->Release();       c->ui_dev = nullptr; }
    if (c->ui_adapter)     { c->ui_adapter->Release();   c->ui_adapter = nullptr; }
    c->ui_fence_value = 0;
    c->ui_buffer_fence_values[0] = 0;
    c->ui_buffer_fence_values[1] = 0;

    // 4. Tear down core D3D hardware layer last
    if (c->d3d11_ctx)    { c->d3d11_ctx->Release();    c->d3d11_ctx = nullptr; }
    if (c->d3d11_dev)    { c->d3d11_dev->Release();    c->d3d11_dev = nullptr; }

    LeaveCriticalSection(&c->wlock);

    // 5. Unload all companion binaries safely now that their object references are dead
    if (c->dcomp_dll) { FreeLibrary(c->dcomp_dll); c->dcomp_dll = nullptr; }

    AuraEgl *e = &c->egl;
    if (e->libGLESv2) { FreeLibrary(e->libGLESv2); e->libGLESv2 = nullptr; }
    if (e->libEGL)    { FreeLibrary(e->libEGL);    e->libEGL = nullptr; }

    // 6. AT THE VERY END: Delete the lock after nothing can ever query it again
    DeleteCriticalSection(&c->wlock);
    AURA_LOG("DirectComposition hardware pipeline torn down cleanly.");
}

#endif /* _WIN32 */