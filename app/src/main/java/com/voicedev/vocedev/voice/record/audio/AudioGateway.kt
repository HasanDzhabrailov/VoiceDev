package com.voicedev.vocedev.voice.record.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10

private const val SAMPLE_RATE = 48_000
private const val CHANNEL_COUNT = 1
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

/**
 * Configuration for the [IAudioSource].
 */
data class AudioSourceConfig(
    val sampleRate: Int = SAMPLE_RATE,
    val channelCount: Int = CHANNEL_COUNT,
    val encoding: Int = ENCODING,
    val noiseSuppressorEnabled: Boolean = false
)

/**
 * Contract representing a raw audio source (e.g. [AudioRecord]).
 */
interface IAudioSource {

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    suspend fun start(
        scope: CoroutineScope,
        config: AudioSourceConfig,
        onData: suspend (ShortArray, Int) -> Unit,
        onError: suspend (Throwable) -> Unit
    ): Session

    interface Session : Closeable {
        suspend fun pause()
        suspend fun resume()
        suspend fun stop()
        suspend fun updateNoiseSuppressor(enabled: Boolean)
        fun elapsedMillis(): Long
        val bufferSize: Int
        val fileHintExtension: String get() = "ogg"
    }
}

/**
 * Contract for encoding PCM samples into a persisted representation.
 */
interface IEncoder : Closeable {
    suspend fun start(sampleRate: Int, channelCount: Int)
    suspend fun encode(buffer: ShortArray, length: Int)
    suspend fun finalizeEncoding()
    val fileExtension: String get() = "ogg"
}

/**
 * The orchestrator for routing audio samples from [IAudioSource] into an [IEncoder].
 */
