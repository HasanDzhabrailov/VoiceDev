package com.voicedev.vocedev.voice.record

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.voicedev.vocedev.voice.record.audio.AudioGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

interface VoiceRecorderComponent {

    val model: StateFlow<Model>

    fun onIntent(intent: Intent)

    data class Model(
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val isLocked: Boolean = false,
        val elapsedMs: Long = 0L,
        val peakLevelDb: Float = -120f,
        val tempFilePath: String? = null,
        val error: String? = null,
        val gain: Float = 1f,
        val noiseSuppressionEnabled: Boolean = false
    )

    sealed interface Intent {
        data object Start : Intent
        data object Pause : Intent
        data object Resume : Intent
        data object Cancel : Intent
        data object Finish : Intent
        data object ToggleLock : Intent
        data class UpdateGain(val gain: Float) : Intent
        data class UpdateNoiseSuppress(val enabled: Boolean) : Intent
    }
}

class VoiceRecorderComponentImpl(
    componentContext: ComponentContext,
    audioGateway: AudioGateway,
    permissionFlow: Flow<Boolean>,
    private val hasPermission: () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : VoiceRecorderComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(dispatcher)
    private val store = RecorderStore(
        scope = scope,
        audioGateway = audioGateway,
        hasRecordPermission = hasPermission,
        permissionFlow = permissionFlow
    )

    override val model: StateFlow<VoiceRecorderComponent.Model> = store.state

    override fun onIntent(intent: VoiceRecorderComponent.Intent) {
        store.dispatch(intent)
    }

    init {
        lifecycle.doOnDestroy {
            store.dispose()
            scope.cancel()
        }
    }
}

private fun ComponentContext.componentScope(dispatcher: CoroutineDispatcher): CoroutineScope {
    val job = SupervisorJob()
    val scope = CoroutineScope(dispatcher + job)
    lifecycle.doOnDestroy { scope.cancel() }
    return scope
}

