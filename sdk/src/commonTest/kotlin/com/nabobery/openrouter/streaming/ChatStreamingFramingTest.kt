package com.nabobery.openrouter.streaming

import com.nabobery.openrouter.ChatFinishReasonEnum
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.openrouter.chat.ChatStreamEvent
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.stream
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkSerializationException
import com.nabobery.sdkgen.runtime.SdkStreamingException
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import com.nabobery.sdkgen.testing.assertClosedNormally
import com.nabobery.sdkgen.testing.assertClosedWith
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The SSE framing and decoding contract matrix, driven through the curated `client.chat.stream(...)` (which
 * wraps the generated `sendChatCompletionRequestStream`). The incremental SSE engine is owned by the runtime;
 * this matrix re-proves its guarantees *through the generated OpenRouter chat operation* on the fake transport.
 * Chunk boundaries are controlled via [FakeByteStream] (one element delivered per read).
 */
class ChatStreamingFramingTest {
    private val credential = OpenRouterCredentials.static("sk-or-frame")
    private val sse = listOf(SdkHeader("Content-Type", "text/event-stream"))
    private val json = listOf(SdkHeader("Content-Type", "application/json"))

    private fun client(body: FakeByteStream): Pair<OpenRouter, FakeByteStream> {
        val transport = FakeTransport(TransportCapabilities(supportsStreaming = true)).enqueueResponse(200, sse, body)
        return OpenRouter(credential = credential, transport = transport) to body
    }

    private fun bytes(vararg fragments: String) = FakeByteStream(fragments.map { it.encodeToByteArray() })

    private suspend fun deltas(events: List<ChatStreamEvent>): List<String> =
        events.filterIsInstance<ChatStreamEvent.Chunk>().flatMap { c ->
            c.chunk.choices.mapNotNull { it.delta.content }
        }

    @Test
    fun oneEventPerChunkAndManyEventsInOneChunk() = runTest {
        val body =
            FakeByteStream(
                listOf(
                    SseWireFixtures.chatChunk(content = "a").encodeToByteArray(),
                    (
                        SseWireFixtures.chatChunk(content = "b") +
                            SseWireFixtures.chatChunk(content = "c") +
                            SseWireFixtures.DONE
                        ).encodeToByteArray(),
                ),
            )
        val (client, stream) = client(body)
        val events = client.chat.stream(SseWireFixtures.userChatRequest()).toList()
        assertEquals(listOf("a", "b", "c"), deltas(events))
        stream.assertClosedNormally()
    }

    @Test
    fun delimiterSplitAcrossChunks() = runTest {
        val event = SseWireFixtures.chatChunk(content = "x")
        val body = bytes(event.dropLast(1), "\n" + SseWireFixtures.DONE)
        val (client, _) = client(body)
        assertEquals(listOf("x"), deltas(client.chat.stream(SseWireFixtures.userChatRequest()).toList()))
    }

    @Test
    fun utf8CodePointSplitAcrossChunks() = runTest {
        val full = (SseWireFixtures.chatChunk(content = "héllo 🦀") + SseWireFixtures.DONE).encodeToByteArray()
        // One byte per read forces splits inside the 2-byte 'é' and the 4-byte '🦀'.
        val body = FakeByteStream(full.map { byteArrayOf(it) })
        val (client, _) = client(body)
        assertEquals(listOf("héllo 🦀"), deltas(client.chat.stream(SseWireFixtures.userChatRequest()).toList()))
    }

    @Test
    fun crlfLineEndings() = runTest {
        val body =
            bytes(
                SseWireFixtures.event(SseWireFixtures.chatChunkJson(content = "a"), lineEnding = "\r\n"),
                SseWireFixtures.event(SseWireFixtures.chatChunkJson(content = "b"), lineEnding = "\r\n"),
                "data: [DONE]\r\n\r\n",
            )
        val (client, _) = client(body)
        assertEquals(listOf("a", "b"), deltas(client.chat.stream(SseWireFixtures.userChatRequest()).toList()))
    }

    @Test
    fun commentsRetryIdAndBlankFieldsAreIgnored() = runTest {
        val body =
            bytes(
                SseWireFixtures.COMMENT,
                "retry: 1000\nid: 7\ndata:\n\n",
                SseWireFixtures.chatChunk(content = "x"),
                SseWireFixtures.DONE,
            )
        val (client, _) = client(body)
        assertEquals(listOf("x"), deltas(client.chat.stream(SseWireFixtures.userChatRequest()).toList()))
    }

    @Test
    fun multilineDataIsJoined() = runTest {
        val body =
            bytes(
                SseWireFixtures.multilineEvent(SseWireFixtures.chatChunkJson(content = "joined")),
                SseWireFixtures.DONE,
            )
        val (client, _) = client(body)
        assertEquals(listOf("joined"), deltas(client.chat.stream(SseWireFixtures.userChatRequest()).toList()))
    }

