package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkCapabilityException
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves the curated wiring does not break streaming preflight; full SSE semantics are covered separately.
 * The positive case needs a streaming-capable transport; the negative control pins why the
 * curated Ktor factory hard-codes supportsStreaming = true.
 */
class StreamingCapabilitySmokeTest {
    private val credential = OpenRouterCredentials.static("sk-or-stream")

    private fun streamingRequest(): ChatRequest = chatRequest {
        messages =
            listOf(
                SdkJson.decodeFromJsonElement(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "hello")
                    },
                ),
            )
        stream = true
    }

    // Documented OpenRouter wire format: the chat chunk is the top-level `data:` payload (no
    // Speakeasy `{ "data": … }` envelope). See the sse-payload overlay and StreamingWireTruthTest.
    private fun chatStreamEvent(content: String): String =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"},\"finish_reason\":null,\"index\":0}]," +
            "\"created\":1,\"id\":\"chat-1\",\"model\":\"test\",\"object\":\"chat.completion.chunk\"}\n\n"

    @Test
    fun streamingTransportDeliversOneEvent() = runTest {
        val sse = (chatStreamEvent("hi") + "data: [DONE]\n\n").encodeToByteArray()
        val body = FakeByteStream(listOf(sse))
        val transport =
            FakeTransport(TransportCapabilities(supportsStreaming = true))
                .enqueueResponse(200, listOf(SdkHeader("Content-Type", "text/event-stream")), body)
        val client = OpenRouter(credential = credential, transport = transport)

        val events = client.chat.sendChatCompletionRequestStream(streamingRequest()).toList()

        assertEquals(1, events.size)
        assertEquals("hi", events.single().choices.single().delta.content)
        assertTrue(body.closed)
    }

    @Test
    fun nonStreamingTransportFailsPreflight() = runTest {
        val transport = FakeTransport().enqueueResponse(200, listOf(SdkHeader("Content-Type", "text/event-stream")))
        val client = OpenRouter(credential = credential, transport = transport)

        assertFailsWith<SdkCapabilityException> {
            client.chat.sendChatCompletionRequestStream(streamingRequest()).toList()
        }
    }
}
