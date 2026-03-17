package com.mossip.auraplayer.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.Canvas

enum class InitialPlayerState {
    AUTOPLAY,
    LOADED
}

enum class PlayerState {
    PLAYING,
    PAUSED,
    STOPPED,
    IDLE,
    LOADING,
    SEEKING,
    BUFFERING,
}

data class MediaTrack(
    val id: String,
    val type: String,
    val title: String,
    val lang: String?,
    val width: Int = 0,
    val height: Int = 0
)

class AuraPlayer {
    private val playerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _currentTime = MutableStateFlow(0.0)
    val currentTime = _currentTime.asStateFlow()

    private val _bufferDuration = MutableStateFlow(0.0)
    val bufferDuration = _bufferDuration.asStateFlow()

    private val _audioLevels = MutableStateFlow(doubleArrayOf(0.0, 0.0))
    val audioLevels = _audioLevels.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized = _isInitialized.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState.IDLE)
    val playerState = _playerState.asStateFlow()

    private val _tracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration = _duration.asStateFlow()

    private val _volume = MutableStateFlow(100.0)
    val volume = _volume.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()

    private val _speed = MutableStateFlow(1.0)
    val speed = _speed.asStateFlow()

    init {
        AuraPlayerLoader.load()
    }

    fun onNativeTimeChange(time: Double) {
        _currentTime.value = time
    }

    fun onNativeDurationChange(duration: Double) {
        _duration.value = duration
    }

    fun onNativeVolumeChange(v: Double) { _volume.value = v }
    fun onNativeMuteChange(m: Boolean) { _isMuted.value = m }
    fun onNativeSpeedChange(s: Double) { _speed.value = s }

    fun onNativeStateChange(stateInt: Int) {
        _playerState.value = when(stateInt) {
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
            val newTracks = getNativeTracks()?.toList() ?: emptyList()
            _tracks.value = newTracks
        }
    }

    fun onNativeBufferChange(duration: Double) {
        _bufferDuration.value = duration
    }

    private external fun getBufferDuration(): Double

    private external fun getNativeTracks(): Array<MediaTrack>?

    private external fun initializeNative(canvas: Canvas, audioOnly: Boolean)
    private external fun getAudioLevels(): DoubleArray
    external fun loadFile(url: String)

    fun initialize(canvas: Canvas, audioOnly: Boolean) {
        if(!_isInitialized.value) {
            initializeNative(canvas, audioOnly)
            _isInitialized.value = true
        }
    }

    external fun setPause(pause: Boolean)
    private external fun setPropertyDouble(name: String, value: Double)
    private external fun getPropertyDouble(name: String): Double
    private external fun getPropertyString(name: String): String?
    private external fun setTrack(type: String, id: String)

    private external fun terminateNative()

    fun setVolume(v: Double) = setPropertyDouble("volume", v)
    fun getVolume() = getPropertyDouble("volume")
    fun seek(seconds: Double) = setPropertyDouble("time-pos", seconds)
    fun setSpeed(speed: Double) = setPropertyDouble("speed", speed)

    external fun setMute(mute: Boolean)

//    fun setPreferredQuality(height: Int) {
//        // Example: "bestvideo[height<=1080]+bestaudio/best[height<=1080]"
//        val format = "bestvideo[height<=$height]+bestaudio/best"
//        setPropertyString("ytdl-format", format)
//    }

    fun release() {
        stop()
        playerScope.cancel()
    }

    private fun stop() {
        if (isInitialized.value) {
            terminateNative()
            _isInitialized.value = false
        }
    }
}