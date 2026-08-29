package com.nabobery.openrouter.chat

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.SdkJson
import com.nabobery.openrouter.chatRequest
import com.nabobery.openrouter.streaming.SseWireFixtures
import com.nabobery.sdkgen.runtime.SdkByteStream
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkRequest
import com.nabobery.sdkgen.runtime.SdkRequestBody
import com.nabobery.sdkgen.runtime.SdkResponseResult
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Pins the curated chat overloads: the golden requirement that curated and exact calls serialize identical
 * bytes, that `stream(...)` forces `"stream": true` without touching other fields, and
 * that in-band stream errors arrive as [ChatStreamEvent.Error] values while the flow completes normally.
 */
class ChatCuratedOverloadsTest {
    private val credential = OpenRouterCredentials.static("sk-or-curated")
    private val json = listOf(SdkHeader("Content-Type", "application/json"))
    private val sse = listOf(SdkHeader("Content-Type", "text/event-stream"))

    private fun chatResultBody() = FakeByteStream(
        listOf(
            (
                "{\"choices\":[],\"created\":1,\"id\":\"c\",\"model\":\"test/model\"," +
                    "\"object\":\"chat.completion\",\"system_fingerprint\":null}"
                ).encodeToByteArray(),
        ),
    )

    private fun sseBody(vararg fragments: String) = FakeByteStream(fragments.map { it.encodeToByteArray() })

    private fun openRouter(transport: FakeTransport) = OpenRouter(credential = credential, transport = transport)

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

    @Test
    fun curatedSendSerializesIdenticallyToExactCall() = runTest {
        val transport =
            FakeTransport()
                .enqueueResponse(200, json, chatResultBody())
                .enqueueResponse(200, json, chatResultBody())
        val client = openRouter(transport)

        client.chat.send(model = "test/model", messages = listOf(userMessage("hi")))
        client.chat.sendChatCompletionRequest(
            chatRequest {
                model = "test/model"
                messages = listOf(userMessage("hi"))
            },
        )

        assertContentEquals(bodyBytes(transport.capturedRequests[0]), bodyBytes(transport.capturedRequests[1]))
    }

    @Test
    fun curatedStreamForcesStreamFlagAndMatchesExactPayload() = runTest {
        val transport =
            FakeTransport(TransportCapabilities(supportsStreaming = true))
                .enqueueResponse(
                    200,
                    sse,
                    sseBody(SseWireFixtures.chatChunk(content = "Hel"), SseWireFixtures.DONE),
                )
                .enqueueResponse(
                    200,
                    sse,
                    sseBody(SseWireFixtures.chatChunk(content = "Hel"), SseWireFixtures.DONE),
                )
        val client = openRouter(transport)

        client.chat.stream(model = "test/model", messages = listOf(userMessage("hi"))).toList()
        client.chat
            .sendChatCompletionRequestStream(
                chatRequest {
                    model = "test/model"
                    messages = listOf(userMessage("hi"))
                    stream = true
                },
            ).toList()

        val curated = bodyBytes(transport.capturedRequests[0])
        assertContentEquals(curated, bodyBytes(transport.capturedRequests[1]))
        val obj = SdkJson.parseToJsonElement(curated.decodeToString()).jsonObject
        assertEquals(JsonPrimitive(true), obj["stream"])
    }

