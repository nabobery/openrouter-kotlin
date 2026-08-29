package com.nabobery.openrouter.streaming

import com.nabobery.openrouter.Inputs
import com.nabobery.openrouter.MessagesStreamEvents
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.RequestDeadlines
import com.nabobery.openrouter.StreamEvents
import com.nabobery.openrouter.anthropicmessages.create
import com.nabobery.openrouter.anthropicmessages.stream
import com.nabobery.openrouter.anthropicmessages.textDeltas
import com.nabobery.openrouter.anthropicmessages.userMessageParam
import com.nabobery.openrouter.betaresponses.create
import com.nabobery.openrouter.betaresponses.outputTextDeltas
import com.nabobery.openrouter.betaresponses.stream
import com.nabobery.openrouter.messagesRequest
import com.nabobery.openrouter.responsesRequest
import com.nabobery.sdkgen.runtime.SdkByteStream
import com.nabobery.sdkgen.runtime.SdkRequest
import com.nabobery.sdkgen.runtime.SdkRequestBody
import com.nabobery.sdkgen.runtime.SdkTimeoutException
import com.nabobery.sdkgen.runtime.TimeoutPhase
import com.nabobery.sdkgen.testing.ChunkGate
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Curated Responses and Anthropic Messages contract: curated and exact calls serialize identical payloads, the
 * streams expose the generated typed event unions (with typed errors delivered as values), the text-delta helpers
 * work, and the runtime streaming semantics (idle deadline, cancellation) are preserved through the wrappers.
 */
class InferenceStreamingContractTest {
    private val credential = OpenRouterCredentials.static("sk-or-inference")
    private val json = jsonHeaders

    private fun openRouter(transport: FakeTransport) = OpenRouter(credential = credential, transport = transport)

    private fun emptyBody() = FakeByteStream(listOf("{}".encodeToByteArray()))

    private suspend fun bodyBytes(request: SdkRequest): ByteArray = consume(request.body)

    private suspend fun consume(body: SdkRequestBody?): ByteArray = when (body) {
        null -> ByteArray(0)
        is SdkRequestBody.Bytes -> body.bytes
        is SdkRequestBody.OneShot -> readAll(body.stream)
        is SdkRequestBody.ReplayFactory -> consume(body.create())
    }