    @Test
    fun metadataOnlyEventsAreSkipped() = runTest {
        val body = bytes("event: ping\n\n")
        val (client, stream) = client(body)
        assertEquals(emptyList(), client.chat.stream(SseWireFixtures.userChatRequest()).toList())
        stream.assertClosedNormally()
    }

    @Test
    fun doneSentinelEndsTheStreamWithoutEmission() = runTest {
        val body =
            bytes(
                SseWireFixtures.chatChunk(content = "only"),
                SseWireFixtures.DONE,
                SseWireFixtures.chatChunk(content = "afterDone"),
            )
        val (client, stream) = client(body)
        assertEquals(listOf("only"), deltas(client.chat.stream(SseWireFixtures.userChatRequest()).toList()))
        stream.assertClosedNormally()
    }

    @Test
    fun eofWithoutSentinelCompletesNormally() = runTest {
        val body = bytes(SseWireFixtures.chatChunk(content = "x"))
        val (client, stream) = client(body)
        assertEquals(listOf("x"), deltas(client.chat.stream(SseWireFixtures.userChatRequest()).toList()))
        stream.assertClosedNormally()
    }

    @Test
    fun usageOnlyFinalChunkIsDelivered() = runTest {
        val body =
            bytes(
                SseWireFixtures.chatChunk(content = "Hi"),
                SseWireFixtures.chatChunk(content = null, usage = SseWireFixtures.USAGE_JSON, choices = "[]"),
                SseWireFixtures.DONE,
            )
        val (client, _) = client(body)
        val events = client.chat.stream(SseWireFixtures.userChatRequest()).toList()
        val last = assertIs<ChatStreamEvent.Chunk>(events.last())
        assertEquals(12, last.chunk.usage?.totalTokens)
        assertEquals(0.000012, last.chunk.usage?.cost)
    }

    @Test
    fun nonSuccessBeforeStreamIsTypedApiException() = runTest {
        val errorBody =
            FakeByteStream(
                listOf("{\"error\":{\"code\":402,\"message\":\"payment required\"}}".encodeToByteArray()),
            )
        val transport = FakeTransport(
            TransportCapabilities(supportsStreaming = true),
        ).enqueueResponse(402, json, errorBody)
        val client = OpenRouter(credential = credential, transport = transport)
        val received = mutableListOf<ChatStreamEvent>()
        val ex =
            assertFailsWith<ChatClient.SendChatCompletionRequestApiException> {
                client.chat.stream(SseWireFixtures.userChatRequest()).collect { received += it }
            }
        assertEquals(402, ex.statusCode)
        assertTrue(received.isEmpty())
    }

    @Test
    fun malformedEventAfterValidEventsFailsWithBoundedDiagnostics() = runTest {
        val malformed = "data: " + "{not json".repeat(23_000) + "\n\n" // ~200 KiB invalid JSON on one data line
        val body = bytes(SseWireFixtures.chatChunk(content = "first"), malformed)
        val (client, stream) = client(body)
        val received = mutableListOf<ChatStreamEvent>()
        val ex =
            assertFailsWith<SdkSerializationException> {
                client.chat.stream(SseWireFixtures.userChatRequest()).collect { received += it }
            }
        assertEquals(1, received.size)
        assertTrue(ex.toString().length < 70_000, "diagnostics must be bounded, was ${ex.toString().length}")
        stream.assertClosedWith(ex)
    }

    @Test
    fun eventOverByteBudgetIsRejectedWithoutBuffering() = runTest {
        val oversized =
            "data: ".encodeToByteArray() +
                ByteArray(1_100_000) { 'a'.code.toByte() } +
                "\n\n".encodeToByteArray()
        val body = FakeByteStream(listOf(oversized))
        val (client, stream) = client(body)
        val ex =
            assertFailsWith<SdkStreamingException> {
                client.chat.stream(SseWireFixtures.userChatRequest()).collect { }
            }
        stream.assertClosedWith(ex)
    }

    @Test
    fun unknownFinishReasonIsPreserved() = runTest {
        val body =
            bytes(SseWireFixtures.chatChunk(content = "x", finishReason = "totally_new"), SseWireFixtures.DONE)
        val (client, _) = client(body)
        val events = client.chat.stream(SseWireFixtures.userChatRequest()).toList()
        val finish = assertIs<ChatStreamEvent.Chunk>(events.single()).chunk.choices.single().finishReason
        val unknown = assertIs<ChatFinishReasonEnum.SdkUnknown>(finish)
        assertEquals("totally_new", unknown.value)
    }
}