    @Test
    fun streamOnRequestWithoutFlagAddsIt() = runTest {
        val transport =
            FakeTransport(TransportCapabilities(supportsStreaming = true))
                .enqueueResponse(200, sse, sseBody(SseWireFixtures.chatChunk(content = "Hi"), SseWireFixtures.DONE))
        val client = openRouter(transport)

        val request =
            chatRequest {
                model = "test/model"
                messages = listOf(userMessage("hi"))
            }
        client.chat.stream(request).toList()

        val obj = SdkJson.parseToJsonElement(
            bodyBytes(transport.capturedRequests[0]).decodeToString(),
        ).jsonObject
        assertEquals(JsonPrimitive(true), obj["stream"])
        // Every field other than the injected `stream` is untouched.
        assertEquals("test/model", obj["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun chunksBecomeChunkEvents() = runTest {
        val transport =
            FakeTransport(TransportCapabilities(supportsStreaming = true))
                .enqueueResponse(
                    200,
                    sse,
                    sseBody(
                        SseWireFixtures.chatChunk(content = "Hel"),
                        SseWireFixtures.chatChunk(content = "lo"),
                        SseWireFixtures.DONE,
                    ),
                )
        val client = openRouter(transport)

        val events = client.chat.stream(SseWireFixtures.userChatRequest()).toList()
        assertEquals(2, events.size)
        events.forEach { assertIs<ChatStreamEvent.Chunk>(it) }

        val transport2 =
            FakeTransport(TransportCapabilities(supportsStreaming = true))
                .enqueueResponse(
                    200,
                    sse,
                    sseBody(
                        SseWireFixtures.chatChunk(content = "Hel"),
                        SseWireFixtures.chatChunk(content = "lo"),
                        SseWireFixtures.DONE,
                    ),
                )
        val deltas = openRouter(transport2).chat.stream(SseWireFixtures.userChatRequest()).contentDeltas().toList()
        assertEquals(listOf("Hel", "lo"), deltas)
    }

    @Test
    fun midStreamErrorChunkBecomesErrorValueAndCompletes() = runTest {
        val transport =
            FakeTransport(TransportCapabilities(supportsStreaming = true))
                .enqueueResponse(
                    200,
                    sse,
                    sseBody(
                        SseWireFixtures.chatChunk(content = "Hel"),
                        SseWireFixtures.chatChunk(
                            content = "",
                            finishReason = "error",
                            error = SseWireFixtures.MID_STREAM_ERROR_JSON,
                        ),
                        // No DONE: OpenRouter ends the stream after the unified error event (EOF).
                    ),
                )
        val client = openRouter(transport)

        val events = client.chat.stream(SseWireFixtures.userChatRequest()).toList()
        assertIs<ChatStreamEvent.Chunk>(events[0])
        val err = assertIs<ChatStreamEvent.Error>(events[1])
        assertEquals(502, err.error.code)
        assertEquals("provider_unavailable", err.error.metadata?.errorType?.value)
    }

    @Test
    fun configureBlockAppliesToCuratedRequest() = runTest {
        val transport = FakeTransport().enqueueResponse(200, json, chatResultBody())
        val client = openRouter(transport)

        client.chat.send(model = "test/model", messages = listOf(userMessage("hi"))) {
            temperature = 0.0
            maxTokens = 16
        }

        val obj = SdkJson.parseToJsonElement(
            bodyBytes(transport.capturedRequests[0]).decodeToString(),
        ).jsonObject
        assertEquals(0.0, obj["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals(16, obj["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun sendRejectsStreamTrueBeforeAnyRequestIsSent() = runTest {
        // A `send(...)` that sets stream = true would drive an SSE body through the buffered decoder, failing
        // only after a billable request. The guard rejects it up front — nothing reaches the transport.
        val transport = FakeTransport() // no response enqueued: a sent request would itself fail
        val client = openRouter(transport)

        assertFailsWith<IllegalArgumentException> {
            client.chat.send(model = "test/model", messages = listOf(userMessage("hi"))) { stream = true }
        }
        assertFailsWith<IllegalArgumentException> {
            client.chat.send(
                chatRequest {
                    model = "test/model"
                    messages = listOf(userMessage("hi"))
                    stream = true
                },
            )
        }
        assertEquals(0, transport.capturedRequests.size)
    }

    @Test
    fun sendWithResponseExposesGenerationIdHeader() = runTest {
        val transport =
            FakeTransport()
                .enqueueResponse(
                    200,
                    json + SdkHeader("X-Generation-Id", "gen-xyz"),
                    chatResultBody(),
                )
        val client = openRouter(transport)

        val result: SdkResponseResult<ChatClient.SendChatCompletionRequestResponse> =
            client.chat.sendWithResponse(
                chatRequest {
                    model = "test/model"
                    messages = listOf(userMessage("hi"))
                },
            )

        val header = result.headers.firstOrNull { it.name.equals("X-Generation-Id", ignoreCase = true) }
        assertEquals("gen-xyz", assertNotNull(header).value)
    }
}
