/* dcomp_ui_test.cpp — Path A, Step 1 (PROOF-OF-CONCEPT, pure D3D, no Skia).
 *
 * Goal of this step ONLY: prove that our DComp tree can hold a SECOND
 * swapchain (premultiplied alpha) in ui_visual, composited OVER the video
 * visual. We fill it with a translucent color via plain D3D11 — no Skia,
 * no Compose, no reflection. If a see-through tint appears over the video,
 * the two-layer foundation works and Path A is viable.
 *
 * Once this is proven, we replace the "clear to translucent color" body
 * with "wrap backbuffer as Skia surface + let Compose render into it".
 *
 * Compile as C++ alongside dcomp_win.cpp. These functions assume aura_dcomp_init
 * already ran (d3d11_dev, dcomp_dev, ui_visual all exist).
 */
#ifdef _WIN32

#include <windows.h>
#include <d3d11.h>
#include <dxgi1_4.h>
#include <d3d12.h>
#include <dcomp.h>
#include <stdio.h>
#include "aura_ctx.h"

#define ULOG(...) do { fprintf(stderr, "[aura-uitest] " __VA_ARGS__); \
                       fprintf(stderr, "\n"); fflush(stderr); } while (0)

/* Create the UI composition swapchain — PREMULTIPLIED alpha (the whole
 * point: cleared/transparent pixels let the video show through). Mirror of
 * create_video_swapchain but alpha-capable. Stored in c->ui_sc. */
extern "C" int aura_ui_create_swapchain(AuraCtx *c, int w, int h)
{
    if (w <= 0 || h <= 0) return 0;
    if (c->ui_sc) return 1;   /* already made */

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
    d.Format           = DXGI_FORMAT_B8G8R8A8_UNORM;   /* what Skia/Compose expect on Win */
    d.SampleDesc.Count = 1;
    d.BufferUsage      = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    d.BufferCount      = 2;
    d.SwapEffect       = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
    d.AlphaMode        = DXGI_ALPHA_MODE_PREMULTIPLIED;  /* <-- the difference from video */
    d.Scaling          = DXGI_SCALING_STRETCH;

    IDXGISwapChain1 *sc1 = nullptr;
    hr = factory->CreateSwapChainForComposition(c->d3d11_dev, &d, nullptr, &sc1);
    factory->Release(); adapter->Release(); dxgi->Release();

    if (FAILED(hr) || !sc1) {
        ULOG("CreateSwapChainForComposition (ui) failed: 0x%08lx", hr);
        return 0;
    }

    hr = sc1->QueryInterface(__uuidof(IDXGISwapChain3), (void **)&c->ui_sc);
    sc1->Release();
    if (FAILED(hr)) { ULOG("QI IDXGISwapChain3 failed: 0x%08lx", hr); return 0; }

    /* Put it in the UI visual (above the video visual, per the tree built
     * in aura_dcomp_init). */
    c->ui_visual->SetContent(c->ui_sc);
    c->dcomp_dev->Commit();
    ULOG("ui swapchain created %dx%d (premultiplied), attached to ui_visual", w, h);
    return 1;
}

static int aura_ui_create_own_device(AuraCtx *c)
{
    if (c->ui_dev) return 1;

    IDXGIFactory4 *f = nullptr;
    if (FAILED(CreateDXGIFactory2(0, __uuidof(IDXGIFactory4), (void **)&f))) {
        ULOG("CreateDXGIFactory2 (ui device) failed");
        return 0;
    }

    IDXGIAdapter1 *ad = nullptr;
    for (UINT i = 0; f->EnumAdapters1(i, &ad) != DXGI_ERROR_NOT_FOUND; ++i) {
        DXGI_ADAPTER_DESC1 desc;
        ad->GetDesc1(&desc);
        if (!(desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) &&
            SUCCEEDED(D3D12CreateDevice(ad, D3D_FEATURE_LEVEL_11_0,
                                        __uuidof(ID3D12Device),
                                        (void **)&c->ui_dev)))
            break;
        ad->Release();
        ad = nullptr;
    }
    f->Release();
    if (!c->ui_dev) { ULOG("no hardware D3D12 device for UI"); return 0; }
    c->ui_adapter = ad;

    D3D12_COMMAND_QUEUE_DESC qd = {};
    qd.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    if (FAILED(c->ui_dev->CreateCommandQueue(&qd,
            __uuidof(ID3D12CommandQueue), (void **)&c->ui_queue))) {
        ULOG("CreateCommandQueue (ui) failed");
        return 0;
    }
    ULOG("UI D3D12 device + queue created (our own, not Skiko's)");
    return 1;
}

