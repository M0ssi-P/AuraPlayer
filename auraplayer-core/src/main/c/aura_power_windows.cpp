#ifdef _WIN32
#include <jni.h>
#include <windows.h>

/* ============================================================================
 * Sleep prevention — plain Win32, no WinRT involved. Fully functional.
 * ========================================================================= */

static bool g_sleepPrevented = false;

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setSleepPreventionNative(
    JNIEnv*, jobject, jboolean enable)
{
    if (enable && !g_sleepPrevented) {
        EXECUTION_STATE prev = SetThreadExecutionState(
            ES_CONTINUOUS | ES_DISPLAY_REQUIRED);
        g_sleepPrevented = (prev != 0);
    } else if (!enable && g_sleepPrevented) {
        SetThreadExecutionState(ES_CONTINUOUS);
        g_sleepPrevented = false;
    }
}

/* ============================================================================
 * Now Playing / SystemMediaTransportControls — STUBBED OUT.
 *
 * Real SMTC integration needs either:
 *   (a) C++/WinRT, which requires MSVC (cl.exe) + Windows SDK + cppwinrt.exe
 *       to generate the winrt/*.h projection headers — not available in this
 *       MinGW-only build environment, or
 *   (b) the raw WinRT ABI (windows.media.h) without C++/WinRT sugar, which
 *       hit a genuine bug in w64devkit's bundled headers (duplicate
 *       IReference<boolean>/IReference<BYTE> specializations — those types
 *       are both `unsigned char` on Windows, and the header generation
 *       didn't guard against the collision). Confirmed broken via a minimal
 *       repro; not something fixable from our side.
 *
 * Until one of those is worth the cost (installing VS Build Tools, or a
 * MinGW/header-version swap that might dodge the collision), these are
 * harmless no-ops so AuraPlayer.kt's calls compile and run safely with no
 * effect on Windows. macOS has full Now Playing functionality via
 * MPNowPlayingInfoCenter/MPRemoteCommandCenter — see aura_nowplaying_macos.m.
 *
 * To revisit: see the conversation/notes on Option A (MSVC + cppwinrt.exe)
 * vs Option B (raw ABI, blocked by the header bug above) before restarting
 * this work.
 * ========================================================================= */

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setupNowPlayingNative(
    JNIEnv*, jobject, jlong /*hwndPtr*/)
{
    /* no-op on Windows for now — see comment block above */
}

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_updateNowPlayingNative(
    JNIEnv*, jobject, jstring /*title*/, jdouble /*duration*/,
    jdouble /*position*/, jboolean /*isPlaying*/)
{
    /* no-op on Windows for now — see comment block above */
}

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_teardownNowPlayingNative(
    JNIEnv*, jobject)
{
    /* no-op on Windows for now — see comment block above */
}

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setNextEnabledNative(
    JNIEnv*, jobject, jboolean /*enabled*/)
{
    /* no-op on Windows for now — see comment block above */
}

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setPreviousEnabledNative(
    JNIEnv*, jobject, jboolean /*enabled*/)
{
    /* no-op on Windows for now — see comment block above */
}

#endif