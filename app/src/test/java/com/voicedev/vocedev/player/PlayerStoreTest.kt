package com.voicedev.vocedev.player

import android.net.Uri
import com.voicedev.vocedev.waveform.WaveformProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStoreTest {

    @Test
    fun playPreparesPlayerAndLoadsWaveform() = runTest(StandardTestDispatcher()) {
        val player = FakePlayer()
        val waveformProvider = FakeWaveformProvider(flowOf(listOf(10, 20, 30)))
        val store = PlayerStore(
            scope = backgroundScope,
            player = player,
            waveformProvider = waveformProvider,
            ioDispatcher = testScheduler
        )

        val uri = Uri.parse("file://sample.mp3")
        store.dispatch(VoicePlayerComponent.Intent.Play(uri))
        advanceUntilIdle()

        assertEquals(uri, player.preparedUri)
        assertTrue(player.playCalls >= 1)
        assertEquals(listOf(10, 20, 30), store.model.value.waveform)
    }

    @Test
    fun pauseClearsPlayingFlag() = runTest(StandardTestDispatcher()) {
        val player = FakePlayer()
        val waveformProvider = FakeWaveformProvider(flowOf(emptyList()))
        val store = PlayerStore(
            scope = backgroundScope,
            player = player,
            waveformProvider = waveformProvider,
            ioDispatcher = testScheduler
        )
        val uri = Uri.parse("file://sample.mp3")
        store.dispatch(VoicePlayerComponent.Intent.Play(uri))
        advanceUntilIdle()

        store.dispatch(VoicePlayerComponent.Intent.Pause)
        advanceUntilIdle()

        assertTrue(player.pauseCalled)
        assertFalse(store.model.value.isPlaying)
    }

    @Test
    fun retriesPlaybackWhenBuffering() = runTest(StandardTestDispatcher()) {
        val player = FakePlayer()
        val waveformProvider = FakeWaveformProvider(flowOf(emptyList()))
        val store = PlayerStore(
            scope = backgroundScope,
            player = player,
            waveformProvider = waveformProvider,
            ioDispatcher = testScheduler
        )

        val uri = Uri.parse("file://sample.mp3")
        store.dispatch(VoicePlayerComponent.Intent.Play(uri))
        advanceUntilIdle()
        assertEquals(1, player.playCalls)

        player.emit(snapshot = player.state.value.copy(isPlaying = false, isBuffering = true))
        advanceUntilIdle()

        advanceTimeBy(800)
        assertEquals(2, player.playCalls)
    }

    @Test
    fun clampsSpeedToAllowedRange() = runTest(StandardTestDispatcher()) {
        val player = FakePlayer()
        val store = PlayerStore(
            scope = backgroundScope,
            player = player,
            waveformProvider = FakeWaveformProvider(flowOf(emptyList())),
            ioDispatcher = testScheduler
        )

        store.dispatch(VoicePlayerComponent.Intent.SetSpeed(5f))
        advanceUntilIdle()

        assertEquals(2f, player.state.value.speed)
        assertEquals(2f, store.model.value.speed)

        store.dispatch(VoicePlayerComponent.Intent.SetSpeed(0.25f))
        advanceUntilIdle()
        assertEquals(0.5f, player.state.value.speed)
        assertEquals(0.5f, store.model.value.speed)
    }
}

private class FakePlayer : IVoicePlayer {
    private val _state = MutableStateFlow(
        VoicePlayerSnapshot(
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            bufferedMs = 0L,
            speed = 1f,
            isBuffering = false,
            error = null,
            errorMessage = null
        )
    )
    override val state: StateFlow<VoicePlayerSnapshot> = _state

    var preparedUri: Uri? = null
    var playCalls: Int = 0
    var pauseCalled: Boolean = false

    override suspend fun prepare(uri: Uri) {
        preparedUri = uri
        emit(_state.value.copy(durationMs = 240000L))
    }

    override fun play() {
        playCalls += 1
        emit(_state.value.copy(isPlaying = true))
    }

    override fun pause() {
        pauseCalled = true
        emit(_state.value.copy(isPlaying = false))
    }

    override fun stop() {
        emit(
            VoicePlayerSnapshot(
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                bufferedMs = 0L,
                speed = 1f,
                isBuffering = false
            )
        )
    }

    override fun seekTo(positionMs: Long) {
        emit(_state.value.copy(positionMs = positionMs))
    }

    override fun setSpeed(speed: Float) {
        emit(_state.value.copy(speed = speed))
    }

    override fun toggleSpeakerphone() = Unit

    fun emit(snapshot: VoicePlayerSnapshot) {
        _state.value = snapshot
    }
}

private class FakeWaveformProvider(
    private val flow: Flow<List<Int>>
) : WaveformProvider {
    override fun loadWaveform(messageId: String, uri: Uri): Flow<List<Int>> = flow
}
