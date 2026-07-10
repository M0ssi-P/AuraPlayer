/*
 * AuraPlayer portable JNI core -- multi-instance.
 *
 * Every AuraPlayer instance owns one AuraCtx (returned to Kotlin as a
 * jlong handle). No global mpv state: create as many players as you like,
 * each with its own mpv_handle, event thread, surface, and callbacks.
 *
 * Lifecycle contract (mirrored in AuraPlayer.kt):
 *   createNative()                 -> handle          (mpv_create, options later)
 *   initializeNative(h, canvas, audioOnly)            (surface + mpv_initialize + thread)
 *   ... playback calls (h, ...) ...
 *   terminateNative(h)             -> quit, join thread, destroy, free
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <mpv/client.h>

#ifdef _WIN32
    #include <windows.h>
    #include <process.h>
#else
    #include <pthread.h>
#endif

#include "jawt_macos.h"

/* ---------------------------------------------------------------------- */
/* Per-instance context                                                    */
/* ---------------------------------------------------------------------- */

typedef struct AuraCtx {
    mpv_handle  *mpv;
    jobject      jself;     /* GlobalRef to the Kotlin AuraPlayer instance */
    AuraSurface *surface;   /* NULL in audio-only mode or before init */
    int64_t      wid;
#ifdef _WIN32
    HANDLE       thread;
#else
    pthread_t    thread;
    int          thread_started;
#endif
} AuraCtx;

/* One JavaVM per process -- the only global, and a legitimate one. */
static JavaVM *g_vm = NULL;

static AuraCtx *ctx_of(jlong handle)
{
    return (AuraCtx *)(intptr_t)handle;
}

/* State codes mirrored in AuraPlayer.kt */
#define STATE_PLAYING   0
#define STATE_PAUSED    1
#define STATE_STOPPED   2
#define STATE_IDLE      3
#define STATE_LOADING   4
#define STATE_SEEKING   5
#define STATE_BUFFERING 6

static int get_internal_state(AuraCtx *c)
{
    if (!c || !c->mpv) return STATE_STOPPED;

    int64_t paused = 0, core_idle = 0, seeking = 0;
    mpv_get_property(c->mpv, "pause",     MPV_FORMAT_FLAG, &paused);
    mpv_get_property(c->mpv, "core-idle", MPV_FORMAT_FLAG, &core_idle);
    mpv_get_property(c->mpv, "seeking",   MPV_FORMAT_FLAG, &seeking);

    if (seeking) return STATE_SEEKING;

    if (core_idle) {
        double duration = 0;
        mpv_get_property(c->mpv, "duration", MPV_FORMAT_DOUBLE, &duration);
        return (duration <= 0) ? STATE_LOADING : STATE_BUFFERING;
    }

    return paused ? STATE_PAUSED : STATE_PLAYING;
}

/* ---------------------------------------------------------------------- */
/* Per-instance mpv event loop                                             */
/* ---------------------------------------------------------------------- */

