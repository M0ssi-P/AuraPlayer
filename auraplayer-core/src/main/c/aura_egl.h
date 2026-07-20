/* aura_egl.h — self-contained EGL types + constants for runtime loading.
 * Replaces <EGL/egl.h> and <EGL/eglext.h> so no ANGLE SDK is needed at
 * build time. Values are from the Khronos EGL registry and are stable.
 *
 * In aura_ctx.h, replace  #include <EGL/egl.h>  with  #include "aura_egl.h"
 */
#ifndef AURA_EGL_H
#define AURA_EGL_H

#ifdef _WIN32
#include <stdint.h>
#include <windows.h>

/* ---- types ---- */
typedef void    *EGLDisplay;
typedef void    *EGLConfig;
typedef void    *EGLContext;
typedef void    *EGLSurface;
typedef void    *EGLClientBuffer;
typedef void    *EGLDeviceEXT;
typedef int32_t  EGLint;
typedef unsigned EGLBoolean;
typedef unsigned EGLenum;
typedef intptr_t EGLAttrib;

#define EGL_NO_DISPLAY ((EGLDisplay)0)
#define EGL_NO_CONTEXT ((EGLContext)0)
#define EGL_NO_SURFACE ((EGLSurface)0)

/* ---- core constants ---- */
#define EGL_NONE                    0x3038
#define EGL_TRUE                    1
#define EGL_FALSE                   0
#define EGL_ALPHA_SIZE              0x3021
#define EGL_BLUE_SIZE               0x3022
#define EGL_GREEN_SIZE              0x3023
#define EGL_RED_SIZE                0x3024
#define EGL_SURFACE_TYPE            0x3033
#define EGL_PBUFFER_BIT             0x0001
#define EGL_RENDERABLE_TYPE         0x3040
#define EGL_OPENGL_ES2_BIT          0x0004
#define EGL_CONTEXT_CLIENT_VERSION  0x3098
#define EGL_HEIGHT                  0x3056
#define EGL_WIDTH                   0x3057

/* ---- ANGLE extension constants ---- */
#define EGL_PLATFORM_DEVICE_EXT     0x313F
#define EGL_D3D11_DEVICE_ANGLE      0x33A1
#define EGL_D3D_TEXTURE_ANGLE       0x33A3

/* ---- function pointer types ---- */
typedef void *(__stdcall *PFN_eglGetProcAddress)(const char *name);
typedef EGLint (__stdcall *PFN_eglGetError)(void);
typedef EGLBoolean (__stdcall *PFN_eglInitialize)(EGLDisplay dpy,
    EGLint *major, EGLint *minor);
typedef EGLBoolean (__stdcall *PFN_eglTerminate)(EGLDisplay dpy);
typedef EGLBoolean (__stdcall *PFN_eglChooseConfig)(EGLDisplay dpy,
    const EGLint *attribs, EGLConfig *configs, EGLint size, EGLint *num);
typedef EGLContext (__stdcall *PFN_eglCreateContext)(EGLDisplay dpy,
    EGLConfig cfg, EGLContext share, const EGLint *attribs);
typedef EGLBoolean (__stdcall *PFN_eglDestroyContext)(EGLDisplay dpy,
    EGLContext ctx);
typedef EGLBoolean (__stdcall *PFN_eglMakeCurrent)(EGLDisplay dpy,
    EGLSurface draw, EGLSurface read, EGLContext ctx);
typedef EGLSurface (__stdcall *PFN_eglCreatePbufferSurface)(EGLDisplay dpy,
    EGLConfig cfg, const EGLint *attribs);
typedef EGLSurface (__stdcall *PFN_eglCreatePbufferFromClientBuffer)(
    EGLDisplay dpy, EGLenum buftype, EGLClientBuffer buffer,
    EGLConfig cfg, const EGLint *attribs);
typedef EGLBoolean (__stdcall *PFN_eglDestroySurface)(EGLDisplay dpy,
    EGLSurface surf);
/* extensions (via eglGetProcAddress) */
typedef EGLDeviceEXT (__stdcall *PFN_eglCreateDeviceANGLE)(
    EGLint device_type, void *native_device, const EGLAttrib *attribs);
typedef EGLDisplay (__stdcall *PFN_eglGetPlatformDisplayEXT)(
    EGLenum platform, void *native_display, const EGLint *attribs);
/* from libGLESv2.dll */
typedef void (__stdcall *PFN_glFinish)(void);

/* ---- runtime-loaded function table ---- */
typedef struct AuraEgl {
    HMODULE libEGL;
    HMODULE libGLESv2;

    PFN_eglGetProcAddress               GetProcAddress_;
    PFN_eglGetError                     GetError;
    PFN_eglInitialize                   Initialize;
    PFN_eglTerminate                    Terminate;
    PFN_eglChooseConfig                 ChooseConfig;
    PFN_eglCreateContext                CreateContext;
    PFN_eglDestroyContext               DestroyContext;
    PFN_eglMakeCurrent                  MakeCurrent;
    PFN_eglCreatePbufferSurface         CreatePbufferSurface;
    PFN_eglCreatePbufferFromClientBuffer CreatePbufferFromClientBuffer;
    PFN_eglDestroySurface               DestroySurface;

    PFN_eglCreateDeviceANGLE            CreateDeviceANGLE;
    PFN_eglGetPlatformDisplayEXT        GetPlatformDisplayEXT;

    PFN_glFinish                        glFinish;
} AuraEgl;

#endif /* _WIN32 */
#endif /* AURA_EGL_H */