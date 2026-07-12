package com.mossip.auraplayer.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.Canvas

/* ====================================================================== */
/* Public types                                                            */
/* ====================================================================== */

enum class PlayerState { PLAYING, PAUSED, STOPPED, IDLE, LOADING, SEEKING, BUFFERING }

data class MediaTrack(
    val id: String,
    val type: String,
    val title: String,
    val lang: String?,
    val width: Int = 0,
    val height: Int = 0,
)

data class AudioDevice(val id: String, val description: String)

enum class OverlayMode { OVER_VIDEO, DOCKED }

data class Segment(
    val start: Double,
    val end: Double,
    val label: String? = null,
    val kind: Kind = Kind.CHAPTER,
) {
    enum class Kind { CHAPTER, INTRO, OUTRO, AD, CUSTOM }
}

class AuraSurfaceEntry(
    val player: AuraPlayer,
    val holeIndex: Int,
) {
    private var _bounds = MutableStateFlow<Any?>(null)
    val bounds: StateFlow<Any?> get() = _bounds.asStateFlow()

    private val _attachedState = MutableStateFlow(false)
    val attachedState: StateFlow<Boolean> get() = _attachedState.asStateFlow()

    private val _isFullScreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> get() = _isFullScreen.asStateFlow()

    val canvas: Canvas = Canvas().apply { background = java.awt.Color.BLACK }
    var initialized = false

    fun setBounds(value: Any?) { _bounds.value = value }
    fun setFullScreen(bool: Boolean) { _isFullScreen.value = bool }
    fun setAttached(bool: Boolean) { _attachedState.value = bool }
}

interface AuraSurfaceHost {
    fun setFullscreen(player: AuraPlayer, enabled: Boolean)
    fun isFullscreen(player: AuraPlayer): Boolean
    fun setWindowFullscreen(player: AuraPlayer, enabled: Boolean)
}

/* ====================================================================== */
/* Player                                                                  */
/* ====================================================================== */

class AuraPlayer {

    private val playerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _currentTime = MutableStateFlow(0.0)
    val currentTime = _currentTime.asStateFlow()
    private val _duration = MutableStateFlow(0.0)
    val duration = _duration.asStateFlow()
    private val _bufferDuration = MutableStateFlow(0.0)
    val bufferDuration = _bufferDuration.asStateFlow()
    private val _playerState = MutableStateFlow(PlayerState.IDLE)
    val playerState = _playerState.asStateFlow()
    private val _tracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()
    private val _volume = MutableStateFlow(100.0)
    val volume = _volume.asStateFlow()
    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()
    private val _speed = MutableStateFlow(1.0)
    val speed = _speed.asStateFlow()
    private val _fullscreen = MutableStateFlow(false)
    val fullscreen = _fullscreen.asStateFlow()
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized = _isInitialized.asStateFlow()

    private var handle: Long = 0L

    var videoHandle: Long = 0L
        private set

    var host: AuraSurfaceHost? = null

    private val _overlayMode = MutableStateFlow(OverlayMode.DOCKED)
    val overlayMode: StateFlow<OverlayMode> = _overlayMode.asStateFlow()

    init {
        AuraPlayerLoader.load()
        handle = createNative()
        check(handle != 0L) { "AuraPlayer: native context creation failed" }
    }

    /* ----------------------------- lifecycle ------------------------------ */

    fun initialize(canvas: Canvas, audioOnly: Boolean) {
        if (_isInitialized.value || handle == 0L) return
        videoHandle = initializeNative(handle, canvas, audioOnly)
        println("INIT: ctx=$handle wid=$videoHandle")
        _overlayMode.value = enableUnderlayIfSupported()
        println(overlayMode)
        _isInitialized.value = true
        if (!audioOnly && videoHandle == 0L) {
            System.err.println("[AuraPlayer] embedding failed; mpv opened detached window")
        }
    }

    fun release() {
        if (handle != 0L) {
            terminateNative(handle)
            handle = 0L
            videoHandle = 0L
            _isInitialized.value = false
        }
        playerScope.cancel()
    }

    /* ---------------------------- fullscreen ------------------------------ */

    fun enterFullscreen() = setFullscreenInternal(true)
    fun exitFullscreen() = setFullscreenInternal(false)
    fun toggleFullscreen() = setFullscreenInternal(!(host?.isFullscreen(this) ?: false))

    private fun setFullscreenInternal(enabled: Boolean) {
        val h = host ?: return
        h.setFullscreen(this, enabled)
        h.setWindowFullscreen(this, enabled)
        _fullscreen.value = enabled
    }

    /* ------------------------- underlay dispatch -------------------------- */

    fun enableUnderlayIfSupported(): OverlayMode = when {
        videoHandle == 0L -> OverlayMode.DOCKED
        Os.current == Os.MACOS -> {
            enableUnderlay(videoHandle)
            OverlayMode.OVER_VIDEO
        }
        Os.current == Os.WINDOWS -> OverlayMode.OVER_VIDEO  // region-cut verified
        else -> OverlayMode.DOCKED
    }

    fun clearUnderlay(rootWindowHandle: Long, holeIndex: Int) {
        setUnderlay(rootWindowHandle, videoHandle, holeIndex, 0, 0, 0, 0, false)
    }