#ifdef _WIN32
static unsigned __stdcall event_loop(void *arg) {
#define THREAD_RETURN 0
#else
static void *event_loop(void *arg) {
#define THREAD_RETURN NULL
#endif
    AuraCtx *c = (AuraCtx *)arg;
    if (!g_vm || !c || !c->jself) goto thread_exit;

    JNIEnv *env;
    if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != 0)
        goto thread_exit;

    jclass cls = (*env)->GetObjectClass(env, c->jself);
    jmethodID onStateChanged    = (*env)->GetMethodID(env, cls, "onNativeStateChange",    "(I)V");
    jmethodID onTimeChanged     = (*env)->GetMethodID(env, cls, "onNativeTimeChange",     "(D)V");
    jmethodID onDurationChanged = (*env)->GetMethodID(env, cls, "onNativeDurationChange", "(D)V");
    jmethodID onTracksChanged   = (*env)->GetMethodID(env, cls, "onNativeTracksChanged",  "()V");
    jmethodID onBufferChanged   = (*env)->GetMethodID(env, cls, "onNativeBufferChange",   "(D)V");
    jmethodID onVolumeChanged   = (*env)->GetMethodID(env, cls, "onNativeVolumeChange",   "(D)V");
    jmethodID onMuteChanged     = (*env)->GetMethodID(env, cls, "onNativeMuteChange",     "(Z)V");
    jmethodID onSpeedChanged    = (*env)->GetMethodID(env, cls, "onNativeSpeedChange",    "(D)V");

    if (!onStateChanged || !onTimeChanged || !onDurationChanged || !onTracksChanged) {
        fprintf(stderr, "[AuraPlayer] Error: Could not find Kotlin callback methods!\n");
        goto detach_exit;
    }

    for (;;) {
        mpv_event *event = mpv_wait_event(c->mpv, -1);
        if (event->event_id == MPV_EVENT_SHUTDOWN)
            break;  /* "quit" was processed; terminateNative will destroy */

        switch (event->event_id) {
            case MPV_EVENT_FILE_LOADED:
                (*env)->CallVoidMethod(env, c->jself, onTracksChanged);
                break;

            case MPV_EVENT_PROPERTY_CHANGE: {
                mpv_event_property *prop = (mpv_event_property *)event->data;

                if (strcmp(prop->name, "time-pos") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    (*env)->CallVoidMethod(env, c->jself, onTimeChanged, *(double *)prop->data);
                } else if (strcmp(prop->name, "volume") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    (*env)->CallVoidMethod(env, c->jself, onVolumeChanged, *(double *)prop->data);
                } else if (strcmp(prop->name, "mute") == 0 && prop->format == MPV_FORMAT_FLAG) {
                    (*env)->CallVoidMethod(env, c->jself, onMuteChanged,
                                           (jboolean)(*(int *)prop->data));
                } else if (strcmp(prop->name, "speed") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    (*env)->CallVoidMethod(env, c->jself, onSpeedChanged, *(double *)prop->data);
                } else if (strcmp(prop->name, "demuxer-cache-duration") == 0 &&
                           prop->format == MPV_FORMAT_DOUBLE) {
                    (*env)->CallVoidMethod(env, c->jself, onBufferChanged, *(double *)prop->data);
                } else if (strcmp(prop->name, "duration") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    (*env)->CallVoidMethod(env, c->jself, onDurationChanged, *(double *)prop->data);
                } else {
                    (*env)->CallVoidMethod(env, c->jself, onStateChanged,
                                           (jint)get_internal_state(c));
                }
                break;
            }

            case MPV_EVENT_LOG_MESSAGE: {
                mpv_event_log_message *msg = (mpv_event_log_message *)event->data;
                fprintf(stderr, "[mpv/%s] %s: %s", msg->level, msg->prefix, msg->text);
                break;
            }

            case MPV_EVENT_END_FILE:
                (*env)->CallVoidMethod(env, c->jself, onStateChanged, (jint)STATE_IDLE);
                break;

            default:
                break;
        }
    }

detach_exit:
    (*g_vm)->DetachCurrentThread(g_vm);
thread_exit:
    return THREAD_RETURN;
}

