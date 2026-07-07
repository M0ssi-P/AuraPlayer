#ifndef AURA_NATIVE_PLAYER_H
#define AURA_NATIVE_PLAYER_H

#include <jni.h>
#include <stdint.h>

/*
 * Per-platform surface layer, implemented in exactly one of:
 *   native_surface_macos.m  (CAMetalLayer via JAWT_SurfaceLayers -> gpu-context=macembed)
 *   native_surface_win.c    (HWND via JAWT -> plain wid embedding)
 *   native_surface_x11.c    (X11 Window via JAWT -> plain wid embedding)
 *
 * The portable core (native_player.c) never touches Vulkan, Metal, or
 * window-system APIs directly. mpv owns the entire rendering pipeline;
 * these functions only produce/maintain the native handle passed as --wid.
 */

/*
 * Attach a native rendering target to the AWT canvas and return the value
 * to pass to mpv as --wid:
 *   macOS   -> pointer to a retained CAMetalLayer attached via JAWT_SurfaceLayers
 *   Windows -> the canvas's HWND
 *   Linux   -> the canvas's X11 Window id
 *
 * Must be called on the AWT event dispatch thread with a displayable,
 * non-zero-sized canvas. Returns 0 on failure.
 */
int64_t aura_surface_attach(JNIEnv *env, jobject canvas);

/*
 * Notify the surface layer that the canvas was resized. w/h are in AWT
 * points; the macOS implementation converts to pixels via contentsScale.
 * No-op on Windows/X11 (mpv tracks the parent window size itself).
 */
void aura_surface_resize(int w, int h);

/* Show/hide the native surface (macOS: layer.hidden; others: no-op). */
void aura_surface_set_visible(int visible);

/*
 * Release the native surface. MUST be called only after
 * mpv_terminate_destroy() has returned -- mpv's VO holds a reference to
 * the layer/window until then.
 */
void aura_surface_detach(void);

/*
 * Platform-specific mpv options.
 * gpu_context: "macembed" on macOS (the custom context patched into your
 *              libmpv build); NULL elsewhere (mpv auto-probes winvk/x11vk).
 * hwdec:       "videotoolbox" on macOS (MoltenVK has no Vulkan video-decode
 *              extensions); "auto" elsewhere.
 */
const char *aura_platform_gpu_context(void);
const char *aura_platform_hwdec(void);

#endif /* AURA_NATIVE_PLAYER_H */