extern "C" int aura_ui_create_swapchain_d3d12(AuraCtx *c, int w, int h)
{
    if (w <= 0 || h <= 0) return 0;
    if (c->ui_sc) return 1;

    if (!aura_ui_create_own_device(c)) return 0;

    IDXGIFactory4 *factory = nullptr;
    HRESULT hr = CreateDXGIFactory2(0, __uuidof(IDXGIFactory4), (void **)&factory);
    if (FAILED(hr)) { ULOG("CreateDXGIFactory2 failed 0x%08lx", hr); return 0; }

    DXGI_SWAP_CHAIN_DESC1 d;
    ZeroMemory(&d, sizeof(d));
    d.Width            = (UINT)w;
    d.Height           = (UINT)h;
    d.Format           = DXGI_FORMAT_B8G8R8A8_UNORM;
    d.SampleDesc.Count = 1;
    d.BufferUsage      = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    d.BufferCount      = 2;
    d.SwapEffect       = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
    d.AlphaMode        = DXGI_ALPHA_MODE_PREMULTIPLIED;
    d.Scaling          = DXGI_SCALING_STRETCH;

    IDXGISwapChain1 *sc1 = nullptr;
    hr = factory->CreateSwapChainForComposition((IUnknown *)c->ui_queue, &d, nullptr, &sc1);
    factory->Release();
    if (FAILED(hr) || !sc1) {
        ULOG("CreateSwapChainForComposition(queue) failed 0x%08lx", hr);
        return 0;
    }

    hr = sc1->QueryInterface(__uuidof(IDXGISwapChain3), (void **)&c->ui_sc);
    sc1->Release();
    if (FAILED(hr)) { ULOG("QI SwapChain3 failed 0x%08lx", hr); return 0; }

    hr = c->ui_dev->CreateFence(0, D3D12_FENCE_FLAG_NONE,
                                __uuidof(ID3D12Fence), (void **)&c->ui_fence);
    if (FAILED(hr)) { ULOG("CreateFence failed 0x%08lx", hr); return 0; }
    c->ui_fence_event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    c->ui_fence_value = 0;
    c->ui_buffer_fence_values[0] = 0;
    c->ui_buffer_fence_values[1] = 0;

    hr = c->ui_visual->SetContent(c->ui_sc);
    if (FAILED(hr)) { ULOG("ui SetContent failed 0x%08lx", hr); return 0; }
    c->dcomp_dev->Commit();
    ULOG("D3D12 UI swapchain created %dx%d on OUR queue, attached OK", w, h);
    return 1;
}

extern "C" void aura_ui_test_fill(AuraCtx *c)
{
    if (!c->ui_sc) { ULOG("test_fill: no ui swapchain"); return; }
        EnterCriticalSection(&c->wlock);

        ID3D11Texture2D *back = nullptr;
        HRESULT hr = c->ui_sc->GetBuffer(0, __uuidof(ID3D11Texture2D), (void **)&back);
        if (FAILED(hr) || !back) {
            ULOG("test_fill: GetBuffer failed 0x%08lx", hr);   // <-- log the HRESULT
            LeaveCriticalSection(&c->wlock);
            return;
        }

    ID3D11RenderTargetView *rtv = nullptr;
    c->d3d11_dev->CreateRenderTargetView(back, nullptr, &rtv);
    if (rtv) {
        /* premultiplied 50% red: RGB already multiplied by A=0.5 */
        const FLOAT premul_red[4] = { 0.5f, 0.0f, 0.0f, 0.5f };
        c->d3d11_ctx->ClearRenderTargetView(rtv, premul_red);
        rtv->Release();
    }
    back->Release();

    c->ui_sc->Present(0, 0);
    c->dcomp_dev->Commit();
    ULOG("test fill presented (translucent red) — video should show through");
}

#endif /* _WIN32 */