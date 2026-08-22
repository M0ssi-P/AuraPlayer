#import <IOKit/pwr_mgt/IOPMLib.h>
#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>
#include <jni.h>

static JavaVM *g_jvm = NULL;
static jobject g_playerRef = NULL;   // global ref, one active "now playing" owner at a time
static jmethodID g_onRemotePlay, g_onRemotePause, g_onRemoteSeek, g_onRemoteNext, g_onRemotePrev;

static JNIEnv *attachEnv(void) {
    JNIEnv *env;
    (*g_jvm)->AttachCurrentThread(g_jvm, (void **)&env, NULL);
    return env;
}

static IOPMAssertionID g_assertionID = 0;
static BOOL g_active = NO;

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setSleepPreventionNative(
    JNIEnv *env, jobject obj, jboolean enable)
{
    if (enable && !g_active) {
        CFStringRef reason = CFSTR("AuraPlayer video playback");
        IOReturn r = IOPMAssertionCreateWithName(
            kIOPMAssertionTypePreventUserIdleDisplaySleep,
            kIOPMAssertionLevelOn,
            reason,
            &g_assertionID);
        g_active = (r == kIOReturnSuccess);
        if (!g_active) {
            fprintf(stderr, "[AuraPlayer] IOPMAssertionCreate failed: %d\n", r);
        }
    } else if (!enable && g_active) {
        IOPMAssertionRelease(g_assertionID);
        g_assertionID = 0;
        g_active = NO;
    }
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setupNowPlayingNative(
    JNIEnv *env, jobject obj, jlong hwndPtr)
{
    (void)hwndPtr;
    (*env)->GetJavaVM(env, &g_jvm);
    if (g_playerRef) (*env)->DeleteGlobalRef(env, g_playerRef);
    g_playerRef = (*env)->NewGlobalRef(env, obj);

    jclass cls = (*env)->GetObjectClass(env, obj);
    g_onRemotePlay  = (*env)->GetMethodID(env, cls, "onRemotePlay", "()V");
    g_onRemotePause = (*env)->GetMethodID(env, cls, "onRemotePause", "()V");
    g_onRemoteSeek  = (*env)->GetMethodID(env, cls, "onRemoteSeek", "(D)V");
    g_onRemoteNext  = (*env)->GetMethodID(env, cls, "onRemoteNext", "()V");
    g_onRemotePrev  = (*env)->GetMethodID(env, cls, "onRemotePrevious", "()V");

    dispatch_async(dispatch_get_main_queue(), ^{
        MPRemoteCommandCenter *cc = [MPRemoteCommandCenter sharedCommandCenter];

        [cc.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
            JNIEnv *e2 = attachEnv();
            if (g_playerRef) (*e2)->CallVoidMethod(e2, g_playerRef, g_onRemotePlay);
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [cc.pauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
            JNIEnv *e2 = attachEnv();
            if (g_playerRef) (*e2)->CallVoidMethod(e2, g_playerRef, g_onRemotePause);
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [cc.nextTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
            JNIEnv *e2 = attachEnv();
            if (g_playerRef) (*e2)->CallVoidMethod(e2, g_playerRef, g_onRemoteNext);
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [cc.previousTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
            JNIEnv *e2 = attachEnv();
            if (g_playerRef) (*e2)->CallVoidMethod(e2, g_playerRef, g_onRemotePrev);
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        // This is your seek bar handler — the widget's scrubber drags call this.
        [cc.changePlaybackPositionCommand addTargetWithHandler:
            ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                MPChangePlaybackPositionCommandEvent *e =
                    (MPChangePlaybackPositionCommandEvent *)event;
                JNIEnv *e2 = attachEnv();
                if (g_playerRef) (*e2)->CallVoidMethod(e2, g_playerRef, g_onRemoteSeek, (jdouble)e.positionTime);
                return MPRemoteCommandHandlerStatusSuccess;
        }];
        cc.changePlaybackPositionCommand.enabled = YES;
    });
}

/* Call this every time position/duration/playing-state/title changes —
 * cheap to call often, but you don't need per-frame; once per second or on
 * actual state change (play/pause/seek/track change) is plenty. */
JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_updateNowPlayingNative(
    JNIEnv *env, jobject obj,
    jstring title, jdouble duration, jdouble position, jboolean isPlaying)
{
    const char *cTitle = (*env)->GetStringUTFChars(env, title, NULL);
    NSString *nsTitle = [NSString stringWithUTF8String:cTitle];
    (*env)->ReleaseStringUTFChars(env, title, cTitle);

    dispatch_async(dispatch_get_main_queue(), ^{
        NSMutableDictionary *info = [NSMutableDictionary dictionary];
        info[MPMediaItemPropertyTitle] = nsTitle;
        info[MPMediaItemPropertyPlaybackDuration] = @(duration);
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(position);
        info[MPNowPlayingInfoPropertyPlaybackRate] = @(isPlaying ? 1.0 : 0.0);
        // Optional: MPMediaItemPropertyArtwork for the album-art-style thumbnail
        // in the widget — build an MPMediaItemArtwork from a UIImage/NSImage if
        // you have one, otherwise the widget falls back to a generic icon.
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = info;
    });
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_teardownNowPlayingNative(
    JNIEnv *env, jobject obj)
{
    dispatch_async(dispatch_get_main_queue(), ^{
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
    });
    if (g_playerRef) { (*env)->DeleteGlobalRef(env, g_playerRef); g_playerRef = NULL; }
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setNextEnabledNative(
    JNIEnv *env, jobject obj, jboolean enabled)
{
    dispatch_async(dispatch_get_main_queue(), ^{
        [MPRemoteCommandCenter sharedCommandCenter].nextTrackCommand.enabled = enabled;
    });
}

JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setPreviousEnabledNative(
    JNIEnv *env, jobject obj, jboolean enabled)
{
    dispatch_async(dispatch_get_main_queue(), ^{
        [MPRemoteCommandCenter sharedCommandCenter].previousTrackCommand.enabled = enabled;
    });
}