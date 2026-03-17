#include <jni.h>
#include <jawt.h>
#include <mpv/client.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

// Platform-specific headers for JAWT
#ifdef _WIN32
    #include <jawt_md.h>
    #include <windows.h>
    #include <process.h>
#elif __linux__
    #include <jawt_md.h>
    #include <X11/Xlib.h>
    #include <pthread.h>
#elif __APPLE__
    #include <jawt_md.h>
    #include <objc/runtime.h>
    #include <pthread.h>
#endif

JavaVM* g_vm = NULL;
jobject g_obj = NULL;
mpv_handle *mpv = NULL;

#define STATE_PLAYING   0
#define STATE_PAUSED    1
#define STATE_STOPPED   2
#define STATE_IDLE      3
#define STATE_LOADING   4
#define STATE_SEEKING   5
#define STATE_BUFFERING 6

int get_internal_state() {
    if (!mpv) return STATE_STOPPED;

    int64_t paused = 0;
    int64_t core_idle = 0;
    int64_t seeking = 0;

    mpv_get_property(mpv, "pause", MPV_FORMAT_FLAG, &paused);
    mpv_get_property(mpv, "core-idle", MPV_FORMAT_FLAG, &core_idle);
    mpv_get_property(mpv, "seeking", MPV_FORMAT_FLAG, &seeking);

    if (seeking) return STATE_SEEKING;

    if (core_idle) {
        double duration = 0;
        mpv_get_property(mpv, "duration", MPV_FORMAT_DOUBLE, &duration);
        return (duration <= 0) ? STATE_LOADING : STATE_BUFFERING;
    }

    return paused ? STATE_PAUSED : STATE_PLAYING;
}

