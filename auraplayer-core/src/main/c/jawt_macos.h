#ifndef AURA_NATIVE_PLAYER_H
#define AURA_NATIVE_PLAYER_H

#include <jni.h>
#include <stdint.h>

/*
 * Per-platform surface layer. Now fully per-instance: every AuraPlayer
 * owns one AuraSurface. No globals anywhere, so any number of players
 * can coexist in one process / one window.
 *
 * Implemented in exactly one of:
 *   native_surface_macos.m  (CAMetalLayer via JAWT -> gpu-context=macembed)
 *   native_surface_win.c    (HWND via JAWT -> plain wid embedding)
 *   native_surface_x11.c    (X11 Window via JAWT -> plain wid embedding)
 */

typedef struct AuraSurface AuraSurface; /* opaque, per-platform */

/*
 * Attach a native rendering target to the AWT canvas.
 * On success returns a new AuraSurface and writes the value to pass to
 * mpv as --wid into *wid_out. Returns NULL on failure.
 * Must be called on the AWT event dispatch thread with a displayable canvas.
 */
AuraSurface *aura_surface_attach(JNIEnv *env, jobject canvas, int64_t *wid_out);

/* w/h in AWT points; macOS converts to pixels internally. */
void aura_surface_resize(AuraSurface *s, int w, int h);

void aura_surface_set_visible(AuraSurface *s, int visible);

/*
 * Release the surface and free s. MUST be called only after the owning
 * mpv handle has been fully destroyed (mpv_terminate_destroy returned).
 */
void aura_surface_detach(AuraSurface *s);

/* Platform option strings ("macembed"/NULL, "videotoolbox"/"auto"). */
const char *aura_platform_gpu_context(void);
const char *aura_platform_hwdec(void);

#endif