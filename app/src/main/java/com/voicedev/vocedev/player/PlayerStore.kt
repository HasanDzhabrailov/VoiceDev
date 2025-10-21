package com.voicedev.vocedev.player

import android.net.Uri
import com.voicedev.vocedev.waveform.WaveformProvider
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BUFFER_RETRY_DELAY_MS = 750L
private const val MIN_SPEED = 0.5f
private const val MAX_SPEED = 2.0f

/**
 * Store orchestrates intents, player state and waveform loading.
 */
class PlayerStore(
    private val scope: CoroutineScope,
    private val player: IVoicePlayer,
    private val waveformProvider: WaveformProvider,
    private val ioDispatcher: CoroutineDispatcher,
    private val cacheKeyMapper: (Uri) -> String = { uri ->
        uri.lastPathSegment ?: uri.toString()
    }
) {
    private val _model = MutableStateFlow(VoicePlayerComponent.Model())
    val model: StateFlow<VoicePlayerComponent.Model> = _model.asStateFlow()

    private var preparedUri: Uri? = null
    private var waveformJob: Job? = null
    private var retryJob: Job? = null
    private var pendingPlay = false

    init {
        scope.launch {
            player.state.collectLatest { snapshot ->
                _model.update {
                    it.copy(
                        isPlaying = snapshot.isPlaying,
                        positionMs = snapshot.positionMs,
                        durationMs = snapshot.durationMs,
                        bufferedMs = snapshot.bufferedMs,
                        speed = snapshot.speed,
                        error = snapshot.error?.message ?: snapshot.errorMessage
                    )
                }

                if (!snapshot.isPlaying && !snapshot.isBuffering) {
                    pendingPlay = false
                }

                if (snapshot.shouldRetryBuffering) {
                    scheduleBufferRetry()
                } else {
                    retryJob?.cancel()
                    retryJob = null
                }
            }
        }
    }

    fun dispatch(intent: VoicePlayerComponent.Intent) {
        when (intent) {
            is VoicePlayerComponent.Intent.Play -> handlePlay(intent.uri)
            VoicePlayerComponent.Intent.Pause -> handlePause()
            VoicePlayerComponent.Intent.Stop -> handleStop()
            is VoicePlayerComponent.Intent.SeekTo -> player.seekTo(max(0L, intent.positionMs))
            is VoicePlayerComponent.Intent.SetSpeed -> handleSpeed(intent.speed)
            VoicePlayerComponent.Intent.ToggleSpeakerphone -> player.toggleSpeakerphone()
        }
    }

    private fun handlePlay(uri: Uri) {
        if (preparedUri == uri && pendingPlay) {
            return
        }

        scope.launch {
            if (preparedUri != uri) {
                preparedUri = uri
                waveformJob?.cancel()
                waveformJob = scope.launch {
                    waveformProvider.loadWaveform(cacheKeyMapper(uri), uri).collect { peaks ->
                        _model.update { it.copy(waveform = peaks) }
                    }
                }

                runCatching {
                    withContext(ioDispatcher) { player.prepare(uri) }
                }.onFailure { error ->
                    _model.update { it.copy(error = error.message) }
                    return@launch
                }
            }

            pendingPlay = true
            player.play()
        }
    }

    private fun handlePause() {
        pendingPlay = false
        player.pause()
    }

    private fun handleStop() {
        pendingPlay = false
        preparedUri = null
        waveformJob?.cancel()
        waveformJob = null
        player.stop()
        _model.value = VoicePlayerComponent.Model()
    }

    private fun handleSpeed(speed: Float) {
        val clamped = min(MAX_SPEED, max(MIN_SPEED, speed))
        player.setSpeed(clamped)
        _model.update { it.copy(speed = clamped) }
    }

    private fun scheduleBufferRetry() {
        if (!pendingPlay) return
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(BUFFER_RETRY_DELAY_MS)
            if (player.state.value.shouldRetryBuffering) {
                player.play()
            }
        }
    }
}

/**
 * Abstraction around the underlying ExoPlayer so the store can remain platform agnostic.
 */
interface IVoicePlayer {
    val state: StateFlow<VoicePlayerSnapshot>
    suspend fun prepare(uri: Uri)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun toggleSpeakerphone()
}

/**
 * Snapshot of the playback state exposed by [IVoicePlayer].
 */
data class VoicePlayerSnapshot(
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val speed: Float,
    val isBuffering: Boolean,
    val error: Throwable? = null,
    val errorMessage: String? = null
) {
    val shouldRetryBuffering: Boolean
        get() = isBuffering && !isPlaying && error == null
}
