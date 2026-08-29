package com.nabobery.openrouter.internal

import com.nabobery.openrouter.ChatRequest
import com.nabobery.openrouter.MessagesRequest
import com.nabobery.openrouter.ResponsesRequest
import com.nabobery.openrouter.SdkJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Returns [this] with `"stream": true` set. When [isSet] already reports the flag present, the receiver is
 * returned unchanged; otherwise the value is re-encoded through the generated serializer with the single
 * `stream` key added, so no other field is touched. Keeping the round-trip in the generated serializer makes
 * curated `stream(...)` payloads byte-identical to the exact call with `stream = true` set on the builder.
 */
internal inline fun <reified T> T.withStreamFlag(isSet: T.() -> Boolean): T {
    if (isSet()) return this
    val raw = SdkJson.encodeToJsonElement(this).jsonObject
    return SdkJson.decodeFromJsonElement(JsonObject(raw + ("stream" to JsonPrimitive(true))))
}

/** Returns this [ChatRequest] with `"stream": true` forced on (unchanged if it already sets it). */
internal fun ChatRequest.withStreamFlag(): ChatRequest = withStreamFlag { stream == true }

/** Returns this [ResponsesRequest] with `"stream": true` forced on (unchanged if it already sets it). */
internal fun ResponsesRequest.withStreamFlag(): ResponsesRequest = withStreamFlag { stream == true }

/** Returns this [MessagesRequest] with `"stream": true` forced on (unchanged if it already sets it). */
internal fun MessagesRequest.withStreamFlag(): MessagesRequest = withStreamFlag { stream == true }

// A curated `send`/`create` overload targets the buffered decoder, which cannot read an SSE
// (`text/event-stream`) body. Rejecting `stream = true` here fails fast, before a billable request is
// sent, instead of surfacing an opaque deserialization error after the fact; callers stream via `stream(...)`.
private fun requireNotStreaming(stream: Boolean?) {
    require(stream != true) {
        "This is a non-streaming call; open a streaming request with stream(...) instead of setting stream = true."
    }
}

/** Returns this [ChatRequest] unchanged, or throws if it requests streaming (see [requireNotStreaming]). */
internal fun ChatRequest.requireNotStreaming(): ChatRequest = apply { requireNotStreaming(stream) }

/** Returns this [ResponsesRequest] unchanged, or throws if it requests streaming (see [requireNotStreaming]). */
internal fun ResponsesRequest.requireNotStreaming(): ResponsesRequest = apply { requireNotStreaming(stream) }

/** Returns this [MessagesRequest] unchanged, or throws if it requests streaming (see [requireNotStreaming]). */
internal fun MessagesRequest.requireNotStreaming(): MessagesRequest = apply { requireNotStreaming(stream) }