    /* ----------------------------- playback ------------------------------- */

    fun loadFile(url: String) = loadFile(handle, url)
    fun setPause(pause: Boolean) = setPause(handle, pause)
    fun setMute(mute: Boolean) = setMute(handle, mute)
    fun seek(seconds: Double) = setPropertyDouble(handle, "time-pos", seconds)
    fun setVolume(v: Double) = setPropertyDouble(handle, "volume", v)
    fun setSpeed(s: Double) = setPropertyDouble(handle, "speed", s)
    fun setTrack(type: String, id: String) = setTrack(handle, type, id)
    fun updateSurfaceBounds(x: Int, y: Int, w: Int, h: Int) =
        updateSurfaceBounds(handle, x, y, w, h)
    fun setSurfaceVisible(visible: Boolean) = setSurfaceVisible(handle, visible)

    suspend fun getPropertyDoubleAsync(name: String): Double =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            getPropertyDouble(handle, name)
        }

    fun audioDevices(): List<AudioDevice> =
        getAudioDevices(handle)?.toList()?.chunked(2)
            ?.map { AudioDevice(it[0], it[1]) } ?: emptyList()

    fun setAudioDevice(device: AudioDevice) = setAudioDevice(handle, device.id)

    /* ------------------- callbacks (from native event thread) ------------- */

    fun onNativeTimeChange(time: Double) { _currentTime.value = time }
    fun onNativeDurationChange(d: Double) { _duration.value = d }
    fun onNativeBufferChange(d: Double) { _bufferDuration.value = d }
    fun onNativeVolumeChange(v: Double) { _volume.value = v }
    fun onNativeMuteChange(m: Boolean) { _isMuted.value = m }
    fun onNativeSpeedChange(s: Double) { _speed.value = s }

    fun onNativeStateChange(stateInt: Int) {
        _playerState.value = when (stateInt) {
            0 -> PlayerState.PLAYING
            1 -> PlayerState.PAUSED
            2 -> PlayerState.STOPPED
            3 -> PlayerState.IDLE
            4 -> PlayerState.LOADING
            5 -> PlayerState.SEEKING
            6 -> PlayerState.BUFFERING
            else -> PlayerState.IDLE
        }
    }

    fun onNativeTracksChanged() {
        playerScope.launch {
            _tracks.value = getNativeTracks(handle)?.toList() ?: emptyList()
        }
    }

    /* ------------------------------ natives -------------------------------- */
    /* Signatures must match native_render.c / jawt_*.c|m exactly.             */

    private external fun createNative(): Long
    private external fun initializeNative(handle: Long, canvas: Canvas, audioOnly: Boolean): Long
    private external fun terminateNative(handle: Long)
    private external fun loadFile(handle: Long, url: String)
    private external fun setPause(handle: Long, pause: Boolean)
    private external fun setMute(handle: Long, mute: Boolean)
    private external fun setPropertyDouble(handle: Long, name: String, value: Double)
    private external fun getPropertyDouble(handle: Long, name: String): Double
    private external fun getPropertyString(handle: Long, name: String): String?
    private external fun setPropertyString(handle: Long, name: String, value: String)
    private external fun getPropertyInt(handle: Long, name: String): Int
    private external fun getBufferDuration(handle: Long): Double
    private external fun getAudioLevels(handle: Long): DoubleArray
    private external fun getAudioDevices(handle: Long): Array<String>?
    private external fun setAudioDevice(handle: Long, deviceId: String)
    private external fun getTrackCount(handle: Long, type: String): Int
    private external fun setTrack(handle: Long, type: String, id: String)
    private external fun getNativeTracks(handle: Long): Array<MediaTrack>?
    private external fun updateSurfaceBounds(handle: Long, x: Int, y: Int, w: Int, h: Int)
    private external fun setSurfaceVisible(handle: Long, visible: Boolean)

    external fun dcompInit(ctx: Long, topLevelHwnd: Long): Boolean
    external fun dcompAttachUiSwapchain(ctx: Long, swapchainPtr: Long)
    external fun dcompSetVideoRect(ctx: Long, x: Int, y: Int, w: Int, h: Int)
    external fun dcompCommit(ctx: Long)
    external fun dcompTeardown(ctx: Long)

    /* [FIX-DCOMP] Removed dcompInit/dcompAttachUiSwapchain/dcompSetVideoRect/
     * dcompCommit/dcompTeardown: no native implementation exists yet, so any
     * call would throw UnsatisfiedLinkError. Reintroduce together with the
     * DirectComposition backend when (if) that work starts. */

    private external fun enableUnderlay(videoLayerPtr: Long)                    // macOS
    external fun setUnderlay(rootHandle: Long, videoHandle: Long, index: Int,   // Windows (stub on mac/linux)
                             x: Int, y: Int, w: Int, h: Int, enable: Boolean)
    external fun setBorderlessFullscreen(windowHandle: Long, enable: Boolean)   // all platforms

    private enum class Os {
        WINDOWS, MACOS, LINUX;
        companion object {
            val current = System.getProperty("os.name").lowercase().let {
                when {
                    it.startsWith("win") -> WINDOWS
                    it.startsWith("mac") -> MACOS
                    else -> LINUX
                }
            }
        }
    }
}