package com.nabobery.openrouter.anthropicmessages

import com.nabobery.openrouter.InlineMessagesContentBlockDeltaEventDeltaX956b8ed8.InlineMessagesContentBlockDeltaEventDeltaOneOf1Xecf23299
import com.nabobery.openrouter.MessagesMessageParam
import com.nabobery.openrouter.MessagesRequest
import com.nabobery.openrouter.MessagesResult
import com.nabobery.openrouter.MessagesStreamEvents
import com.nabobery.openrouter.SdkJson
import com.nabobery.openrouter.internal.requireNotStreaming
import com.nabobery.openrouter.internal.withStreamFlag
import com.nabobery.openrouter.messagesRequest
import com.nabobery.sdkgen.runtime.CallOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

// Curated overloads on the generated `AnthropicMessagesClient`. The Messages stream already exposes a typed event
// hierarchy (`MessagesStreamEvents`) with typed error events, so the curated stream returns it directly. Message
// params are built by decoding canonical JSON through the union serializer (surviving inline-type renames).

/** A user-role Anthropic message param with plain-text content. */
public fun userMessageParam(text: String): MessagesMessageParam = messageParam("user", text)

/** An assistant-role Anthropic message param with plain-text content. */
public fun assistantMessageParam(text: String): MessagesMessageParam = messageParam("assistant", text)

/** Routine Messages call, with optional extra request fields via [configure]. */
public suspend fun AnthropicMessagesClient.create(
    model: String,
    maxTokens: Int,
    messages: List<MessagesMessageParam>,
    options: CallOptions = CallOptions(),
    configure: (MessagesRequest.Builder.() -> Unit)? = null,
): MessagesResult =
    createMessages(buildRequest(model, maxTokens, messages, configure).requireNotStreaming(), options = options)

/** Cold Messages stream; each collection sends one request with `"stream": true` forced on. */
public fun AnthropicMessagesClient.stream(
    request: MessagesRequest,
    options: CallOptions = CallOptions(),
): Flow<MessagesStreamEvents> = createMessagesStream(request.withStreamFlag(), options = options)

/** Cold Messages stream from a model, token budget, and messages, with optional extra fields via [configure]. */
public fun AnthropicMessagesClient.stream(
    model: String,
    maxTokens: Int,
    messages: List<MessagesMessageParam>,
    options: CallOptions = CallOptions(),
    configure: (MessagesRequest.Builder.() -> Unit)? = null,
): Flow<MessagesStreamEvents> = stream(buildRequest(model, maxTokens, messages, configure), options)

/** `text_delta` content-block deltas only, in arrival order. */
public fun Flow<MessagesStreamEvents>.textDeltas(): Flow<String> = transform { event ->
    if (event is MessagesStreamEvents.MessagesContentBlockDeltaEvent) {
        event.textDeltaOrNull()?.let { emit(it) }
    }
}

// Matches on the generated text-delta branch of the `delta` union (the input_json_delta branch has no text).
// The branch is referenced by its generated inline-type name: it is content-addressed, so a schema change to
// this delta shape renames the type and surfaces as a compile error under the drift gate — never a silent skip.
private fun MessagesStreamEvents.MessagesContentBlockDeltaEvent.textDeltaOrNull(): String? =
    (delta as? InlineMessagesContentBlockDeltaEventDeltaOneOf1Xecf23299)?.text

private fun messageParam(role: String, text: String): MessagesMessageParam = SdkJson.decodeFromJsonElement(
    buildJsonObject {
        put("role", role)
        put("content", text)
    },
)

// Flag-neutral: `create(...)` rejects `stream = true` and `stream(...)` forces it on (as in ChatExtensions).
private fun buildRequest(
    model: String,
    maxTokens: Int,
    messages: List<MessagesMessageParam>,
    configure: (MessagesRequest.Builder.() -> Unit)?,
): MessagesRequest = messagesRequest {
    this.model = model
    this.maxTokens = maxTokens
    this.messages = messages
    configure?.invoke(this)
}