    private suspend fun readAll(stream: SdkByteStream): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        while (true) chunks += stream.readChunk() ?: break
        stream.close()
        return chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    }

    private fun responsesTextDelta(content: String) = SseWireFixtures.responsesEvent(
        "response.output_text.delta",
        "\"content_index\":0,\"delta\":\"$content\",\"item_id\":\"item-1\"," +
            "\"logprobs\":[],\"output_index\":0,\"sequence_number\":1",
    )

    private fun messagesTextDelta(content: String) = SseWireFixtures.messagesEvent(
        "content_block_delta",
        "{\"delta\":{\"text\":\"$content\",\"type\":\"text_delta\"},\"index\":0,\"type\":\"content_block_delta\"}",
    )

    // ---- Responses ----

    @Test
    fun responsesCreatePayloadMatchesExactCall() = runTest {
        val transport = FakeTransport().enqueueResponse(
            200,
            json,
            emptyBody(),
        ).enqueueResponse(200, json, emptyBody())
        val client = openRouter(transport)

        runCatching { client.betaResponses.create(model = "test/model", input = "hi") }
        runCatching {
            client.betaResponses.createResponses(
                responsesRequest {
                    model = "test/model"
                    input = Inputs.fromRaw(JsonPrimitive("hi"))
                },
            )
        }

        assertContentEquals(bodyBytes(transport.capturedRequests[0]), bodyBytes(transport.capturedRequests[1]))
    }

    @Test
    fun responsesStreamYieldsTypedEventsAndTextDeltas() = runTest {
        val server = SseFakeServer()
        server.sse(responsesTextDelta("Hel"), responsesTextDelta("lo"), SseWireFixtures.DONE)
        val client = openRouterOver(server)

        val events = client.betaResponses.stream(model = "test/model", input = "hi").toList()
        events.forEach { assertIs<StreamEvents.TextDeltaEvent>(it) }

        val server2 = SseFakeServer()
        server2.sse(responsesTextDelta("Hel"), responsesTextDelta("lo"), SseWireFixtures.DONE)
        val deltas = openRouterOver(
            server2,
        ).betaResponses.stream(model = "test/model", input = "hi").outputTextDeltas().toList()
        assertEquals(listOf("Hel", "lo"), deltas)
    }

    @Test
    fun responsesStreamCompletesAtEofWithoutSentinel() = runTest {
        // Pins that the Responses stream completes normally on a plain EOF (no `[DONE]` sentinel) after
        // delivering its typed events, throwing nothing. (Typed error events being modeled as values is
        // pinned separately in responsesErrorEventIsAValueWithMessage.)
        val server = SseFakeServer()
        val stream = server.sse(responsesTextDelta("Hel"), responsesTextDelta("lo")) // EOF, no DONE
        val client = openRouterOver(server)

        val events = client.betaResponses.stream(model = "test/model", input = "hi").toList()
        assertEquals(2, events.size)
        events.forEach { assertIs<StreamEvents.TextDeltaEvent>(it) }
        assertTrue(stream.closed)
    }

    @Test
    fun responsesErrorEventIsAValueWithMessage() = runTest {
        // A `response.error` event is a terminal in-band value, not a thrown exception: the `StreamEvents`
        // union models it as StreamEvents.ErrorEvent. The generated inspection union selects this branch only
        // when every declared property is present with its declared JSON type — including the string-typed
        // (but nullable) `code`/`param` — so a JSON `null` (or a missing key) matches zero branches and the
        // decode throws. The string-valued shape below is what actually selects the ErrorEvent branch.
        val errorEvent = SseWireFixtures.responsesEvent(
            "error",
            "\"code\":\"server_error\",\"message\":\"upstream exploded\",\"param\":\"input\",\"sequence_number\":7",
        )
        val server = SseFakeServer()
        val stream = server.sse(responsesTextDelta("Hel"), errorEvent) // EOF after the error event
        val client = openRouterOver(server)

        val events = client.betaResponses.stream(model = "test/model", input = "hi").toList()
        assertEquals(2, events.size)
        assertIs<StreamEvents.TextDeltaEvent>(events[0])
        val error = assertIs<StreamEvents.ErrorEvent>(events[1])
        assertEquals("upstream exploded", error.message)
        assertEquals("server_error", error.code)
        assertTrue(stream.closed)
    }

    @Test
    fun responsesCreateRejectsStreamTrueBeforeAnyRequestIsSent() = runTest {
        val transport = FakeTransport() // no response enqueued: a sent request would itself fail
        val client = openRouter(transport)

        assertFailsWith<IllegalArgumentException> {
            client.betaResponses.create(model = "test/model", input = "hi") { stream = true }
        }
        assertEquals(0, transport.capturedRequests.size)
    }

    @Test
    fun responsesIdleDeadlineFiresThroughCuratedStream() = runTest {
        val gate = ChunkGate()
        val server = SseFakeServer()
        val stream = server.sse(
            responsesTextDelta("Hel"),
            responsesTextDelta("lo"),
            SseWireFixtures.DONE,
            gate = gate,
        )
        val client = openRouterOver(server, deadlines = RequestDeadlines(streamIdle = 2.seconds))

        val ex =
            assertFailsWith<SdkTimeoutException> {
                client.betaResponses.stream(
                    request = responsesRequest {
                        model = "test/model"
                    },
                    options = client.options(),
                ).collect { }
            }
        assertEquals(TimeoutPhase.STREAM_IDLE, ex.phase)
        assertTrue(stream.closed)
    }

    // ---- Messages ----

    @Test
    fun messagesCreatePayloadMatchesExactCall() = runTest {
        val transport = FakeTransport().enqueueResponse(
            200,
            json,
            emptyBody(),
        ).enqueueResponse(200, json, emptyBody())
        val client = openRouter(transport)

        runCatching {
            client.anthropicMessages.create(
                model = "test/model",
                maxTokens = 16,
                messages = listOf(userMessageParam("hi")),
            )
        }
        runCatching {
            client.anthropicMessages.createMessages(
                messagesRequest {
                    model = "test/model"
                    maxTokens = 16
                    messages = listOf(userMessageParam("hi"))
                },
            )
        }

        assertContentEquals(bodyBytes(transport.capturedRequests[0]), bodyBytes(transport.capturedRequests[1]))
    }

    @Test
    fun messagesStreamYieldsTextDeltas() = runTest {
        val server = SseFakeServer()
        server.sse(messagesTextDelta("Hel"), messagesTextDelta("lo"), SseWireFixtures.DONE)
        val client = openRouterOver(server)

        val deltas =
            client.anthropicMessages
                .stream(model = "test/model", maxTokens = 16, messages = listOf(userMessageParam("hi")))
                .textDeltas()
                .toList()
        assertEquals(listOf("Hel", "lo"), deltas)
    }

    @Test
    fun messagesErrorEventIsAValueWithErrorType() = runTest {
        val server = SseFakeServer()
        server.sse(
            messagesTextDelta("Hel"),
            SseWireFixtures.messagesEvent(
                "error",
                "{\"error\":{\"message\":\"overloaded\",\"type\":\"overloaded_error\",\"error_type\":\"provider_unavailable\"},\"type\":\"error\"}",
            ),
        )
        val client = openRouterOver(server)

        val events =
            client.anthropicMessages
                .stream(model = "test/model", maxTokens = 16, messages = listOf(userMessageParam("hi")))
                .toList()
        val error = assertIs<MessagesStreamEvents.MessagesErrorEvent>(events.last())
        assertEquals("overloaded", error.error.message)
        assertEquals("provider_unavailable", error.error.errorType?.value)
    }

    @Test
    fun messagesCreateRejectsStreamTrueBeforeAnyRequestIsSent() = runTest {
        val transport = FakeTransport() // no response enqueued: a sent request would itself fail
        val client = openRouter(transport)

        assertFailsWith<IllegalArgumentException> {
            client.anthropicMessages.create(
                model = "test/model",
                maxTokens = 16,
                messages = listOf(userMessageParam("hi")),
            ) { stream = true }
        }
        assertEquals(0, transport.capturedRequests.size)
    }

    @Test
    fun messagesCancellationClosesBody() = runTest {
        val gate = ChunkGate()
        val server = SseFakeServer()
        val stream = server.sse(
            messagesTextDelta("Hel"),
            messagesTextDelta("lo"),
            SseWireFixtures.DONE,
            gate = gate,
        )
        val client = openRouterOver(server)

        val received = Channel<MessagesStreamEvents>(Channel.UNLIMITED)
        val job =
            launch {
                client.anthropicMessages
                    .stream(model = "test/model", maxTokens = 16, messages = listOf(userMessageParam("hi")))
                    .collect { received.send(it) }
            }
        received.receive()
        job.cancelAndJoin()
        assertTrue(stream.closed)
    }
}
