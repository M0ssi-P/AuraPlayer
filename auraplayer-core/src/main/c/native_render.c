/*
 * AuraPlayer portable JNI core.
 *
 * Rendering model: window/layer embedding via --wid. mpv (vo=gpu-next,
 * gpu-api=vulkan) owns the Vulkan instance, device, and swapchain
 * internally -- including MoltenVK on macOS through the custom
 * gpu-context=macembed patched into the bundled libmpv.
 *
 * There is deliberately NO mpv_render_context, no Vulkan API usage, and
 * no per-frame JNI render call in this file. libmpv has no Vulkan render
 * API (only OpenGL and SW), so the previous MPV_RENDER_API_TYPE_VULKAN
 * code could never compile; with wid embedding none of it is needed.
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
/* Global state                                                            */
/* ---------------------------------------------------------------------- */

static JavaVM     *g_vm  = NULL;
static jobject     g_obj = NULL;
static mpv_handle *mpv   = NULL;

/* State codes mirrored in AuraPlayer.kt / onNativeStateChange */
#define STATE_PLAYING   0
#define STATE_PAUSED    1
#define STATE_STOPPED   2
#define STATE_IDLE      3
#define STATE_LOADING   4
#define STATE_SEEKING   5
#define STATE_BUFFERING 6

/* ---------------------------------------------------------------------- */
/* Helpers                                                                 */
/* ---------------------------------------------------------------------- */

static int get_internal_state(void)
{
    if (!mpv) return STATE_STOPPED;

    int64_t paused = 0, core_idle = 0, seeking = 0;
    mpv_get_property(mpv, "pause",     MPV_FORMAT_FLAG, &paused);
    mpv_get_property(mpv, "core-idle", MPV_FORMAT_FLAG, &core_idle);
    mpv_get_property(mpv, "seeking",   MPV_FORMAT_FLAG, &seeking);

    if (seeking) return STATE_SEEKING;

    if (core_idle) {
        double duration = 0;
        mpv_get_property(mpv, "duration", MPV_FORMAT_DOUBLE, &duration);
        return (duration <= 0) ? STATE_LOADING : STATE_BUFFERING;
    }

    return paused ? STATE_PAUSED : STATE_PLAYING;
}

/* ---------------------------------------------------------------------- */
/* mpv event loop thread                                                   */
/* ---------------------------------------------------------------------- */

#ifdef _WIN32
static unsigned __stdcall event_loop(void *arg) {
#define THREAD_RETURN 0
#else
static void *event_loop(void *arg) {
#define THREAD_RETURN NULL
#endif
    (void)arg;
    if (g_vm == NULL || g_obj == NULL) goto thread_exit;

    JNIEnv *env;
    if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != 0)
        goto thread_exit;

    jclass cls = (*env)->GetObjectClass(env, g_obj);
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
                    (*env)->CallVoidMethod(env, g_obj, onTimeChanged, *(double *)prop->data);
                } else if (strcmp(prop->name, "volume") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    (*env)->CallVoidMethod(env, g_obj, onVolumeChanged, *(double *)prop->data);
                } else if (strcmp(prop->name, "mute") == 0 && prop->format == MPV_FORMAT_FLAG) {
                    (*env)->CallVoidMethod(env, g_obj, onMuteChanged,
                                           (jboolean)(*(int *)prop->data));
                } else if (strcmp(prop->name, "speed") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    (*env)->CallVoidMethod(env, g_obj, onSpeedChanged, *(double *)prop->data);
                } else if (strcmp(prop->name, "demuxer-cache-duration") == 0 &&
                           prop->format == MPV_FORMAT_DOUBLE) {
                    (*env)->CallVoidMethod(env, g_obj, onBufferChanged, *(double *)prop->data);
                } else if (strcmp(prop->name, "duration") == 0 && prop->format == MPV_FORMAT_DOUBLE) {
                    (*env)->CallVoidMethod(env, g_obj, onDurationChanged, *(double *)prop->data);
                } else {
                    (*env)->CallVoidMethod(env, g_obj, onStateChanged,
                                           (jint)get_internal_state());
                }
                break;
            }

            case MPV_EVENT_LOG_MESSAGE: {
                mpv_event_log_message *msg = (mpv_event_log_message *)event->data;
                fprintf(stderr, "[mpv/%s] %s: %s", msg->level, msg->prefix, msg->text);
                break;
            }

            case MPV_EVENT_END_FILE:
                (*env)->CallVoidMethod(env, g_obj, onStateChanged, (jint)STATE_IDLE);
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

/*
 * Matches the Kotlin declaration:
 *   private external fun initializeNative(canvas: Canvas, audioOnly: Boolean)
 * (The previous version was missing the canvas parameter, which would have
 * caused UnsatisfiedLinkError.)
 */
JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_initializeNative(
    JNIEnv *env, jobject thisObj, jobject canvas, jboolean audioOnly)
{
    if (mpv) return;

    (*env)->GetJavaVM(env, &g_vm);
    g_obj = (*env)->NewGlobalRef(env, thisObj);

    mpv = mpv_create();
    if (!mpv) return;

    /* Generic playback options */
    mpv_set_option_string(mpv, "demuxer-max-bytes",      "150M");
    mpv_set_option_string(mpv, "demuxer-max-back-bytes", "50M");
    mpv_set_option_string(mpv, "ytdl",                   "yes");
    mpv_set_option_string(mpv, "hls-bitrate",            "max");
    mpv_set_option_string(mpv, "keep-open",              "yes");
    mpv_set_option_string(mpv, "msg-level",              "all=v");
    mpv_set_option_string(mpv, "log-file",               "/tmp/mpv.log");
    mpv_request_log_messages(mpv, "info");

    if (audioOnly) {
        mpv_set_option_string(mpv, "vid", "no");
    } else {
        /*
         * Video path: embed via --wid. All of these options MUST be set
         * before mpv_initialize() -- setting wid afterwards is exactly the
         * mistake that makes mpv fall back to its own detached window.
         */
        int64_t wid = aura_surface_attach(env, canvas);
        if (wid != 0) {
            mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &wid);
            mpv_set_option_string(mpv, "vo",      "gpu-next");
            mpv_set_option_string(mpv, "gpu-api", "vulkan");

            const char *gctx = aura_platform_gpu_context();
            if (gctx)
                mpv_set_option_string(mpv, "gpu-context", gctx);

            mpv_set_option_string(mpv, "hwdec", aura_platform_hwdec());
        } else {
            fprintf(stderr,
                "[AuraPlayer] Surface attach failed; mpv will open its own window\n");
            mpv_set_option_string(mpv, "hwdec", "auto");
        }
    }

    mpv_observe_property(mpv, 0, "time-pos",               MPV_FORMAT_DOUBLE);
    mpv_observe_property(mpv, 0, "duration",               MPV_FORMAT_DOUBLE);
    mpv_observe_property(mpv, 0, "pause",                  MPV_FORMAT_FLAG);
    mpv_observe_property(mpv, 0, "core-idle",              MPV_FORMAT_FLAG);
    mpv_observe_property(mpv, 0, "seeking",                MPV_FORMAT_FLAG);
    mpv_observe_property(mpv, 0, "demuxer-cache-duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(mpv, 0, "volume",                 MPV_FORMAT_DOUBLE);
    mpv_observe_property(mpv, 0, "mute",                   MPV_FORMAT_FLAG);
    mpv_observe_property(mpv, 0, "speed",                  MPV_FORMAT_DOUBLE);

    if (mpv_initialize(mpv) < 0) {
        fprintf(stderr, "[AuraPlayer] mpv_initialize failed\n");
        return;
    }

#ifdef _WIN32
    _beginthreadex(NULL, 0, event_loop, NULL, 0, NULL);
#else
    pthread_t thread;
    pthread_create(&thread, NULL, event_loop, NULL);
    pthread_detach(thread);
#endif
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_terminateNative(
    JNIEnv *env, jobject obj)
{
    (void)obj;

    if (mpv) {
        /* Blocks until the VO (and its swapchain on our layer) is gone. */
        mpv_terminate_destroy(mpv);
        mpv = NULL;
    }

    /* Only safe AFTER mpv is fully destroyed. */
    aura_surface_detach();

    if (g_obj) {
        (*env)->DeleteGlobalRef(env, g_obj);
        g_obj = NULL;
    }
}

/* ---------------------------------------------------------------------- */
/* JNI: surface geometry                                                   */
/* ---------------------------------------------------------------------- */

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_updateSurfaceBounds(
    JNIEnv *env, jobject obj, jint x, jint y, jint w, jint h)
{
    (void)env; (void)obj; (void)x; (void)y;
    /*
     * macOS: resizes the CAMetalLayer; the macembed context polls
     * drawableSize and resizes the Vulkan swapchain on its own.
     * Windows/X11: no-op -- mpv follows the parent window itself.
     */
    aura_surface_resize((int)w, (int)h);
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setSurfaceVisible(
    JNIEnv *env, jobject obj, jboolean visible)
{
    (void)env; (void)obj;
    aura_surface_set_visible(visible ? 1 : 0);
}

/* ---------------------------------------------------------------------- */
/* JNI: playback control / properties (unchanged behavior)                 */
/* ---------------------------------------------------------------------- */

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_loadFile(
    JNIEnv *env, jobject obj, jstring url)
{
    (void)obj;
    if (!mpv) return;
    const char *path = (*env)->GetStringUTFChars(env, url, NULL);
    const char *cmd[] = {"loadfile", path, NULL};
    int result = mpv_command(mpv, cmd);
    if (result < 0) {
        fprintf(stderr, "[AuraPlayer] loadfile failed for '%s': %s\n",
                path, mpv_error_string(result));
    }
    (*env)->ReleaseStringUTFChars(env, url, path);
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setPause(
    JNIEnv *env, jobject obj, jboolean pause)
{
    (void)env; (void)obj;
    if (!mpv) return;
    int flag = pause ? 1 : 0;
    mpv_set_property(mpv, "pause", MPV_FORMAT_FLAG, &flag);
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setMute(
    JNIEnv *env, jobject obj, jboolean mute)
{
    (void)env; (void)obj;
    if (!mpv) return;
    int val = mute ? 1 : 0;
    mpv_set_property(mpv, "mute", MPV_FORMAT_FLAG, &val);
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setPropertyDouble(
    JNIEnv *env, jobject obj, jstring name, jdouble value)
{
    (void)obj;
    if (!mpv) return;
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    mpv_set_property(mpv, prop, MPV_FORMAT_DOUBLE, &value);
    (*env)->ReleaseStringUTFChars(env, name, prop);
}

JNIEXPORT jdouble JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getPropertyDouble(
    JNIEnv *env, jobject obj, jstring name)
{
    (void)obj;
    if (!mpv) return 0.0;
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    double value = 0;
    mpv_get_property(mpv, prop, MPV_FORMAT_DOUBLE, &value);
    (*env)->ReleaseStringUTFChars(env, name, prop);
    return (jdouble)value;
}

/* Was declared in AuraPlayer.kt but missing from the old C file. */
JNIEXPORT jstring JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getPropertyString(
    JNIEnv *env, jobject obj, jstring name)
{
    (void)obj;
    if (!mpv) return NULL;
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    char *value = mpv_get_property_string(mpv, prop);
    (*env)->ReleaseStringUTFChars(env, name, prop);
    if (!value) return NULL;
    jstring result = (*env)->NewStringUTF(env, value);
    mpv_free(value);
    return result;
}

JNIEXPORT jint JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getPropertyInt(
    JNIEnv *env, jobject obj, jstring name)
{
    (void)obj;
    if (!mpv) return 0;
    const char *prop = (*env)->GetStringUTFChars(env, name, NULL);
    int64_t value = 0;
    mpv_get_property(mpv, prop, MPV_FORMAT_INT64, &value);
    (*env)->ReleaseStringUTFChars(env, name, prop);
    return (jint)value;
}

JNIEXPORT jdouble JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getBufferDuration(
    JNIEnv *env, jobject obj)
{
    (void)env; (void)obj;
    if (!mpv) return 0.0;
    double duration = 0;
    mpv_get_property(mpv, "demuxer-cache-duration", MPV_FORMAT_DOUBLE, &duration);
    return (jdouble)duration;
}

JNIEXPORT jdoubleArray JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getAudioLevels(
    JNIEnv *env, jobject obj)
{
    (void)obj;
    double left = 0, right = 0;
    if (mpv) {
        mpv_get_property(mpv, "audio-levels/0/peak", MPV_FORMAT_DOUBLE, &left);
        mpv_get_property(mpv, "audio-levels/1/peak", MPV_FORMAT_DOUBLE, &right);
    }
    jdoubleArray result = (*env)->NewDoubleArray(env, 2);
    double fill[2] = {left, right};
    (*env)->SetDoubleArrayRegion(env, result, 0, 2, fill);
    return result;
}

JNIEXPORT jint JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getTrackCount(
    JNIEnv *env, jobject obj, jstring type)
{
    (void)obj;
    if (!mpv) return 0;
    const char *track_type = (*env)->GetStringUTFChars(env, type, NULL);
    int64_t count = 0;
    mpv_get_property(mpv, "track-list/count", MPV_FORMAT_INT64, &count);
    (*env)->ReleaseStringUTFChars(env, type, track_type);
    return (jint)count;
}

JNIEXPORT void JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_setTrack(
    JNIEnv *env, jobject obj, jstring type, jstring id)
{
    (void)obj;
    if (!mpv) return;
    const char *t_type = (*env)->GetStringUTFChars(env, type, NULL);
    const char *t_id   = (*env)->GetStringUTFChars(env, id, NULL);
    mpv_set_property_string(mpv, t_type, t_id);
    (*env)->ReleaseStringUTFChars(env, type, t_type);
    (*env)->ReleaseStringUTFChars(env, id, t_id);
}

JNIEXPORT jobjectArray JNICALL Java_com_mossip_auraplayer_engine_AuraPlayer_getNativeTracks(
    JNIEnv *env, jobject obj)
{
    (void)obj;
    if (!mpv) return NULL;

    mpv_node node;
    if (mpv_get_property(mpv, "track-list", MPV_FORMAT_NODE, &node) < 0) return NULL;

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