/* ---------------------------------------------------------------------- */
/* JNI: lifecycle                                                          */
/* ---------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_createNative(
    JNIEnv *env, jobject thisObj)
{
    if (!g_vm)
        (*env)->GetJavaVM(env, &g_vm);

    AuraCtx *c = (AuraCtx *)calloc(1, sizeof(AuraCtx));
    if (!c) return 0;

    c->jself = (*env)->NewGlobalRef(env, thisObj);
    c->mpv = mpv_create();
    if (!c->mpv) {
        (*env)->DeleteGlobalRef(env, c->jself);
        free(c);
        return 0;
    }
    fprintf(stderr, "[C] createNative -> ctx=%p\n", (void*)c);
    fflush(stderr);
    return (jlong)(intptr_t)c;
}

JNIEXPORT jlong JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_initializeNative(
    JNIEnv *env, jobject obj, jlong handle, jobject canvas, jboolean audioOnly)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return 0;

    int64_t wid = 0;

    mpv_set_option_string(c->mpv, "demuxer-max-bytes",      "150M");
    mpv_set_option_string(c->mpv, "demuxer-max-back-bytes", "50M");
    mpv_set_option_string(c->mpv, "ytdl",                   "yes");
    mpv_set_option_string(c->mpv, "hls-bitrate",            "max");
    mpv_set_option_string(c->mpv, "keep-open",              "yes");
    mpv_set_option_string(c->mpv, "audio-channels",         "stereo");
    mpv_request_log_messages(c->mpv, "info");

    if (audioOnly) {
            mpv_set_option_string(c->mpv, "vid", "no");
        } else {
            c->surface = aura_surface_attach(env, canvas, &wid);
            if (c->surface && wid != 0) {
                c->wid = wid;
                mpv_set_option(c->mpv, "wid", MPV_FORMAT_INT64, &wid);
                mpv_set_option_string(c->mpv, "vo",      "gpu-next");
                mpv_set_option_string(c->mpv, "gpu-api", "vulkan");

                const char *gctx = aura_platform_gpu_context();
                if (gctx)
                    mpv_set_option_string(c->mpv, "gpu-context", gctx);

                mpv_set_option_string(c->mpv, "hwdec", aura_platform_hwdec());
            } else {
                fprintf(stderr,
                    "[AuraPlayer] Surface attach failed; mpv will open its own window\n");
                mpv_set_option_string(c->mpv, "hwdec", "auto");
            }
        }

    mpv_observe_property(c->mpv, 0, "time-pos",               MPV_FORMAT_DOUBLE);
    mpv_observe_property(c->mpv, 0, "duration",               MPV_FORMAT_DOUBLE);
    mpv_observe_property(c->mpv, 0, "pause",                  MPV_FORMAT_FLAG);
    mpv_observe_property(c->mpv, 0, "core-idle",              MPV_FORMAT_FLAG);
    mpv_observe_property(c->mpv, 0, "seeking",                MPV_FORMAT_FLAG);
    mpv_observe_property(c->mpv, 0, "demuxer-cache-duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(c->mpv, 0, "volume",                 MPV_FORMAT_DOUBLE);
    mpv_observe_property(c->mpv, 0, "mute",                   MPV_FORMAT_FLAG);
    mpv_observe_property(c->mpv, 0, "speed",                  MPV_FORMAT_DOUBLE);

    if (mpv_initialize(c->mpv) < 0) {
        fprintf(stderr, "[AuraPlayer] mpv_initialize failed\n");
        return 0;
    }

    #ifdef _WIN32
        c->thread = (HANDLE)_beginthreadex(NULL, 0, event_loop, c, 0, NULL);
    #else
    if (pthread_create(&c->thread, NULL, event_loop, c) == 0)
        c->thread_started = 1;
    #endif

    fprintf(stderr, "[C] initializeNative -> returning wid=%p\n", (void*)(intptr_t)wid);
    fflush(stderr);
    return (jlong)wid;
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_terminateNative(
    JNIEnv *env, jobject obj, jlong handle)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c) return;

    if (c->mpv) {
        /* Ask mpv to quit; the event thread exits on MPV_EVENT_SHUTDOWN. */
        const char *cmd[] = {"quit", NULL};
        mpv_command_async(c->mpv, 0, cmd);

        #ifdef _WIN32
        if (c->thread) {
            WaitForSingleObject(c->thread, INFINITE);
            CloseHandle(c->thread);
            c->thread = NULL;
        }
        #else
        if (c->thread_started) {
            pthread_join(c->thread, NULL);
            c->thread_started = 0;
        }
        #endif
        /* Event thread is gone; now the handle is exclusively ours. */
        mpv_terminate_destroy(c->mpv);
        c->mpv = NULL;
    }

    /* Only safe AFTER mpv is fully destroyed. */
    if (c->surface) {
        aura_surface_detach(c->surface);
        c->surface = NULL;
    }

    if (c->jself) {
        (*env)->DeleteGlobalRef(env, c->jself);
        c->jself = NULL;
    }
    free(c);
}

