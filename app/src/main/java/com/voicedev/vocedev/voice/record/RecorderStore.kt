package com.voicedev.vocedev.voice.record

import com.voicedev.vocedev.voice.record.audio.AudioGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecorderStore(
    private val scope: CoroutineScope,
    private val audioGateway: AudioGateway,
    private val hasRecordPermission: () -> Boolean,
    permissionFlow: Flow<Boolean>
) {

    private val _state = MutableStateFlow(VoiceRecorderComponent.Model())
    val state: StateFlow<VoiceRecorderComponent.Model> = _state.asStateFlow()

    private val intentMutex = Mutex()
    private var session: AudioGateway.RecordingSession? = null
    private var timerJob: Job? = null

    init {
        scope.launch {
            permissionFlow.collectLatest { granted ->
                if (!granted) {
                    handlePermissionRevoked()
                }
            }
        }
    }

    fun dispatch(intent: VoiceRecorderComponent.Intent) {
        scope.launch {
            intentMutex.withLock {
                when (intent) {
                    VoiceRecorderComponent.Intent.Start -> handleStart()
                    VoiceRecorderComponent.Intent.Pause -> handlePause()
                    VoiceRecorderComponent.Intent.Resume -> handleResume()
                    VoiceRecorderComponent.Intent.Cancel -> handleCancel()
                    VoiceRecorderComponent.Intent.Finish -> handleFinish()
                    VoiceRecorderComponent.Intent.ToggleLock -> toggleLock()
                    is VoiceRecorderComponent.Intent.UpdateGain -> updateGain(intent.gain)
                    is VoiceRecorderComponent.Intent.UpdateNoiseSuppress -> updateNoiseSuppress(intent.enabled)
                }
            }
        }
    }

    fun dispose() {
        scope.launch {
            stopSession(delete = true)
        }
    }

    private suspend fun handleStart() {
        if (session != null) return
        if (!hasRecordPermission()) {
            updateError("Microphone permission is required")
            return
        }
        val model = _state.value
        try {
            val recordingSession = audioGateway.startRecording(
                scope = scope,
                gain = model.gain,
                noiseSuppression = model.noiseSuppressionEnabled,
                onPeak = { peak ->
                    _state.value = _state.value.copy(peakLevelDb = peak)
                },
                onError = { throwable ->
                    updateError(throwable.message ?: "Recording error")
                    scope.launch { stopSession(delete = true) }
                }
            )
            session = recordingSession
            _state.value = _state.value.copy(
                isRecording = true,
                isPaused = false,
                tempFilePath = recordingSession.file.absolutePath,
                elapsedMs = 0L,
                peakLevelDb = -120f,
                error = null
            )
            startTimer()
        } catch (t: Throwable) {
            updateError(t.message ?: "Failed to start recording")
        }
    }

    private suspend fun handlePause() {
        val currentSession = session ?: return
        currentSession.pause()
        stopTimer()
        _state.value = _state.value.copy(isPaused = true, elapsedMs = currentSession.elapsedMillis())
    }

    private suspend fun handleResume() {
        val currentSession = session ?: return
        currentSession.resume()
        _state.value = _state.value.copy(isPaused = false)
        startTimer()
    }

    private suspend fun handleFinish() {
        stopSession(delete = false)
    }

    private suspend fun handleCancel() {
        stopSession(delete = true)
    }

    private fun toggleLock() {
        _state.value = _state.value.copy(isLocked = !_state.value.isLocked)
    }

    private suspend fun updateGain(value: Float) {
        val gain = value.coerceIn(0.1f, 3f)
        _state.value = _state.value.copy(gain = gain)
        session?.updateGain(gain)
    }

    private suspend fun updateNoiseSuppress(enabled: Boolean) {
        _state.value = _state.value.copy(noiseSuppressionEnabled = enabled)
        session?.updateNoiseSuppressor(enabled)
    }

    private fun startTimer() {
        timerJob?.cancel()
        val currentSession = session ?: return
        timerJob = scope.launch {
            while (true) {
                _state.value = _state.value.copy(elapsedMs = currentSession.elapsedMillis())
                delay(50L)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private suspend fun stopSession(delete: Boolean) {
        val currentSession = session ?: return
        stopTimer()
        session = null
        if (delete) {
            currentSession.cancelAndDelete()
            _state.value = _state.value.copy(
                isRecording = false,
                isPaused = false,
                tempFilePath = null,
                elapsedMs = 0L
            )
        } else {
            val file = currentSession.stopAndFinalize()
            _state.value = _state.value.copy(
                isRecording = false,
                isPaused = false,
                tempFilePath = file.absolutePath,
                elapsedMs = currentSession.elapsedMillis()
            )
        }
    }

    private suspend fun handlePermissionRevoked() {
        if (session != null) {
            updateError("Microphone permission revoked")
            stopSession(delete = true)
        }
    }

    private fun updateError(message: String) {
        _state.value = _state.value.copy(error = message)
    }
}

