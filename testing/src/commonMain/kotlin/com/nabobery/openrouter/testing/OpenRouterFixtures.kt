package com.nabobery.openrouter.testing

import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.serialization.json.JsonPrimitive

/**
 * OpenRouter wire fixtures in the documented top-level shapes (no Speakeasy `{ "data": … }` envelope), plus the
 * fluent [FakeTransport] enqueue helpers built on them. Every JSON shape mirrors the pinned contract exactly (it is
 * decoded by the generated serializers in tests), so a drift re-pin that changes a required field fails a fixture
 * round-trip test before it reaches a consumer.
 */
public object OpenRouterFixtures {
    /** A buffered chat-completion body (`ChatResult`) with one assistant choice, usage, and a null system fingerprint. */
    public fun chatCompletionJson(
        content: String,
        id: String = "gen-test",
        model: String = "openrouter/test",
        finishReason: String = "stop",
        promptTokens: Int = 1,
        completionTokens: Int = 1,
    ): String =
        "{\"id\":${jsonQuoted(id)},\"object\":\"chat.completion\",\"created\":1,\"model\":${jsonQuoted(model)}," +
            "\"choices\":[{\"index\":0,\"finish_reason\":${jsonQuoted(finishReason)}," +
            "\"message\":{\"role\":\"assistant\",\"content\":${jsonQuoted(content)}}}]," +
            "\"system_fingerprint\":null," +
            "\"usage\":{\"prompt_tokens\":$promptTokens,\"completion_tokens\":$completionTokens," +
            "\"total_tokens\":${promptTokens + completionTokens}}}"

    /** A single streaming chat chunk (`chat.completion.chunk`) carrying one content [delta]. */
    public fun chatChunkJson(
        delta: String,
        id: String = "gen-test",
        model: String = "openrouter/test",
        finish: Boolean = false,
    ): String =
        "{\"id\":${jsonQuoted(id)},\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":${jsonQuoted(model)}," +
            "\"choices\":[{\"index\":0,\"delta\":{\"content\":${jsonQuoted(delta)}}," +
            "\"finish_reason\":${if (finish) "\"stop\"" else "null"}}]}"

    /** An OpenRouter error envelope (`{ "error": { "code", "message" } }`) for the given [code] and [message]. */
    public fun errorJson(code: Int, message: String): String =
        "{\"error\":{\"code\":$code,\"message\":${jsonQuoted(message)}}}"

    /** An empty models page (`ModelsListResponse`: `data`, `links.next`, `total_count`), enough for a decode assertion. */
    public fun modelsPageJson(): String = "{\"data\":[],\"links\":{\"next\":null},\"total_count\":0}"

    /**
     * JSON-encodes [s] as a quoted string literal with full RFC 8259 escaping (control characters, quotes, and
     * backslashes) via kotlinx.serialization — not a hand-rolled partial encoder — so content with newlines, tabs,
     * quotes, or other control characters still produces valid JSON. Available on the compile classpath through
     * `api(project(":sdk"))`, which re-exports `kotlinx-serialization-json`.
     */
    private fun jsonQuoted(s: String): String = JsonPrimitive(s).toString()
}

private val jsonHeaders: List<SdkHeader> = listOf(SdkHeader("Content-Type", "application/json"))
private val sseHeaders: List<SdkHeader> = listOf(SdkHeader("Content-Type", "text/event-stream"))

/**
 * Enqueues a raw JSON [body] with the given [status]. When no [headers] are supplied a `Content-Type:
 * application/json` header is used so the response decodes. Returns the transport for fluent chaining.
 */
public fun FakeTransport.enqueueJson(status: Int, body: String, headers: List<SdkHeader> = emptyList()): FakeTransport =
    enqueueResponse(
        status,
        headers.ifEmpty { jsonHeaders },
        FakeByteStream(listOf(body.encodeToByteArray())),
    )

/**
 * Enqueues a buffered chat completion whose single assistant message carries [content]. The next `chat.send` on a
 * client over this transport returns a `ChatResult` decoding to that content. Returns the transport for chaining.
 */
public fun FakeTransport.enqueueChatCompletion(
    content: String,
    id: String = "gen-test",
    model: String = "openrouter/test",
): FakeTransport = enqueueJson(200, OpenRouterFixtures.chatCompletionJson(content, id = id, model = model))

/**
 * Enqueues an SSE chat stream emitting one content chunk per [deltas] value, terminated by the `[DONE]` sentinel.
 * The next `chat.stream(...).contentDeltas()` on a client over this transport yields [deltas] in order. Returns the
 * transport for chaining.
 */
public fun FakeTransport.enqueueChatStream(
    vararg deltas: String,
    id: String = "gen-test",
    model: String = "openrouter/test",
): FakeTransport {
    val body =
        buildString {
            deltas.forEachIndexed { index, delta ->
                append("data: ")
                append(
                    OpenRouterFixtures.chatChunkJson(
                        delta,
                        id = id,
                        model = model,
                        finish =
                        index == deltas.lastIndex,
                    ),
                )
                append("\n\n")
            }
            append("data: [DONE]\n\n")
        }
    return enqueueResponse(200, sseHeaders, FakeByteStream(listOf(body.encodeToByteArray())))
}

/**
 * Enqueues an OpenRouter error response with HTTP [status] and body error [message]/[code] (defaulting [code] to
 * [status]). The next call over this transport throws the operation's typed `…ApiException`. Returns the transport
 * for chaining.
 */
public fun FakeTransport.enqueueError(status: Int, message: String, code: Int = status): FakeTransport =
    enqueueJson(status, OpenRouterFixtures.errorJson(code, message))
