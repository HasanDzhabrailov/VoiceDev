package com.voicedev.vocedev.waveform

import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CACHE_VERSION = 1

/**
 * Provides waveform peaks for a message. Peaks are normalized to 0-255.
 */
interface WaveformProvider {
    fun loadWaveform(messageId: String, uri: Uri): Flow<List<Int>>
}

/**
 * Persistence abstraction to hide Room behind a SOLID boundary.
 */
interface WaveformCache {
    suspend fun read(messageId: String, version: Int): List<Int>?
    suspend fun write(messageId: String, version: Int, peaks: List<Int>)
}

/**
 * Default implementation reading JSON sidecars and falling back to streaming computation.
 */
class DefaultWaveformProvider(
    private val cache: WaveformCache,
    private val openInput: suspend (Uri) -> InputStream,
    private val readSidecar: suspend (Uri) -> String?,
    private val waveformComputer: WaveformComputer,
    private val dispatcher: CoroutineDispatcher,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : WaveformProvider {

    override fun loadWaveform(messageId: String, uri: Uri): Flow<List<Int>> = channelFlow {
        val cached = runCatching { withContext(dispatcher) { cache.read(messageId, CACHE_VERSION) } }.getOrNull()
        if (!cached.isNullOrEmpty()) {
            send(cached)
            return@channelFlow
        }

        val sidecarPeaks = runCatching {
            withContext(dispatcher) { readSidecar(uri)?.let { parseSidecar(it) } }
        }.getOrNull()

        if (!sidecarPeaks.isNullOrEmpty()) {
            withContext(dispatcher) { cache.write(messageId, CACHE_VERSION, sidecarPeaks) }
            send(sidecarPeaks)
            return@channelFlow
        }

        val latest = AtomicReference<List<Int>>(emptyList())
        try {
            withContext(dispatcher) {
                openInput(uri).use { input ->
                    waveformComputer.compute(input).collect { peaks ->
                        latest.set(peaks)
                        this@channelFlow.send(peaks)
                    }
                }
            }

            val toPersist = latest.get()
            if (toPersist.isNotEmpty()) {
                withContext(dispatcher) { cache.write(messageId, CACHE_VERSION, toPersist) }
            }
        } catch (expected: Exception) {
            send(emptyList())
        }
    }

    private fun parseSidecar(raw: String): List<Int> {
        val payload = json.decodeFromString(SidecarPayload.serializer(), raw)
        return payload.peaks.map { it.coerceIn(0, 255) }
    }
}

/**
 * Streaming computer converting audio samples into peaks.
 */
interface WaveformComputer {
    fun compute(input: InputStream): Flow<List<Int>>
}

class StreamingWaveformComputer(
    private val bytesPerChunk: Int = DEFAULT_CHUNK,
    private val windowSize: Int = DEFAULT_WINDOW
) : WaveformComputer {
    override fun compute(input: InputStream): Flow<List<Int>> = channelFlow {
        val accumulator = mutableListOf<Int>()
        val buffer = ByteArray(bytesPerChunk)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            val peaks = bufferToPeaks(buffer, read)
            accumulator += peaks
            send(accumulator.toList())
        }
    }

    private fun bufferToPeaks(buffer: ByteArray, length: Int): List<Int> {
        if (length <= 0) return emptyList()
        val peaks = mutableListOf<Int>()
        var index = 0
        while (index < length) {
            val windowEnd = minOf(index + windowSize, length)
            var max = 0
            for (i in index until windowEnd) {
                val magnitude = buffer[i].toInt() and 0xFF
                if (magnitude > max) {
                    max = magnitude
                }
            }
            peaks += max
            index = windowEnd
        }
        return peaks
    }

    private companion object {
        private const val DEFAULT_CHUNK = 4096
        private const val DEFAULT_WINDOW = 64
    }
}

@Entity(tableName = "waveform_cache")
data class WaveformCacheEntity(
    @PrimaryKey val messageId: String,
    val version: Int,
    val peaks: ByteArray
)

@Dao
interface WaveformCacheDao {
    @Query("SELECT * FROM waveform_cache WHERE messageId = :messageId LIMIT 1")
    suspend fun get(messageId: String): WaveformCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WaveformCacheEntity)
}

@Database(
    entities = [WaveformCacheEntity::class],
    version = 1,
    exportSchema = false
)
abstract class WaveformDatabase : RoomDatabase() {
    abstract fun waveformDao(): WaveformCacheDao
}

class RoomWaveformCache(
    private val dao: WaveformCacheDao
) : WaveformCache {
    override suspend fun read(messageId: String, version: Int): List<Int>? {
        val entity = dao.get(messageId) ?: return null
        if (entity.version != version) return null
        return entity.peaks.map { it.toInt() and 0xFF }
    }

    override suspend fun write(messageId: String, version: Int, peaks: List<Int>) {
        val normalized = peaks.map { it.coerceIn(0, 255) }
        val bytes = ByteArray(normalized.size) { index -> normalized[index].toByte() }
        dao.insert(
            WaveformCacheEntity(
                messageId = messageId,
                version = version,
                peaks = bytes
            )
        )
    }
}

@Serializable
private data class SidecarPayload(
    @SerialName("peaks") val peaks: List<Int> = emptyList()
)