class AudioGateway(
    private val audioSource: IAudioSource,
    private val encoderFactory: (File) -> IEncoder,
    private val cacheDirProvider: () -> File,
    private val ioDispatcher: CoroutineDispatcher
) {

    suspend fun startRecording(
        scope: CoroutineScope,
        gain: Float,
        noiseSuppression: Boolean,
        onPeak: (Float) -> Unit,
        onError: (Throwable) -> Unit
    ): RecordingSession {
        val config = AudioSourceConfig(noiseSuppressorEnabled = noiseSuppression)
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val file = createTempFile(config)
        val encoder = encoderFactory(file)
        val gainHolder = GainHolder(gain)
        val recorderSession = try {
            audioSource.start(
                scope = sessionScope,
                config = config,
                onData = { buffer, length ->
                    val peak = applyGain(buffer, length, gainHolder)
                    encodeSafely(encoder, buffer, length, peak, onPeak, onError)
                },
                onError = { throwable ->
                    onError(throwable)
                }
            )
        } catch (t: Throwable) {
            encoder.close()
            if (!file.delete()) {
                // ignored - best effort cleanup
            }
            throw t
        }

        withContext(ioDispatcher) {
            encoder.start(config.sampleRate, config.channelCount)
        }

        return RecordingSession(
            scope = sessionScope,
            encoder = encoder,
            audioSession = recorderSession,
            file = file,
            gainHolder = gainHolder,
            ioDispatcher = ioDispatcher
        ).also {
            if (noiseSuppression != config.noiseSuppressorEnabled) {
                sessionScope.launch(ioDispatcher) {
                    recorderSession.updateNoiseSuppressor(noiseSuppression)
                }
            }
        }
    }

    private suspend fun encodeSafely(
        encoder: IEncoder,
        buffer: ShortArray,
        length: Int,
        peak: Float,
        onPeak: (Float) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            encoder.encode(buffer, length)
            onPeak(peak)
        } catch (t: Throwable) {
            onError(t)
        }
    }

    private fun applyGain(buffer: ShortArray, length: Int, gainHolder: GainHolder): Float {
        val gain = gainHolder.value
        var max = 1e-6f
        if (gain != 1f) {
            for (i in 0 until length) {
                val sample = (buffer[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                buffer[i] = sample
                val absValue = abs(sample.toInt())
                if (absValue > max) {
                    max = absValue.toFloat()
                }
            }
        } else {
            for (i in 0 until length) {
                val absValue = abs(buffer[i].toInt())
                if (absValue > max) {
                    max = absValue.toFloat()
                }
            }
        }
        val normalized = max / Short.MAX_VALUE.toFloat()
        return 20f * log10(normalized.coerceIn(1e-6f, 1f))
    }

    private fun createTempFile(config: AudioSourceConfig): File {
        val dir = cacheDirProvider()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File.createTempFile("rec_", ".${audioSourceExtension(config)}", dir)
    }

    private fun audioSourceExtension(config: AudioSourceConfig): String {
        return "ogg"
    }

    class RecordingSession internal constructor(
        private val scope: CoroutineScope,
        private val encoder: IEncoder,
        private val audioSession: IAudioSource.Session,
        val file: File,
        private val gainHolder: GainHolder,
        private val ioDispatcher: CoroutineDispatcher
    ) : Closeable {

        private val closed = AtomicBoolean(false)
        private val finalized = AtomicBoolean(false)
        private val mutex = Mutex()

        suspend fun pause() {
            audioSession.pause()
        }

        suspend fun resume() {
            audioSession.resume()
        }

        suspend fun stopAndFinalize(): File {
            mutex.withLock {
                if (finalized.compareAndSet(false, true)) {
                    audioSession.stop()
                    scope.coroutineContext[Job]?.cancel()
                    withContext(ioDispatcher) {
                        encoder.finalizeEncoding()
                    }
                }
            }
            close()
            return file
        }

        suspend fun cancelAndDelete() {
            mutex.withLock {
                if (finalized.compareAndSet(false, true)) {
                    audioSession.stop()
                    scope.coroutineContext[Job]?.cancel()
                }
            }
            close()
            if (file.exists()) {
                file.delete()
            }
        }

        suspend fun updateGain(value: Float) {
            gainHolder.value = value.coerceIn(0.1f, 3f)
        }

        suspend fun updateNoiseSuppressor(enabled: Boolean) {
            audioSession.updateNoiseSuppressor(enabled)
        }

        fun elapsedMillis(): Long = audioSession.elapsedMillis()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    encoder.close()
                } catch (_: IOException) {
                }
                try {
                    audioSession.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private class GainHolder(initial: Float) {
        @Volatile
        var value: Float = initial
    }
}

/**
 * Concrete [IAudioSource] implementation backed by [AudioRecord].
 */
class AndroidAudioSource(
    private val ioDispatcher: CoroutineDispatcher
) : IAudioSource {

    override suspend fun start(
        scope: CoroutineScope,
        config: AudioSourceConfig,
        onData: suspend (ShortArray, Int) -> Unit,
        onError: suspend (Throwable) -> Unit
    ): IAudioSource.Session {
        val bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            if (config.channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            config.encoding
        ).coerceAtLeast(config.sampleRate / 10)

        val audioRecord = createAudioRecord(config, bufferSize)
        val session = AndroidAudioSession(
            scope = scope,
            audioRecord = audioRecord,
            bufferSize = bufferSize,
            config = config,
            ioDispatcher = ioDispatcher,
            onData = onData,
            onError = onError
        )
        session.start()
        return session
    }

    private fun createAudioRecord(config: AudioSourceConfig, bufferSize: Int): AudioRecord {
        val channelConfig = if (config.channelCount == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        val format = AudioFormat.Builder()
            .setSampleRate(config.sampleRate)
            .setEncoding(config.encoding)
            .setChannelMask(channelConfig)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setBufferSizeInBytes(bufferSize)
                .setAudioFormat(format)
                .setAudioAttributes(attributes)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                config.sampleRate,
                channelConfig,
                config.encoding,
                bufferSize
            )
        }
    }

    private class AndroidAudioSession(
        private val scope: CoroutineScope,
        private val audioRecord: AudioRecord,
        override val bufferSize: Int,
        private val config: AudioSourceConfig,
        private val ioDispatcher: CoroutineDispatcher,
        private val onData: suspend (ShortArray, Int) -> Unit,
        private val onError: suspend (Throwable) -> Unit
    ) : IAudioSource.Session {

        private val job = SupervisorJob()
        private val sessionScope = CoroutineScope(scope.coroutineContext + job)
        private val closed = AtomicBoolean(false)
        private val paused = AtomicBoolean(false)
        private var agc: AutomaticGainControl? = null
        private var ns: NoiseSuppressor? = null
        private val buffer = ShortArray(bufferSize / 2)
        private var startElapsed = 0L
        private var accumulated = 0L
        private var lastResume = 0L

        fun start() {
            startElapsed = SystemClock.elapsedRealtime()
            lastResume = startElapsed
            sessionScope.launch(ioDispatcher) {
                setupFx()
                audioRecord.startRecording()
                readLoop()
            }
        }

        private suspend fun readLoop() {
            while (sessionScope.isActive && !closed.get()) {
                if (paused.get()) {
                    delay(16)
                    continue
                }
                val read = withContext(ioDispatcher) {
                    audioRecord.read(buffer, 0, buffer.size)
                }
                if (read > 0) {
                    try {
                        onData(buffer, read)
                    } catch (t: Throwable) {
                        onError(t)
                    }
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                    onError(IOException("AudioRecord read error: $read"))
                    delay(50)
                } else {
                    delay(10)
                }
            }
        }

        private fun setupFx() {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(audioRecord.audioSessionId)
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(audioRecord.audioSessionId)?.apply {
                    enabled = config.noiseSuppressorEnabled
                }
            }
        }

        override suspend fun pause() {
            if (paused.compareAndSet(false, true)) {
                withContext(ioDispatcher) {
                    audioRecord.stop()
                }
                accumulated += SystemClock.elapsedRealtime() - lastResume
            }
        }

        override suspend fun resume() {
            if (paused.compareAndSet(true, false)) {
                lastResume = SystemClock.elapsedRealtime()
                withContext(ioDispatcher) {
                    audioRecord.startRecording()
                }
            }
        }

        override suspend fun stop() {
            if (closed.compareAndSet(false, true)) {
                try {
                    withContext(ioDispatcher) {
                        audioRecord.stop()
                    }
                } finally {
                    accumulated += SystemClock.elapsedRealtime() - lastResume
                    sessionScope.coroutineContext[Job]?.cancel()
                }
            }
        }

        override suspend fun updateNoiseSuppressor(enabled: Boolean) {
            withContext(ioDispatcher) {
                ns?.enabled = enabled
            }
        }

        override fun elapsedMillis(): Long {
            val base = accumulated
            return if (paused.get()) {
                base
            } else {
                base + (SystemClock.elapsedRealtime() - lastResume)
            }
        }

        override fun close() {
            try {
                audioRecord.release()
            } catch (_: Exception) {
            }
            agc?.release()
            ns?.release()
        }
    }
}

