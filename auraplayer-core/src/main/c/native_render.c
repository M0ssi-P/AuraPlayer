#include <jni.h>
#include <jawt.h>
#include <mpv/client.h>
#include <stdlib.h>

// Platform-specific headers for JAWT
#ifdef _WIN32
    #include <jawt_md.h>
    #include <windows.h>
#elif __linux__
    #include <jawt_md.h>
    #include <X11/Xlib.h>
#elif __APPLE__
    #include <objc/runtime.h>
#endif

mpv_handle *mpv = NULL;

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_initializeNative(JNIEnv* env, jobject obj, jobject canvas, jboolean audioOnly) {
    if (mpv) return;

    mpv = mpv_create();
    if (!mpv) return;

    // --- GLOBAL CONFIGURATION (DASH, HLS, LIVE) ---
    mpv_set_property_string(mpv, "cache", "yes");
    mpv_set_option_string(mpv, "demuxer-max-bytes", "150M");
    mpv_set_option_string(mpv, "demuxer-max-back-bytes", "50M");
    mpv_set_option_string(mpv, "ytdl", "yes");
    mpv_set_option_string(mpv, "hls-bitrate", "max");
    mpv_set_option_string(mpv, "profile", "low-latency");
    mpv_set_option_string(mpv, "untied-cache", "yes");
    mpv_set_option_string(mpv, "enable-audio-visualizer", "yes");
    mpv_observe_property(mpv, 0, "audio-levels", MPV_FORMAT_NODE);

    if (audioOnly) {
        mpv_set_option_string(mpv, "video", "no");
        mpv_set_option_string(mpv, "audio-display", "attachment");
    } else {
        JAWT awt;
        awt.version = JAWT_VERSION_1_4;
        if (JAWT_GetAWT(env, &awt) != JNI_FALSE) {
            JAWT_DrawingSurface* ds = awt.GetDrawingSurface(env, canvas);
            if (ds && (ds->Lock(ds) & JAWT_LOCK_ERROR) == 0) {
                JAWT_DrawingSurfaceInfo* dsi = ds->GetDrawingSurfaceInfo(ds);
                if (dsi) {
                    int64_t wid = 0;
                    #ifdef _WIN32
                        JAWT_Win32DrawingSurfaceInfo* win_info = (JAWT_Win32DrawingSurfaceInfo*)dsi->platformInfo;
                        wid = (int64_t)win_info->hwnd;
                    #elif __linux__
                        JAWT_X11DrawingSurfaceInfo* x11_info = (JAWT_X11DrawingSurfaceInfo*)dsi->platformInfo;
                        wid = (int64_t)x11_info->drawable;
                    #elif __APPLE__
                        JAWT_MacOSXDrawingSurfaceInfo* mac_info = (JAWT_MacOSXDrawingSurfaceInfo*)dsi->platformInfo;
                        wid = (int64_t)mac_info->cocoaViewRef;
                    #endif

                    if (wid != 0) {
                        mpv_set_property(mpv, "wid", MPV_FORMAT_INT64, &wid);
                        mpv_set_option_string(mpv, "vo", "gpu");
                        mpv_set_option_string(mpv, "hwdec", "auto");
                    }
                    ds->FreeDrawingSurfaceInfo(dsi);
                }
                ds->Unlock(ds);
            }
            if(ds) awt.FreeDrawingSurface(ds);
        }
    }

    mpv_initialize(mpv);
}

// Playback functions remain the same for all OS
JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_loadFile(JNIEnv* env, jobject obj, jstring url) {
    const char *path = (*env)->GetStringUTFChars(env, url, NULL);
    const char *cmd[] = {"loadfile", path, NULL};
    mpv_command(mpv, cmd);
    (*env)->ReleaseStringUTFChars(env, url, path);
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setPause(JNIEnv* env, jobject obj, jboolean pause) {
    int flag = pause ? 1 : 0;
    mpv_set_property(mpv, "pause", MPV_FORMAT_FLAG, &flag);
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setPropertyDouble(JNIEnv* env, jobject obj, jstring name, jdouble value) {
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    mpv_set_property(mpv, prop, MPV_FORMAT_DOUBLE, &value);
    (*env)->ReleaseStringUTFChars(env, name, prop);
}

JNIEXPORT jdouble JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getPropertyDouble(JNIEnv* env, jobject obj, jstring name) {
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    double value = 0;
    mpv_get_property(mpv, prop, MPV_FORMAT_DOUBLE, &value);
    (*env)->ReleaseStringUTFChars(env, name, prop);
    return (jdouble)value;
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_terminateNative(JNIEnv* env, jobject obj) {
    if (mpv) {
        mpv_terminate_destroy(mpv);
        mpv = NULL;
    }
}

JNIEXPORT jdoubleArray JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getAudioLevels(JNIEnv* env, jobject obj) {
    double *levels = NULL;
    double left = 0, right = 0;
    // Note: 'peak' and 'rms' are sub-properties of audio-levels
    mpv_get_property(mpv, "audio-levels/0/peak", MPV_FORMAT_DOUBLE, &left);
    mpv_get_property(mpv, "audio-levels/1/peak", MPV_FORMAT_DOUBLE, &right);

    jdoubleArray result = (*env)->NewDoubleArray(env, 2);
    double fill[2] = {left, right};
    (*env)->SetDoubleArrayRegion(env, result, 0, 2, fill);
    return result;
}

JNIEXPORT jint JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getTrackCount(JNIEnv* env, jobject obj, jstring type) {
    const char *track_type = (*env)->GetStringUTFChars(env, type, NULL);
    char prop[64];
    snprintf(prop, sizeof(prop), "track-list/count");
    // Usually, we filter by type in Kotlin, but you can fetch total count here
    int64_t count = 0;
    mpv_get_property(mpv, "track-list/count", MPV_FORMAT_INT64, &count);
    (*env)->ReleaseStringUTFChars(env, type, track_type);
    return (jint)count;
}

// Select a track by ID (MPV uses IDs like 1, 2, 3 or 'no')
JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setTrack(JNIEnv* env, jobject obj, jstring type, jstring id) {
    const char *t_type = (*env)->GetStringUTFChars(env, type, NULL);
    const char *t_id = (*env)->GetStringUTFChars(env, id, NULL);
    mpv_set_property_string(mpv, t_type, t_id);
    (*env)->ReleaseStringUTFChars(env, type, t_type);
    (*env)->ReleaseStringUTFChars(env, id, t_id);
}

JNIEXPORT jint JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getPropertyInt(JNIEnv* env, jobject obj, jstring name) {
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    int64_t value = 0;
    mpv_get_property(mpv, prop, MPV_FORMAT_INT64, &value);
    (*env)->ReleaseStringUTFChars(env, name, prop);
    return (jint)value;
}