#ifdef _WIN32
unsigned __stdcall event_loop(void* arg) {
#else
void* event_loop(void* arg) {
#endif
    if (g_vm == NULL || g_obj == NULL) goto thread_exit;

    JNIEnv* env;
    if ((*g_vm)->AttachCurrentThread(g_vm, (void**)&env, NULL) != 0) goto thread_exit;

    jclass cls = (*env)->GetObjectClass(env, g_obj);
    jmethodID onStateChanged = (*env)->GetMethodID(env, cls, "onNativeStateChange", "(I)V");
    jmethodID onTimeChanged = (*env)->GetMethodID(env, cls, "onNativeTimeChange", "(D)V");
    jmethodID onDurationChanged = (*env)->GetMethodID(env, cls, "onNativeDurationChange", "(D)V");
    jmethodID onTracksChanged = (*env)->GetMethodID(env, cls, "onNativeTracksChanged", "()V");
    jmethodID onBufferChanged = (*env)->GetMethodID(env, cls, "onNativeBufferChange", "(D)V");
    jmethodID onVolumeChanged = (*env)->GetMethodID(env, cls, "onNativeVolumeChange", "(D)V");
    jmethodID onMuteChanged = (*env)->GetMethodID(env, cls, "onNativeMuteChange", "(Z)V");
    jmethodID onSpeedChanged = (*env)->GetMethodID(env, cls, "onNativeSpeedChange", "(D)V");

    if (!onStateChanged || !onTimeChanged || !onDurationChanged || !onTracksChanged) {
        fprintf(stderr, "[AuraPlayer] Error: Could not find Kotlin callback methods!\n");
        goto detach_exit;
    }

    while (mpv) {
        mpv_event *event = mpv_wait_event(mpv, -1);
        if (!mpv || event->event_id == MPV_EVENT_SHUTDOWN) break;

        switch (event->event_id) {
            case MPV_EVENT_FILE_LOADED:
                (*env)->CallVoidMethod(env, g_obj, onTracksChanged);
                break;

            case MPV_EVENT_PROPERTY_CHANGE: {
                mpv_event_property *prop = (mpv_event_property *)event->data;

                if (strcmp(prop->name, "time-pos") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    double val = *(double *)prop->data;
                    (*env)->CallVoidMethod(env, g_obj, onTimeChanged, val);
                }
                else if (strcmp(prop->name, "volume") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    double val = *(double *)prop->data;
                    (*env)->CallVoidMethod(env, g_obj, onVolumeChanged, val);
                }
                else if (strcmp(prop->name, "mute") == 0 && prop->format == MPV_FORMAT_FLAG) {
                    int val = *(int *)prop->data;
                    (*env)->CallVoidMethod(env, g_obj, onMuteChanged, (jboolean)val);
                }
                else if (strcmp(prop->name, "speed") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    double val = *(double *)prop->data;
                    (*env)->CallVoidMethod(env, g_obj, onSpeedChanged, val);
                }
                else if (strcmp(prop->name, "demuxer-cache-duration") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    double val = *(double *)prop->data;
                    (*env)->CallVoidMethod(env, g_obj, onBufferChanged, val);
                }
                else if (strcmp(prop->name, "duration") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    double val = *(double *)prop->data;
                    (*env)->CallVoidMethod(env, g_obj, onDurationChanged, val);
                }
                else {
                    int currentState = get_internal_state();
                    (*env)->CallVoidMethod(env, g_obj, onStateChanged, currentState);
                }
                break;
            }

            case MPV_EVENT_END_FILE:
                (*env)->CallVoidMethod(env, g_obj, onStateChanged, (jint)STATE_IDLE);
                break;
        }
    }

detach_exit:
    (*g_vm)->DetachCurrentThread(g_vm);
thread_exit:
    #ifdef _WIN32
    return 0;
    #else
    return NULL;
    #endif
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_initializeNative(JNIEnv* env, jobject obj, jobject canvas, jboolean audioOnly) {
    if (mpv) return;

    (*env)->GetJavaVM(env, &g_vm);
    g_obj = (*env)->NewGlobalRef(env, obj);

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
    mpv_set_option_string(mpv, "video-unscaled", "no");
    mpv_set_option_string(mpv, "keepaspect", "yes");
    mpv_set_option_string(mpv, "hidpi-window-scale", "yes");
    mpv_set_option_string(mpv, "enable-audio-visualizer", "yes");
    mpv_set_option_string(mpv, "keep-open", "yes");
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
                        void* view = dsi->platformInfo;
                        wid = (int64_t)view;
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

    mpv_observe_property(mpv, 0, "time-pos", MPV_FORMAT_DOUBLE);
    mpv_observe_property(mpv, 0, "duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(mpv, 0, "pause", MPV_FORMAT_FLAG);
    mpv_observe_property(mpv, 0, "core-idle", MPV_FORMAT_FLAG);
    mpv_observe_property(mpv, 0, "seeking", MPV_FORMAT_FLAG);
    mpv_observe_property(mpv, 0, "demuxer-cache-duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(mpv, 0, "volume", MPV_FORMAT_DOUBLE);
    mpv_observe_property(mpv, 0, "mute", MPV_FORMAT_FLAG);
    mpv_observe_property(mpv, 0, "speed", MPV_FORMAT_DOUBLE);
    mpv_initialize(mpv);

    #ifdef _WIN32
        uintptr_t thread = _beginthreadex(NULL, 0, (unsigned (__stdcall *)(void *))event_loop, NULL, 0, NULL);
    #else
        pthread_t thread;
        pthread_create(&thread, NULL, event_loop, NULL);
        pthread_detach(thread);
    #endif
}

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

JNIEXPORT jdouble JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getBufferDuration(JNIEnv* env, jobject obj) {
    double duration = 0;
    mpv_get_property(mpv, "demuxer-cache-duration", MPV_FORMAT_DOUBLE, &duration);
    return (jdouble)duration;
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

    if (g_obj) {
        (*env)->DeleteGlobalRef(env, g_obj);
        g_obj = NULL;
    }
}

JNIEXPORT jobjectArray JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getNativeTracks(JNIEnv* env, jobject obj) {
    mpv_node node;
    if (mpv_get_property(mpv, "track-list", MPV_FORMAT_NODE, &node) < 0) return NULL;

    if (node.format != MPV_FORMAT_NODE_ARRAY) {
        mpv_free_node_contents(&node);
        return NULL;
    }

    jclass trackClass = (*env)->FindClass(env, "com/mossip/auraplayer/engine/MediaTrack");
    jmethodID constructor = (*env)->GetMethodID(env, trackClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)V");

    int count = node.u.list->num;
    jobjectArray trackArray = (*env)->NewObjectArray(env, count, trackClass, NULL);

    for (int i = 0; i < count; i++) {
        mpv_node item = node.u.list->values[i];
        if (item.format != MPV_FORMAT_NODE_MAP) continue;

        const char *raw_id = NULL, *raw_type = NULL, *raw_title = NULL, *raw_lang = NULL;
        int width = 0, height = 0;

        for (int j = 0; j < item.u.list->num; j++) {
            char *key = item.u.list->keys[j];
            mpv_node val = item.u.list->values[j];

            if (val.format == MPV_FORMAT_STRING) {
                if (strcmp(key, "id") == 0) raw_id = val.u.string;
                else if (strcmp(key, "type") == 0) raw_type = val.u.string;
                else if (strcmp(key, "title") == 0) raw_title = val.u.string;
                else if (strcmp(key, "lang") == 0) raw_lang = val.u.string;
            } else if (val.format == MPV_FORMAT_INT64) {
                if (strcmp(key, "demux-w") == 0) width = (int)val.u.int64;
                else if (strcmp(key, "demux-h") == 0) height = (int)val.u.int64;
            }
        }

        jstring jId    = (*env)->NewStringUTF(env, raw_id ? raw_id : "");
        jstring jType  = (*env)->NewStringUTF(env, raw_type ? raw_type : "");
        jstring jTitle = (*env)->NewStringUTF(env, raw_title ? raw_title : "");
        jstring jLang  = (*env)->NewStringUTF(env, raw_lang ? raw_lang : "");

        jobject trackObj = (*env)->NewObject(env, trackClass, constructor,
            jId,
            jType,
            jTitle,
            jLang,
            width,
            height
        );

        if (trackObj) {
            (*env)->SetObjectArrayElement(env, trackArray, i, trackObj);
            (*env)->DeleteLocalRef(env, trackObj);
        }

        (*env)->DeleteLocalRef(env, jId);
        (*env)->DeleteLocalRef(env, jType);
        (*env)->DeleteLocalRef(env, jTitle);
        (*env)->DeleteLocalRef(env, jLang);
    }

    mpv_free_node_contents(&node);
    return trackArray;
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

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setMute(JNIEnv* env, jobject obj, jboolean mute) {
    int val = mute ? 1 : 0;
    mpv_set_property(mpv, "mute", MPV_FORMAT_FLAG, &val);
}

JNIEXPORT jint JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getPropertyInt(JNIEnv* env, jobject obj, jstring name) {
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    int64_t value = 0;
    mpv_get_property(mpv, prop, MPV_FORMAT_INT64, &value);
    (*env)->ReleaseStringUTFChars(env, name, prop);
    return (jint)value;
}