/* ---------------------------------------------------------------------- */
/* JNI: surface geometry                                                   */
/* ---------------------------------------------------------------------- */

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_updateSurfaceBounds(
    JNIEnv *env, jobject obj, jlong handle, jint x, jint y, jint w, jint h)
{
    (void)env; (void)obj; (void)x; (void)y;
    AuraCtx *c = ctx_of(handle);
    if (c && c->surface)
        aura_surface_resize(c->surface, (int)w, (int)h);
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setSurfaceVisible(
    JNIEnv *env, jobject obj, jlong handle, jboolean visible)
{
    (void)env; (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (c && c->surface)
        aura_surface_set_visible(c->surface, visible ? 1 : 0);
}

/* ---------------------------------------------------------------------- */
/* JNI: playback control / properties                                      */
/* ---------------------------------------------------------------------- */

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_loadFile(
    JNIEnv *env, jobject obj, jlong handle, jstring url)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return;
    const char *path = (*env)->GetStringUTFChars(env, url, NULL);
    const char *cmd[] = {"loadfile", path, NULL};
    int result = mpv_command(c->mpv, cmd);
    if (result < 0) {
        fprintf(stderr, "[AuraPlayer] loadfile failed for '%s': %s\n",
                path, mpv_error_string(result));
    }
    (*env)->ReleaseStringUTFChars(env, url, path);
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setPause(
    JNIEnv *env, jobject obj, jlong handle, jboolean pause)
{
    (void)env; (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return;
    int flag = pause ? 1 : 0;
    mpv_set_property(c->mpv, "pause", MPV_FORMAT_FLAG, &flag);
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setMute(
    JNIEnv *env, jobject obj, jlong handle, jboolean mute)
{
    (void)env; (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return;
    int val = mute ? 1 : 0;
    mpv_set_property(c->mpv, "mute", MPV_FORMAT_FLAG, &val);
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setPropertyDouble(
    JNIEnv *env, jobject obj, jlong handle, jstring name, jdouble value)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return;
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    mpv_set_property(c->mpv, prop, MPV_FORMAT_DOUBLE, &value);
    (*env)->ReleaseStringUTFChars(env, name, prop);
}

JNIEXPORT jdouble JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getPropertyDouble(
    JNIEnv *env, jobject obj, jlong handle, jstring name)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return 0.0;
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    double value = 0;
    mpv_get_property(c->mpv, prop, MPV_FORMAT_DOUBLE, &value);
    (*env)->ReleaseStringUTFChars(env, name, prop);
    return (jdouble)value;
}

JNIEXPORT jstring JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getPropertyString(
    JNIEnv *env, jobject obj, jlong handle, jstring name)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return NULL;
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    char *value = mpv_get_property_string(c->mpv, prop);
    (*env)->ReleaseStringUTFChars(env, name, prop);
    if (!value) return NULL;
    jstring result = (*env)->NewStringUTF(env, value);
    mpv_free(value);
    return result;
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setPropertyString(
    JNIEnv *env, jobject obj, jlong handle, jstring name, jstring value)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return;
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    const char *val  = (*env)->GetStringUTFChars(env, value, NULL);
    mpv_set_property_string(c->mpv, prop, val);
    (*env)->ReleaseStringUTFChars(env, name, prop);
    (*env)->ReleaseStringUTFChars(env, value, val);
}

JNIEXPORT jint JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getPropertyInt(
    JNIEnv *env, jobject obj, jlong handle, jstring name)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return 0;
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    int64_t value = 0;
    mpv_get_property(c->mpv, prop, MPV_FORMAT_INT64, &value);
    (*env)->ReleaseStringUTFChars(env, name, prop);
    return (jint)value;
}

JNIEXPORT jdouble JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getBufferDuration(
    JNIEnv *env, jobject obj, jlong handle)
{
    (void)env; (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return 0.0;
    double duration = 0;
    mpv_get_property(c->mpv, "demuxer-cache-duration", MPV_FORMAT_DOUBLE, &duration);
    return (jdouble)duration;
}

JNIEXPORT jdoubleArray JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getAudioLevels(
    JNIEnv *env, jobject obj, jlong handle)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    double left = 0, right = 0;
    if (c && c->mpv) {
        mpv_get_property(c->mpv, "audio-levels/0/peak", MPV_FORMAT_DOUBLE, &left);
        mpv_get_property(c->mpv, "audio-levels/1/peak", MPV_FORMAT_DOUBLE, &right);
    }
    jdoubleArray result = (*env)->NewDoubleArray(env, 2);
    double fill[2] = {left, right};
    (*env)->SetDoubleArrayRegion(env, result, 0, 2, fill);
    return result;
}

/* ---------------------------------------------------------------------- */
/* JNI: audio devices                                                      */
/* ---------------------------------------------------------------------- */

JNIEXPORT jobjectArray JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getAudioDevices(
    JNIEnv *env, jobject obj, jlong handle)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return NULL;

    mpv_node node;
    if (mpv_get_property(c->mpv, "audio-device-list", MPV_FORMAT_NODE, &node) < 0)
        return NULL;

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    int count = node.u.list->num;
    jobjectArray arr = (*env)->NewObjectArray(env, count * 2, strCls, NULL);

    for (int i = 0; i < count; i++) {
        const char *name = "", *desc = "";
        mpv_node item = node.u.list->values[i];
        for (int j = 0; j < item.u.list->num; j++) {
            if (strcmp(item.u.list->keys[j], "name") == 0)
                name = item.u.list->values[j].u.string;
            else if (strcmp(item.u.list->keys[j], "description") == 0)
                desc = item.u.list->values[j].u.string;
        }
        (*env)->SetObjectArrayElement(env, arr, i * 2,
                                      (*env)->NewStringUTF(env, name));
        (*env)->SetObjectArrayElement(env, arr, i * 2 + 1,
                                      (*env)->NewStringUTF(env, desc));
    }
    mpv_free_node_contents(&node);
    return arr;
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setAudioDevice(
    JNIEnv *env, jobject obj, jlong handle, jstring deviceId)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return;
    const char *id = (*env)->GetStringUTFChars(env, deviceId, NULL);
    mpv_set_property_string(c->mpv, "audio-device", id);
    (*env)->ReleaseStringUTFChars(env, deviceId, id);
}

/* ---------------------------------------------------------------------- */
/* JNI: tracks                                                             */
/* ---------------------------------------------------------------------- */

JNIEXPORT jint JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getTrackCount(
    JNIEnv *env, jobject obj, jlong handle, jstring type)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return 0;
    const char *track_type = (*env)->GetStringUTFChars(env, type, NULL);
    int64_t count = 0;
    mpv_get_property(c->mpv, "track-list/count", MPV_FORMAT_INT64, &count);
    (*env)->ReleaseStringUTFChars(env, type, track_type);
    return (jint)count;
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setTrack(
    JNIEnv *env, jobject obj, jlong handle, jstring type, jstring id)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return;
    const char *t_type = (*env)->GetStringUTFChars(env, type, NULL);
    const char *t_id   = (*env)->GetStringUTFChars(env, id, NULL);
    mpv_set_property_string(c->mpv, t_type, t_id);
    (*env)->ReleaseStringUTFChars(env, type, t_type);
    (*env)->ReleaseStringUTFChars(env, id, t_id);
}

