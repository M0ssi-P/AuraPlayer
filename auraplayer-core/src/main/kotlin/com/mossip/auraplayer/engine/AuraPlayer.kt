package com.mossip.auraplayer.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Canvas
import javax.sound.midi.Track
import kotlin.collections.emptyList
import kotlin.time.Duration.Companion.milliseconds

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

data class Metadata(
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val year: String?,
    val genre: String?,
    val composer: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val comment: String?,
    val lyrics: String?,
)

data class AudioDevice(val id: String, val description: String)

data class NowPlayingConfig(
    val nowPlayingEnabled: Boolean = true,
    val showNext: Boolean = true,
    val showPrevious: Boolean = true,
)

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
    var uiVideoRect: java.awt.Rectangle? = null

    private val _attachedState = MutableStateFlow(false)
    val attachedState: StateFlow<Boolean> get() = _attachedState.asStateFlow()

    private val _isFullScreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> get() = _isFullScreen.asStateFlow()

    val canvas: Canvas = Canvas().apply { background = java.awt.Color.BLACK }
    var initialized = false
    var usesDComp = false
    var dcompRenderStarted = false
    @Volatile var tornDown = false
    var lastX = Int.MIN_VALUE
    var lastY = Int.MIN_VALUE
    var lastW = 0
    var lastH = 0
    var uiInitialized = false

    private val resizeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var resizeJob: Job? = null

    private var lastPushAt = 0L

    fun pushSizeDebounced(w: Int, h: Int) {
        resizeJob?.cancel()
        val now = System.currentTimeMillis()
        if (now - lastPushAt > 250) {
            /* leading edge: idle → this is a discrete jump (fullscreen,
             * maximize, drag start) — push NOW */
            lastPushAt = now
            if (!tornDown) player.updateSurfaceBounds(0, 0, w, h)
        } else {
            /* trailing edge: mid-stream — coalesce to one push at settle */
            resizeJob = resizeScope.launch {
                delay(100)
                lastPushAt = System.currentTimeMillis()
                if (!tornDown) player.updateSurfaceBounds(0, 0, w, h)
            }
        }
    }

    private var inLiveResize = false

    fun onGeometryPass(w: Int, h: Int) {
        resizeJob?.cancel()
        if (!inLiveResize) {
            inLiveResize = true
            player.beginLiveResize()          // leading edge = drag started
        }
        resizeJob = resizeScope.launch {
            delay(150)                        // trailing edge = drag settled
            inLiveResize = false
            if (!tornDown) {
                player.updateSurfaceBounds(0, 0, w, h)  // contentsScale refresh
                player.endLiveResize()
            }
        }
    }

    fun cancelResize() {
        resizeJob?.cancel()
        resizeScope.cancel()
    }

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
    private val _currentTrack = MutableStateFlow<List<MediaTrack>>(emptyList())
    val currentTrack = _currentTrack.asStateFlow()
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
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Real decoded resolution once mpv reports it (dwidth x dheight, so
     *  rotation/anamorphic already accounted for). Null until known. */
    val videoSize = _videoSize.asStateFlow()
    val aspect: StateFlow<Float> = combine(_videoSize) { s ->
        val w = s.first()?.first ?: 0
        val h = s.first()?.second ?: 0
        if (w > 0 && h > 0) w.toFloat() / h else 0f
    }.stateIn(playerScope, SharingStarted.Eagerly, 0f)

    private val _metadata = MutableStateFlow<Map<String, String>>(emptyMap())
    val metadata: StateFlow<Map<String, String>> = _metadata.asStateFlow()

    private val _mediaTitle = MutableStateFlow<String?>(null)
    val mediaTitle: StateFlow<String?> = _mediaTitle.asStateFlow()
    private fun tag(vararg names: String): String? =
        metadata.value.entries.firstOrNull { e ->
            names.any { it.equals(e.key, ignoreCase = true) ||
                    it.replace("_", "").equals(e.key.replace("_", ""), true) }
        }?.value?.takeIf { it.isNotBlank() }

    private var handle: Long = 0L
    private var nowPlayingSetupDone = false
    val nativeCtx: Long get() = handle

    var videoHandle: Long = 0L
        private set

    @Volatile
    var isAudioOnly: Boolean = false
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
        _isInitialized.value = true
        if (!audioOnly && videoHandle == 0L) {
            System.err.println("[AuraPlayer] embedding failed; mpv opened detached window")
        }
    }

    /* [DCOMP] mpv init for the render-API path: same option setup as
     * initialize(), but NO canvas, NO JAWT, NO --wid — mpv never gets a
     * window. The render context is created later (dcompCreateRenderContext)
     * once the first video rect exists. Called by AuraHostState.register()
     * after dcompInit succeeds. */
    fun initializeRenderApi(audioOnly: Boolean = false) {
        if (_isInitialized.value || handle == 0L) return
        isAudioOnly = audioOnly
        initializeRenderApiNative(handle, audioOnly)
        println("INIT: ctx=$handle (render-api, no wid)")
        _overlayMode.value = OverlayMode.OVER_VIDEO
        _isInitialized.value = true
    }

    fun release() {
        if (handle != 0L) {
            terminateNative(handle)
            handle = 0L
            videoHandle = 0L
            _isInitialized.value = false
            if (nowPlayingConfig.nowPlayingEnabled && nowPlayingSetupDone) {
                teardownNowPlayingNative()
            }
        }
        playerScope.cancel()
    }

    suspend fun loadWhenReady(url: String) {
        isInitialized.first { it }
        loadFile(url)
    }

    @Suppress("unused")   // called from native
    private fun onNativeMetadata(kv: Array<String>) {
        _metadata.value = buildMap {
            for (i in kv.indices step 2) put(kv[i], kv.getOrElse(i + 1) { "" })
        }

        val mets = Metadata(
            artist      = tag("artist"),
            albumArtist = tag("album_artist", "albumartist", "band"),
            album       = tag("album") ,
            year        = tag("date", "year")?.take(4),
            genre       = tag("genre"),
            composer    = tag("composer"),
            trackNumber = tag("track", "track_id")?.substringBefore('/')?.toIntOrNull(),
            discNumber  = tag("disc")?.substringBefore('/')?.toIntOrNull(),
            comment     = tag("comment", "description"),
            lyrics      = tag("lyrics", "unsyncedlyrics", "USLT"),
        )
    }

    @Suppress("unused")
    private fun onNativeTitle(title: String) {
        _mediaTitle.value = title.ifBlank { null }
    }

    private external fun getMetadataNative(handle: Long): Array<String>?

    fun refreshMetadata() {
        val kv = getMetadataNative(handle) ?: return
        onNativeMetadata(kv)
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

    private fun mpvTrackProp(type: String) = when (type) {
        "video" -> "vid"
        "audio" -> "aid"
        "sub" -> "sid"
        else -> type
    }

    fun setTrack(type: String, id: Any) {
        setPropertyString(handle, mpvTrackProp(type),id.toString())
    }

    fun currentTrackId(type: String): String? =
        getPropertyString(handle, mpvTrackProp(type))

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
    fun onNativeVideoSizeChange(w: Int, h: Int) {
        if (w > 0 && h > 0) _videoSize.value = w to h
    }

    // NOW_PLAYING NATIVE CALLs

    fun onRemotePlay() { setPause(false) }
    fun onRemotePause() { setPause(true) }
    fun onRemoteSeek(positionSeconds: Double) { seek(positionSeconds) }
    fun onRemoteNext() { /* if you have a playlist concept */ }
    fun onRemotePrevious() { /* same */ }

    fun onNativeStateChange(stateInt: Int) {
        _playerState.value = when (stateInt) {
            0 -> {
                setSleepPreventionNative(true)
                if (nowPlayingConfig.nowPlayingEnabled) {
                    if (!nowPlayingSetupDone) {
                        setupNowPlayingNative(handle)
                        nowPlayingSetupDone = true
                    }
                    setNextEnabledNative(nowPlayingConfig.showNext)
                    setPreviousEnabledNative(nowPlayingConfig.showPrevious)
                    updateNowPlayingNative("Jujutsu Kaisen", duration.value, currentTime.value, true)
                }
                PlayerState.PLAYING
            }
            1 -> {
                setSleepPreventionNative(false)
                if (nowPlayingConfig.nowPlayingEnabled && nowPlayingSetupDone) {
                    setNextEnabledNative(nowPlayingConfig.showNext)
                    setPreviousEnabledNative(nowPlayingConfig.showPrevious)
                    updateNowPlayingNative("Jujutsu Kaisen", duration.value, currentTime.value, false)
                }
                PlayerState.PAUSED
            }
            2 -> {
                setSleepPreventionNative(false)
                if (nowPlayingConfig.nowPlayingEnabled && nowPlayingSetupDone) {
                    teardownNowPlayingNative()
                }
                PlayerState.STOPPED
            }
            3 -> {
                setSleepPreventionNative(false)
                PlayerState.IDLE
            }
            4 -> PlayerState.LOADING
            5 -> PlayerState.SEEKING
            6 -> PlayerState.BUFFERING
            else -> {
                setSleepPreventionNative(false)
                if (nowPlayingConfig.nowPlayingEnabled && nowPlayingSetupDone) {
                    teardownNowPlayingNative()
                }
                PlayerState.IDLE
            }
        }
    }

    private var tracksJob: Job? = null

    fun onNativeTracksChanged() {
        tracksJob?.cancel()
        tracksJob = playerScope.launch {
            val list = getNativeTracks(handle)?.toList() ?: emptyList()
            _tracks.value = list

            val currentVidTrack = list.find { it.type == "video" && it.id == currentTrackId(mpvTrackProp("video")) }
            val currentAudioTrack = list.find { it.type == "audio" && it.id ==  currentTrackId(mpvTrackProp("audio")) }
            val currentSubTrack = list.find { it.type == "sub" && it.id ==  currentTrackId(mpvTrackProp("sub")) }

            _currentTrack.value = listOfNotNull(currentVidTrack, currentAudioTrack, currentSubTrack)
        }
    }

    /* ------------------------------ natives -------------------------------- */
    /* Signatures must match native_render.c / jawt_*.c|m exactly.             */

    private external fun createNative(): Long
    private external fun initializeNative(handle: Long, canvas: Canvas, audioOnly: Boolean): Long
    /* [DCOMP] mpv handle init without wid/JAWT — see initializeRenderApi().
     * C symbol: Java_com_mossip_auraplayer_engine_AuraPlayer_initializeRenderApiNative */
    private external fun initializeRenderApiNative(handle: Long, audioOnly: Boolean)
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
    external fun setNextEnabledNative(enabled: Boolean)
    external fun setPreviousEnabledNative(enabled: Boolean)
    private external fun setTrack(handle: Long, type: String, id: String)
    private external fun getNativeTracks(handle: Long): Array<MediaTrack>?

    // Now PLAYING & Prevent sleep
    external fun setSleepPreventionNative(enable: Boolean)
    external fun setupNowPlayingNative(hwnd: Long)
    external fun updateNowPlayingNative(title: String, duration: Double, position: Double, isPlaying: Boolean)
    external fun teardownNowPlayingNative()

    var nowPlayingConfig: NowPlayingConfig = NowPlayingConfig()
        set(value) {
            val prevEnabled = field.nowPlayingEnabled
            field = value
            if (value.nowPlayingEnabled && !prevEnabled) {
                setupNowPlayingNative(handle)
            } else if (!value.nowPlayingEnabled && prevEnabled) {
                teardownNowPlayingNative()
            }
            if (value.nowPlayingEnabled) {
                setNextEnabledNative(value.showNext)
                setPreviousEnabledNative(value.showPrevious)
            }
        }
    private external fun updateSurfaceBounds(handle: Long, x: Int, y: Int, w: Int, h: Int)
    private external fun setSurfaceVisible(handle: Long, visible: Boolean)

    private external fun enableUnderlay(videoLayerPtr: Long)                    // macOS
    external fun setUnderlay(rootHandle: Long, videoHandle: Long, index: Int,   // Windows (stub on mac/linux)
                             x: Int, y: Int, w: Int, h: Int, enable: Boolean)
    external fun setBorderlessFullscreen(windowHandle: Long, enable: Boolean)   // all platforms
    private external fun setLiveResize(videoLayerPtr: Long, active: Boolean)

    fun beginLiveResize() {
        if (Os.current == Os.MACOS && videoHandle != 0L) setLiveResize(videoHandle, true)
    }
    fun endLiveResize() {
        if (Os.current == Os.MACOS && videoHandle != 0L) setLiveResize(videoHandle, false)
    }

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