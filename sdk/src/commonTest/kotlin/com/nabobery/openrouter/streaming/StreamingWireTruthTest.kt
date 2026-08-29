package com.nabobery.openrouter.streaming

import com.nabobery.openrouter.MessagesStreamEvents
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.StreamEvents
import com.nabobery.openrouter.imageGenerationRequest
import com.nabobery.openrouter.messagesRequest
import com.nabobery.openrouter.responsesRequest
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import com.nabobery.sdkgen.testing.assertClosedNormally
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Wire-truth proof: fed OpenRouter's documented SSE wire format ([SseWireFixtures], top-level
 * payload with no Speakeasy `{ "data": … }` envelope), the four generated `*Stream` operations
 * decode the payload directly into its typed model.
 *
 * Before the sse-payload overlay (Task 1, RED) every operation threw `SdkSerializationException`
 * because the generated decoder expected the envelope. This is the GREEN half (Task 2): the overlay
 * re-points each `text/event-stream` schema at its payload type, so `chat` yields `ChatStreamChunk`,
 * `responses` yields `StreamEvents`, `messages` yields `MessagesStreamEvents`, and `images` yields
 * `ImageStreamEvent`.
 */
class StreamingWireTruthTest {
    private val credential = OpenRouterCredentials.static("sk-or-wire")
    private val sseHeaders = listOf(SdkHeader("Content-Type", "text/event-stream"))

    private fun client(vararg fragments: String): Pair<OpenRouter, FakeByteStream> {
        val body = FakeByteStream(fragments.map { it.encodeToByteArray() })
        val transport =
            FakeTransport(TransportCapabilities(supportsStreaming = true))
                .enqueueResponse(200, sseHeaders, body)
        return OpenRouter(credential = credential, transport = transport) to body
    }

    @Test
    fun chatStreamDecodesDocumentedWireChunks() = runTest {
        val (client, body) =
            client(
                SseWireFixtures.COMMENT,
                SseWireFixtures.chatChunk(content = "Hel"),
                SseWireFixtures.chatChunk(content = "lo", finishReason = "stop"),
                SseWireFixtures.DONE,
            )
        val chunks = client.chat.sendChatCompletionRequestStream(SseWireFixtures.userChatRequest()).toList()
        assertEquals(listOf("Hel", "lo"), chunks.map { it.choices.single().delta.content })
        assertEquals("stop", chunks.last().choices.single().finishReason?.value)
        body.assertClosedNormally()
    }

    @Test
    fun responsesStreamDecodesDocumentedWireEvents() = runTest {
        val (client, body) =
            client(
                SseWireFixtures.responsesEvent(
                    "response.output_text.delta",
                    "\"content_index\":0,\"delta\":\"Hel\",\"item_id\":\"item-1\"," +
                        "\"logprobs\":[],\"output_index\":0,\"sequence_number\":1",
                ),
                SseWireFixtures.DONE,
            )
        val events = client.betaResponses.createResponsesStream(responsesRequest { stream = true }).toList()
        val delta = assertIs<StreamEvents.TextDeltaEvent>(events.single())
        assertEquals("Hel", delta.delta)
        body.assertClosedNormally()
    }

    @Test
    fun messagesStreamDecodesDocumentedWireEvents() = runTest {
        val (client, body) =
            client(
                SseWireFixtures.messagesEvent(
                    "content_block_delta",
                    "{\"delta\":{\"text\":\"Hel\",\"type\":\"text_delta\"},\"index\":0,\"type\":\"content_block_delta\"}",
                ),
                SseWireFixtures.DONE,
            )
        val events =
            client.anthropicMessages
                .createMessagesStream(
                    messagesRequest {
                        model = "test/model"
                        messages = emptyList()
                        maxTokens = 16
                        stream = true
                    },
                ).toList()
        assertIs<MessagesStreamEvents.MessagesContentBlockDeltaEvent>(events.single())
        body.assertClosedNormally()
    }

    @Test
    fun imagesStreamDecodesDocumentedWireEvents() = runTest {
        val (client, body) =
            client(
                SseWireFixtures.event(
                    "{\"b64_json\":\"<b64>\",\"partial_image_index\":0,\"type\":\"image_generation.partial_image\"}",
                ),
                SseWireFixtures.DONE,
            )
        val events =
            client.images
                .createImagesStream(
                    imageGenerationRequest {
                        model = "test/model"
                        prompt = "a cat"
                    },
                ).toList()
        assertEquals(1, events.size)
        body.assertClosedNormally()
    }
}