JNIEXPORT jobjectArray JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getNativeTracks(
    JNIEnv *env, jobject obj, jlong handle)
{
    (void)obj;
    AuraCtx *c = ctx_of(handle);
    if (!c || !c->mpv) return NULL;

    mpv_node node;
    if (mpv_get_property(c->mpv, "track-list", MPV_FORMAT_NODE, &node) < 0) return NULL;

    if (node.format != MPV_FORMAT_NODE_ARRAY) {
        mpv_free_node_contents(&node);
        return NULL;
    }

    jclass trackClass = (*env)->FindClass(env, "com/mossip/auraplayer/engine/MediaTrack");
    jmethodID constructor = (*env)->GetMethodID(env, trackClass, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)V");

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
                if      (strcmp(key, "id")    == 0) raw_id    = val.u.string;
                else if (strcmp(key, "type")  == 0) raw_type  = val.u.string;
                else if (strcmp(key, "title") == 0) raw_title = val.u.string;
                else if (strcmp(key, "lang")  == 0) raw_lang  = val.u.string;
            } else if (val.format == MPV_FORMAT_INT64) {
                if      (strcmp(key, "demux-w") == 0) width  = (int)val.u.int64;
                else if (strcmp(key, "demux-h") == 0) height = (int)val.u.int64;
            }
        }

        jstring jId    = (*env)->NewStringUTF(env, raw_id    ? raw_id    : "");
        jstring jType  = (*env)->NewStringUTF(env, raw_type  ? raw_type  : "");
        jstring jTitle = (*env)->NewStringUTF(env, raw_title ? raw_title : "");
        jstring jLang  = (*env)->NewStringUTF(env, raw_lang  ? raw_lang  : "");

        jobject trackObj = (*env)->NewObject(env, trackClass, constructor,
                                             jId, jType, jTitle, jLang, width, height);
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