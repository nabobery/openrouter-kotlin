@file:OptIn(OpenRouterExperimentalApi::class)

package com.nabobery.openrouter.io

import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.sdkgen.runtime.SdkBufferLimitExceededException
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.assertClosedNormally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ByteStreamsTest {
    @Test
    fun byteStreamOfDeliversChunksAndReportsEof() = runTest {
        val payload = ByteArray(3 * 1024) { (it % 251).toByte() }
        val stream = byteStreamOf(payload)
        val c1 = stream.readChunk(1024)
        val c2 = stream.readChunk(1024)
        val c3 = stream.readChunk(1024)
        assertEquals(1024, c1?.size)
        assertEquals(1024, c2?.size)
        assertEquals(1024, c3?.size)
        assertEquals(null, stream.readChunk(1024))
        assertContentEquals(payload, (c1!! + c2!! + c3!!))
    }

    @Test
    fun readAllBytesConcatenatesAndCloses() = runTest {
        val stream = FakeByteStream(listOf("Hel".encodeToByteArray(), "lo".encodeToByteArray()))
        val bytes = stream.readAllBytes()
        assertEquals("Hello", bytes.decodeToString())
        assertTrue(stream.closed)
    }

    @Test
    fun readAllBytesBoundIsEnforcedAndClosesTheStream() = runTest {
        val stream = FakeByteStream(listOf(ByteArray(10) { 1 }))
        val failure = assertFailsWith<SdkBufferLimitExceededException> { stream.readAllBytes(maxBytes = 4) }
        assertTrue(stream.closed)
        assertEquals(failure, stream.closeCause)
    }

    @Test
    fun asFlowClosesOnCompletion() = runTest {
        val stream = FakeByteStream(listOf("a".encodeToByteArray(), "b".encodeToByteArray()))
        val chunks = stream.asFlow().toList().map { it.decodeToString() }
        assertEquals(listOf("a", "b"), chunks)
        stream.assertClosedNormally()
    }

    @Test
    fun asFlowClosesWithCancellationCause() = runTest {
        val stream = FakeByteStream(List(5) { "x".encodeToByteArray() })
        // take(1) cancels the upstream after the first chunk; the flow's finally closes with the cancellation.
        stream.asFlow().take(1).collect { }
        assertTrue(stream.closed)
        assertTrue(stream.closeCause is CancellationException)
    }

    @Test
    fun readChunkWithMaxIntDoesNotOverflow() = runTest {
        // Regression: a maxBytes near Int.MAX_VALUE must not overflow `position + maxBytes` into a negative index
        // (which would make copyOfRange throw). The stream delivers everything in one chunk, then reports EOF.
        val stream = byteStreamOf("hi".encodeToByteArray())
        assertEquals("hi", stream.readChunk(Int.MAX_VALUE)?.decodeToString())
        assertEquals(null, stream.readChunk(Int.MAX_VALUE))
    }

    @Test
    fun asFlowClosesWithDownstreamFailure() = runTest {
        val stream = FakeByteStream(listOf("a".encodeToByteArray()))
        val boom = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> {
            stream.asFlow().collect { throw boom }
        }
        assertEquals(boom, thrown)
        assertEquals(boom, stream.closeCause)
    }

    @Test
    fun secondCollectionOfAsFlowFails() = runTest {
        val stream = byteStreamOf("hi".encodeToByteArray())
        stream.asFlow().toList() // consumes and closes the one-shot stream
        assertFailsWith<IllegalStateException> { stream.asFlow().toList() }
    }
}
