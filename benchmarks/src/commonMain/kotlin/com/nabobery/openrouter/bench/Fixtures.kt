package com.nabobery.openrouter.bench

import com.nabobery.openrouter.ChatRequest
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.SdkJson
import com.nabobery.openrouter.chatRequest
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * Deterministic benchmark fixtures: a `FakeTransport` (the `sdkgen-testing` "server") that replays a fixed SSE
 * chat stream or a buffered JSON body, plus the curated [OpenRouter] root over it. No network, no real engine —
 * the measured cost is the SDK's decode/dispatch path, not I/O.
 */
object Fixtures {
    private val credential = OpenRouterCredentials.static("sk-or-bench")
    private val sseHeaders = listOf(SdkHeader("Content-Type", "text/event-stream"))
    private val jsonHeaders = listOf(SdkHeader("Content-Type", "application/json"))

    /** One `data:`-framed OpenRouter chat chunk (documented wire shape; no Speakeasy envelope). */
    private fun chatChunk(index: Int): String =
        "data: {\"id\":\"gen-$index\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test/model\"," +
            "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"token$index \"},\"finish_reason\":null}]}\n\n"

    /** An SSE body of [count] chat chunks followed by the `[DONE]` sentinel. */
    fun sseBody(count: Int): ByteArray {
        val sb = StringBuilder()
        for (i in 0 until count) sb.append(chatChunk(i))
        sb.append("data: [DONE]\n\n")
        return sb.toString().encodeToByteArray()
    }

    /** A ~[approxBytes]-byte non-streaming chat completion JSON body. */
    fun bufferedBody(approxBytes: Int): ByteArray {
        val filler = "x".repeat(maxOf(1, approxBytes))
        return (
            "{\"id\":\"chat-bench\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test/model\"," +
                "\"system_fingerprint\":null,\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"$filler\"}}]}"
            ).encodeToByteArray()
    }

    fun streamingClient(body: ByteArray): OpenRouter {
        val transport =
            FakeTransport(TransportCapabilities(supportsStreaming = true))
                .enqueueResponse(200, sseHeaders, FakeByteStream(listOf(body)))
        return OpenRouter(credential = credential, transport = transport)
    }

    fun bufferedClient(body: ByteArray): OpenRouter {
        val transport = FakeTransport().enqueueResponse(200, jsonHeaders, FakeByteStream(listOf(body)))
        return OpenRouter(credential = credential, transport = transport)
    }

    fun chatRequestFixture(): ChatRequest = chatRequest {
        model = "test/model"
        messages =
            listOf(
                SdkJson.decodeFromJsonElement(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "hi")
                    },
                ),
            )
    }
}
