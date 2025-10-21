package com.voicedev.vocedev.voice.record

import com.voicedev.vocedev.voice.record.audio.AudioGateway
import com.voicedev.vocedev.voice.record.audio.AudioSourceConfig
import com.voicedev.vocedev.voice.record.audio.IAudioSource
import com.voicedev.vocedev.voice.record.audio.IEncoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RecorderStoreTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Test
    fun startAndFinishProducesFile() = runTest {
        val permissionFlow = MutableSharedFlow<Boolean>(replay = 1).apply { tryEmit(true) }
        val fakeSource = FakeAudioSource()
        val gateway = AudioGateway(
            audioSource = fakeSource,
            encoderFactory = { file -> FakeEncoder(file) },
            cacheDirProvider = { File(createTempDir(), "rec-test").apply { mkdirs() } },
            ioDispatcher = dispatcher
        )
        val store = RecorderStore(scope, gateway, hasRecordPermission = { true }, permissionFlow = permissionFlow.asSharedFlow())

        store.dispatch(VoiceRecorderComponent.Intent.Start)
        advanceUntilIdle()
        assertTrue(store.state.value.isRecording)

        fakeSource.advanceTime(1_500)
        store.dispatch(VoiceRecorderComponent.Intent.Finish)
        advanceUntilIdle()

        val model = store.state.value
        assertFalse(model.isRecording)
        assertNotNull(model.tempFilePath)
        val file = File(model.tempFilePath!!)
        assertTrue(file.exists())
        assertTrue("file should be > 0 bytes") { file.length() > 0 }
    }

    @Test
    fun encoderFailureSetsErrorAndStopsRecording() = runTest {
        val permissionFlow = MutableSharedFlow<Boolean>(replay = 1).apply { tryEmit(true) }
        val fakeSource = FakeAudioSource(throwOnEncode = true)
        val gateway = AudioGateway(
            audioSource = fakeSource,
            encoderFactory = { file -> FakeEncoder(file, throwOnEncode = true) },
            cacheDirProvider = { createTempDir(prefix = "rec-fail") },
            ioDispatcher = dispatcher
        )
        val store = RecorderStore(scope, gateway, hasRecordPermission = { true }, permissionFlow = permissionFlow.asSharedFlow())

        store.dispatch(VoiceRecorderComponent.Intent.Start)
        advanceUntilIdle()

        val model = store.state.value
        assertFalse(model.isRecording)
        assertNotNull(model.error)
    }

    @Test
    fun permissionRevokedCancelsRecording() = runTest {
        val permissionFlow = MutableSharedFlow<Boolean>(replay = 1).apply { tryEmit(true) }
        val fakeSource = FakeAudioSource()
        val gateway = AudioGateway(
            audioSource = fakeSource,
            encoderFactory = { file -> FakeEncoder(file) },
            cacheDirProvider = { createTempDir(prefix = "rec-perm") },
            ioDispatcher = dispatcher
        )
        val store = RecorderStore(scope, gateway, hasRecordPermission = { true }, permissionFlow = permissionFlow.asSharedFlow())

        store.dispatch(VoiceRecorderComponent.Intent.Start)
        advanceUntilIdle()
        assertTrue(store.state.value.isRecording)

        permissionFlow.emit(false)
        advanceUntilIdle()

        assertFalse(store.state.value.isRecording)
        assertEquals("Microphone permission revoked", store.state.value.error)
    }

    private suspend fun advanceUntilIdle() {
        dispatcher.scheduler.advanceUntilIdle()
        yield()
    }

    private class FakeAudioSource(
        private val throwOnEncode: Boolean = false
    ) : IAudioSource {

        private var elapsed = 0L

        override suspend fun start(
            scope: kotlinx.coroutines.CoroutineScope,
            config: AudioSourceConfig,
            onData: suspend (ShortArray, Int) -> Unit,
            onError: suspend (Throwable) -> Unit
        ): IAudioSource.Session {
            val session = FakeSession()
            scope.launch {
                val buffer = ShortArray(160) { 100 }
                if (throwOnEncode) {
                    onError(IllegalStateException("encoder failure"))
                } else {
                    onData(buffer, buffer.size)
                }
            }
            return session
        }

        fun advanceTime(amount: Long) {
            elapsed += amount
        }

        private inner class FakeSession : IAudioSource.Session {
            override suspend fun pause() {
            }

            override suspend fun resume() {
            }

            override suspend fun stop() {
            }

            override suspend fun updateNoiseSuppressor(enabled: Boolean) {
                // no-op
            }

            override fun elapsedMillis(): Long = elapsed

            override val bufferSize: Int = 160

            override fun close() { }
        }
    }

    private class FakeEncoder(
        private val file: File,
        private val throwOnEncode: Boolean = false
    ) : IEncoder {

        override suspend fun start(sampleRate: Int, channelCount: Int) {
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf())
        }

        override suspend fun encode(buffer: ShortArray, length: Int) {
            if (throwOnEncode) throw IllegalStateException("encoder failure")
            file.appendBytes(ByteArray(length * 2))
        }

        override suspend fun finalizeEncoding() {
            // no-op
        }

        override fun close() {
            // no-op
        }
    }
}

