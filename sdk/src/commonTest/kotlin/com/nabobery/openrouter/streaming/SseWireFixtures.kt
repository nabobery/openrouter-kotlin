package com.nabobery.openrouter.streaming

import com.nabobery.openrouter.ChatRequest
import com.nabobery.openrouter.SdkJson
import com.nabobery.openrouter.chatRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * SSE fixtures in OpenRouter's documented wire format — the top-level event payload, with no
 * Speakeasy `{ "data": … }` envelope. These are the exact bytes OpenRouter puts on the wire
 * (docs: api-reference/streaming), so the same fixtures drive the wire-truth decode test, the
 * framing/lifecycle contract matrices, the real-engine lane, and the samples' README snippets.
 */
internal object SseWireFixtures {
    /** OpenRouter keep-alive comment line (ignored by the WHATWG SSE parser). */
    const val COMMENT: String = ": OPENROUTER PROCESSING\n\n"

    /** Terminal sentinel; consumed by the runtime and never emitted. */
    const val DONE: String = "data: [DONE]\n\n"

    const val USAGE_JSON: String =
        "{\"prompt_tokens\":5,\"completion_tokens\":7,\"total_tokens\":12,\"cost\":0.000012}"

    const val MID_STREAM_ERROR_JSON: String =
        "{\"code\":502,\"message\":\"Provider disconnected unexpectedly\"," +
            "\"metadata\":{\"error_type\":\"provider_unavailable\"}}"

    /** The top-level chat chunk JSON (no envelope), matching OpenRouter's documented shape. */
    fun chatChunkJson(
        id: String = "gen-1",
        content: String? = "hi",
        finishReason: String? = null,
        usage: String? = null,
        error: String? = null,
        choices: String? = null,
    ): String {
        val delta = if (content == null) "{}" else "{\"content\":${jsonString(content)}}"
        val finish = finishReason?.let { "\"$it\"" } ?: "null"
        val choicesJson = choices ?: "[{\"index\":0,\"delta\":$delta,\"finish_reason\":$finish}]"
        val extra =
            buildString {
                usage?.let { append(",\"usage\":$it") }
                error?.let { append(",\"error\":$it") }
            }
        return "{\"id\":\"$id\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test/model\"," +
            "\"choices\":$choicesJson$extra}"
    }

    /** A single `data:`-framed chat event terminated by a blank line. */
    fun chatChunk(
        id: String = "gen-1",
        content: String? = "hi",
        finishReason: String? = null,
        usage: String? = null,
        error: String? = null,
        choices: String? = null,
    ): String = event(chatChunkJson(id, content, finishReason, usage, error, choices))

    /** A single `data:`-framed event with the requested line ending, terminated by a blank line. */
    fun event(json: String, lineEnding: String = "\n"): String = "data: $json$lineEnding$lineEnding"

    /**
     * Splits [json] across two `data:` lines. The WHATWG parser joins consecutive `data:` field
     * values with '\n' before the event is decoded, so this must decode identically to [event].
     */
    fun multilineEvent(json: String): String {
        val split = json.length / 2 + 1
        val head = json.substring(0, split)
        val tail = json.substring(split)
        return "data: $head\ndata: $tail\n\n"
    }

    /** A Responses-skin event: the payload's `type` discriminator carries the SSE semantics. */
    fun responsesEvent(type: String, body: String = ""): String =
        "data: {\"type\":\"$type\"${if (body.isEmpty()) "" else ",$body"}}\n\n"

    /** A Messages-skin event with a named `event:` field and its JSON payload. */
    fun messagesEvent(event: String, json: String): String = "event: $event\ndata: $json\n\n"

    /**
     * The shared streaming request: a JSON-decoded user message plus [stream]. Building the message
     * by decoding canonical JSON through the union serializer (rather than an inline `of(...)`
     * factory) survives inline-type renames across regenerations.
     */
    fun userChatRequest(stream: Boolean? = true): ChatRequest = chatRequest {
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
        if (stream != null) this.stream = stream
    }

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
