#ifdef _WIN32
#include <jni.h>
#include <windows.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <systemmediatransportcontrolsinterop.h>
#include <stdio.h>

using namespace winrt::Windows::Media;

#define NP_LOG(...) do { fprintf(stderr, "[nowplaying-win] " __VA_ARGS__); \
                         fprintf(stderr, "\n"); fflush(stderr); } while (0)

static bool g_sleepPrevented = false;

static JavaVM *g_jvm = nullptr;
static jobject g_playerRef = nullptr;
static jmethodID g_onRemotePlay, g_onRemotePause, g_onRemoteSeek, g_onRemoteNext, g_onRemotePrev;
static SystemMediaTransportControls g_smtc{ nullptr };
static winrt::event_token g_buttonToken;
static winrt::event_token g_seekToken;

static JNIEnv *attachEnv()
{
    JNIEnv *env;
    g_jvm->AttachCurrentThread((void**)&env, nullptr);
    return env;
}

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

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setupNowPlayingNative(
    JNIEnv *env, jobject obj, jlong hwndPtr)
{
    env->GetJavaVM(&g_jvm);
    if (g_playerRef) env->DeleteGlobalRef(g_playerRef);
    g_playerRef = env->NewGlobalRef(obj);

    jclass cls = env->GetObjectClass(obj);
    g_onRemotePlay  = env->GetMethodID(cls, "onRemotePlay", "()V");
    g_onRemotePause = env->GetMethodID(cls, "onRemotePause", "()V");
    g_onRemoteSeek  = env->GetMethodID(cls, "onRemoteSeek", "(D)V");
    g_onRemoteNext  = env->GetMethodID(cls, "onRemoteNext", "()V");
    g_onRemotePrev  = env->GetMethodID(cls, "onRemotePrevious", "()V");

    HWND hwnd = (HWND)(intptr_t)hwndPtr;
    auto interop = winrt::get_activation_factory<SystemMediaTransportControls,
        ISystemMediaTransportControlsInterop>();
    winrt::com_ptr<::ABI::Windows::Media::ISystemMediaTransportControls> abiSmtc;
    HRESULT hr = interop->GetForWindow(hwnd,
        winrt::guid_of<::ABI::Windows::Media::ISystemMediaTransportControls>(),
        abiSmtc.put_void());
    if (FAILED(hr)) { NP_LOG("GetForWindow failed 0x%08lx", hr); return; }
    g_smtc = abiSmtc.as<SystemMediaTransportControls>();

    g_smtc.IsPlayEnabled(true);
    g_smtc.IsPauseEnabled(true);
    g_smtc.IsNextEnabled(true);
    g_smtc.IsPreviousEnabled(true);
    g_smtc.IsEnabled(true);

    g_buttonToken = g_smtc.ButtonPressed(
        [](SystemMediaTransportControls const&, SystemMediaTransportControlsButtonPressedEventArgs const& args) {
            JNIEnv *e = attachEnv();
            if (!g_playerRef) return;
            switch (args.Button()) {
                case SystemMediaTransportControlsButton::Play:
                    e->CallVoidMethod(g_playerRef, g_onRemotePlay); break;
                case SystemMediaTransportControlsButton::Pause:
                    e->CallVoidMethod(g_playerRef, g_onRemotePause); break;
                case SystemMediaTransportControlsButton::Next:
                    e->CallVoidMethod(g_playerRef, g_onRemoteNext); break;
                case SystemMediaTransportControlsButton::Previous:
                    e->CallVoidMethod(g_playerRef, g_onRemotePrev); break;
                default: break;
            }
        });

    // Seek bar equivalent — PlaybackPositionChangeRequested
    g_seekToken = g_smtc.PlaybackPositionChangeRequested(
        [](SystemMediaTransportControls const&, PlaybackPositionChangeRequestedEventArgs const& args) {
            JNIEnv *e = attachEnv();
            double seconds = args.RequestedPlaybackPosition().count() / 10000000.0; // 100ns units -> s
            if (g_playerRef) e->CallVoidMethod(g_playerRef, g_onRemoteSeek, seconds);
        });

    NP_LOG("SMTC ready");
}

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_updateNowPlayingNative(
    JNIEnv *env, jobject, jstring title, jdouble duration, jdouble position, jboolean isPlaying)
{
    if (!g_smtc) return;
    const char *cTitle = env->GetStringUTFChars(title, nullptr);
    auto updater = g_smtc.DisplayUpdater();
    updater.Type(winrt::Windows::Media::MediaPlaybackType::Video);
    updater.VideoProperties().Title(winrt::to_hstring(cTitle));
    env->ReleaseStringUTFChars(title, cTitle);
    updater.Update();

    auto timeline = MediaPlaybackTimeline{ nullptr };
    // NOTE: full timeline scrubbing typically flows through
    // g_smtc.UpdateTimelineProperties(...) with a SystemMediaTransportControlsTimelineProperties;
    // duration/position given in 100ns ticks:
    SystemMediaTransportControlsTimelineProperties props;
    props.StartTime(winrt::Windows::Foundation::TimeSpan(0));
    props.MinSeekTime(winrt::Windows::Foundation::TimeSpan(0));
    props.MaxSeekTime(winrt::Windows::Foundation::TimeSpan(
        (int64_t)(duration * 10000000.0)));
    props.EndTime(winrt::Windows::Foundation::TimeSpan(
        (int64_t)(duration * 10000000.0)));
    props.Position(winrt::Windows::Foundation::TimeSpan(
        (int64_t)(position * 10000000.0)));
    g_smtc.UpdateTimelineProperties(props);

    g_smtc.PlaybackStatus(isPlaying
        ? MediaPlaybackStatus::Playing
        : MediaPlaybackStatus::Paused);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_teardownNowPlayingNative(JNIEnv *env, jobject)
{
    if (g_smtc) {
        g_smtc.ButtonPressed(g_buttonToken);
        g_smtc.PlaybackPositionChangeRequested(g_seekToken);
        g_smtc.IsEnabled(false);
        g_smtc = nullptr;
    }
    if (g_playerRef) { env->DeleteGlobalRef(g_playerRef); g_playerRef = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setNextEnabledNative(
    JNIEnv*, jobject, jboolean enabled)
{
    if (!g_smtc) return;
    g_smtc.IsNextEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mossip_auraplayer_engine_AuraPlayer_setPreviousEnabledNative(
    JNIEnv*, jobject, jboolean enabled)
{
    if (!g_smtc) return;
    g_smtc.IsPreviousEnabled(enabled);
}

#endif