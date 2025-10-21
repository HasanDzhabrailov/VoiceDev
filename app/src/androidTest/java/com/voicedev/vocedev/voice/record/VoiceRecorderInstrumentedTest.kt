package com.voicedev.vocedev.voice.record

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.voicedev.vocedev.voice.record.audio.AudioGateway
import com.voicedev.vocedev.voice.record.audio.AudioSourceConfig
import com.voicedev.vocedev.voice.record.audio.IAudioSource
import com.voicedev.vocedev.voice.record.audio.IEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VoiceRecorderInstrumentedTest {

    @Test
    fun startPauseResumeFinishHappyPath() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val componentContext = DefaultComponentContext(lifecycle)
        val permissionFlow = MutableStateFlow(true)
        val audioGateway = AudioGateway(
            audioSource = InstrumentedFakeAudioSource(),
            encoderFactory = { file -> InstrumentedFakeEncoder(file) },
            cacheDirProvider = { context.cacheDir },
            ioDispatcher = Dispatchers.IO
        )
        val component = VoiceRecorderComponentImpl(
            componentContext = componentContext,
            audioGateway = audioGateway,
            permissionFlow = permissionFlow,
            hasPermission = { true }
        )

        component.onIntent(VoiceRecorderComponent.Intent.Start)
        delay(200)
        assertTrue(component.model.value.isRecording)

        component.onIntent(VoiceRecorderComponent.Intent.Pause)
        delay(100)
        assertTrue(component.model.value.isPaused)

        component.onIntent(VoiceRecorderComponent.Intent.Resume)
        delay(200)
        assertFalse(component.model.value.isPaused)

        component.onIntent(VoiceRecorderComponent.Intent.Finish)
        delay(200)

        val model = component.model.value
        assertFalse(model.isRecording)
        assertNotNull(model.tempFilePath)
        val file = File(model.tempFilePath!!)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }

    private class InstrumentedFakeAudioSource : IAudioSource {

        override suspend fun start(
            scope: kotlinx.coroutines.CoroutineScope,
            config: AudioSourceConfig,
            onData: suspend (ShortArray, Int) -> Unit,
            onError: suspend (Throwable) -> Unit
        ): IAudioSource.Session {
            val session = InstrumentedSession()
            scope.launch {
                repeat(20) {
                    val buffer = ShortArray(160) { 50 }
                    onData(buffer, buffer.size)
                    delay(20)
                    session.advance(20)
                }
            }
            return session
        }

        private class InstrumentedSession : IAudioSource.Session {
            private var elapsed = 0L
            private var paused = false

            fun advance(amount: Long) {
                if (!paused) elapsed += amount
            }

            override suspend fun pause() {
                paused = true
            }

            override suspend fun resume() {
                paused = false
            }

            override suspend fun stop() {
                paused = true
            }

            override suspend fun updateNoiseSuppressor(enabled: Boolean) {
                // no-op
            }

            override fun elapsedMillis(): Long = elapsed

            override val bufferSize: Int = 160

            override fun close() {
                paused = true
            }
        }
    }

    private class InstrumentedFakeEncoder(private val file: File) : IEncoder {

        override suspend fun start(sampleRate: Int, channelCount: Int) {
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf())
        }

        override suspend fun encode(buffer: ShortArray, length: Int) {
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