/**
 * Encoder that attempts to use OPUS (via [MediaCodec]) and falls back to a custom PCM writer.
 */
class OpusEncoder(
    private val outputFile: File,
    private val ioDispatcher: CoroutineDispatcher
) : IEncoder {

    private var codec: MediaCodec? = null
    private var outputStream: BufferedOutputStream? = null
    private var started = false

    override suspend fun start(sampleRate: Int, channelCount: Int) {
        withContext(ioDispatcher) {
            val codecInfo = findOpusCodec()
            codec = codecInfo?.let { info ->
                tryCreateCodec(info, sampleRate, channelCount)
            }
            if (codec == null) {
                setupFallback(sampleRate, channelCount)
            }
            started = true
        }
    }

    override suspend fun encode(buffer: ShortArray, length: Int) {
        if (!started) return
        val codec = codec
        if (codec != null) {
            encodeWithCodec(codec, buffer, length)
        } else {
            withContext(ioDispatcher) {
                outputStream?.write(toLittleEndianBytes(buffer, length))
            }
        }
    }

    override suspend fun finalizeEncoding() {
        withContext(ioDispatcher) {
            codec?.stop()
            codec?.release()
            codec = null
            outputStream?.flush()
            outputStream?.close()
            outputStream = null
        }
    }

    override fun close() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        codec?.release()
        codec = null
        try {
            outputStream?.close()
        } catch (_: IOException) {
        }
        outputStream = null
    }

    private fun encodeWithCodec(codec: MediaCodec, buffer: ShortArray, length: Int) {
        // Minimal codec loop, fallback to PCM when buffer queue is saturated.
        val inputBufferIndex = codec.dequeueInputBuffer(10_000)
        if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
            inputBuffer.clear()
            val byteArray = toLittleEndianBytes(buffer, length)
            inputBuffer.put(byteArray)
            val presentationTimeUs = SystemClock.elapsedRealtimeNanos() / 1000
            codec.queueInputBuffer(inputBufferIndex, 0, byteArray.size, presentationTimeUs, 0)
        }
        val bufferInfo = MediaCodec.BufferInfo()
        var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        if (outputBufferIndex >= 0) {
            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
            if (outputBuffer != null && bufferInfo.size > 0) {
                val bytes = ByteArray(bufferInfo.size)
                outputBuffer.get(bytes)
                outputBuffer.clear()
                appendToFile(bytes)
            }
            codec.releaseOutputBuffer(outputBufferIndex, false)
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // ignore for now
        }
    }

    private fun appendToFile(bytes: ByteArray) {
        if (outputStream == null) {
            outputStream = BufferedOutputStream(FileOutputStream(outputFile, true))
        }
        outputStream?.write(bytes)
    }

    private suspend fun setupFallback(sampleRate: Int, channelCount: Int) {
        withContext(ioDispatcher) {
            outputStream = BufferedOutputStream(FileOutputStream(outputFile, false))
            writeFakeOggHeader(sampleRate, channelCount)
        }
    }

    private fun writeFakeOggHeader(sampleRate: Int, channelCount: Int) {
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("OggSFAKE".toByteArray())
        buffer.putInt(sampleRate)
        buffer.putInt(channelCount)
        buffer.putInt(ENCODING)
        buffer.putLong(System.currentTimeMillis())
        appendToFile(buffer.array())
    }

    private fun toLittleEndianBytes(buffer: ShortArray, length: Int): ByteArray {
        val byteBuffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) {
            byteBuffer.putShort(buffer[i])
        }
        return byteBuffer.array()
    }

    private fun findOpusCodec(): MediaCodecInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null
        }
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("audio/opus", ignoreCase = true) }
        }
    }

    private fun tryCreateCodec(
        codecInfo: MediaCodecInfo,
        sampleRate: Int,
        channelCount: Int
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            candidate = MediaCodec.createByCodecName(codecInfo.name)
            candidate.apply {
                val format = android.media.MediaFormat.createAudioFormat(
                    "audio/opus",
                    sampleRate,
                    channelCount
                )
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (_: Throwable) {
            candidate?.run {
                runCatching { stop() }
                runCatching { release() }
            }
            null
        }
    }
}

