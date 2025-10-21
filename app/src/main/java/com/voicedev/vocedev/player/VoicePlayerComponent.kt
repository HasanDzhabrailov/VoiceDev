package com.voicedev.vocedev.player

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * Public contract describing the voice player component used by Decompose.
 */
interface VoicePlayerComponent {
    val model: StateFlow<Model>
    fun onIntent(intent: Intent)

    data class Model(
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val bufferedMs: Long = 0L,
        val speed: Float = 1f,
        val error: String? = null,
        val waveform: List<Int> = emptyList()
    )

    sealed interface Intent {
        data class Play(val uri: Uri) : Intent
        data object Pause : Intent
        data object Stop : Intent
        data class SeekTo(val positionMs: Long) : Intent
        data class SetSpeed(val speed: Float) : Intent
        data object ToggleSpeakerphone : Intent
    }
}

internal class DefaultVoicePlayerComponent(
    private val store: PlayerStore
) : VoicePlayerComponent {
    override val model: StateFlow<VoicePlayerComponent.Model> = store.model

    override fun onIntent(intent: VoicePlayerComponent.Intent) {
        store.dispatch(intent)
    }
}
