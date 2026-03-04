package com.mossip.auraplayer.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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

    private val _audioLevels = MutableStateFlow(doubleArrayOf(0.0, 0.0))
    val audioLevels = _audioLevels.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized = _isInitialized.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration = _isInitialized.asStateFlow()
    var initialPlayerState: InitialPlayerState =InitialPlayerState.LOADED

    init {
        AuraPlayerLoader.load()
        playerScope.launch {
            while (isActive) {
                if (isInitialized.value) {
                    updateTime()
                }
                delay(500)
            }
        }
    }
    private external fun initializeNative(canvas: Canvas, audioOnly: Boolean)
    private external fun getAudioLevels(): DoubleArray
    external fun loadFile(url: String)

    fun initialize(canvas: Canvas, audioOnly: Boolean) {
        if(!isInitialized.value) {
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
    fun seek(seconds: Double) = setPropertyDouble("time-pos", seconds)
    fun getDuration(): Double = getPropertyDouble("duration")
    fun getTimePos(): Double = getPropertyDouble("time-pos")

    fun switchTrack(type: String, id: String) {
        // type: "vid", "aid", or "sid"
        setTrack(type, id)
    }

    fun updateTime() {
        _currentTime.value = getTimePos()
        _duration.value = getDuration()
    }

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