package com.voicedev.vocedev.waveform

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WaveformProviderTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun returnsCachedPeaks() = runTest(dispatcher) {
        val cache = RecordingCache(readResult = listOf(1, 2, 3))
        var openCalled = false
        val provider = DefaultWaveformProvider(
            cache = cache,
            openInput = {
                openCalled = true
                ByteArrayInputStream(byteArrayOf())
            },
            readSidecar = { null },
            waveformComputer = FakeWaveformComputer(emptyList()),
            dispatcher = testScheduler
        )

        val emissions = provider.loadWaveform("message", Uri.parse("file://audio"))
            .toList(mutableListOf())

        assertEquals(listOf(listOf(1, 2, 3)), emissions)
        assertFalse(openCalled)
        assertNull(cache.lastWrite)
    }

    @Test
    fun readsSidecarAndCaches() = runTest(dispatcher) {
        val cache = RecordingCache()
        val provider = DefaultWaveformProvider(
            cache = cache,
            openInput = { ByteArrayInputStream(byteArrayOf()) },
            readSidecar = { "{\"peaks\":[0,128,255]}" },
            waveformComputer = FakeWaveformComputer(emptyList()),
            dispatcher = testScheduler
        )

        val emissions = provider.loadWaveform("message", Uri.parse("file://audio"))
            .toList(mutableListOf())

        assertEquals(listOf(listOf(0, 128, 255)), emissions)
        assertEquals(listOf(0, 128, 255), cache.lastWrite)
    }

    @Test
    fun computesWaveformWhenSidecarMissing() = runTest(dispatcher) {
        val cache = RecordingCache()
        val provider = DefaultWaveformProvider(
            cache = cache,
            openInput = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) },
            readSidecar = { null },
            waveformComputer = FakeWaveformComputer(
                listOf(
                    listOf(10, 20),
                    listOf(10, 20, 30)
                )
            ),
            dispatcher = testScheduler
        )

        val emissions = provider.loadWaveform("message", Uri.parse("file://audio"))
            .toList(mutableListOf())

        assertEquals(listOf(listOf(10, 20), listOf(10, 20, 30)), emissions)
        assertEquals(listOf(10, 20, 30), cache.lastWrite)
    }
}

private class RecordingCache(
    private val readResult: List<Int>? = null
) : WaveformCache {
    var lastReadKey: String? = null
    var lastWrite: List<Int>? = null

    override suspend fun read(messageId: String, version: Int): List<Int>? {
        lastReadKey = messageId
        return readResult
    }

    override suspend fun write(messageId: String, version: Int, peaks: List<Int>) {
        lastWrite = peaks
    }
}

private class FakeWaveformComputer(
    private val emissions: List<List<Int>>
) : WaveformComputer {
    override fun compute(input: InputStream): Flow<List<Int>> = flow {
        emissions.forEach { emit(it) }
    }
}
