package com.nabobery.openrouter.chat

import com.nabobery.openrouter.ChatMessages
import com.nabobery.openrouter.ChatRequest
import com.nabobery.openrouter.ChatResult
import com.nabobery.openrouter.chatRequest
import com.nabobery.openrouter.internal.requireNotStreaming
import com.nabobery.openrouter.internal.withStreamFlag
import com.nabobery.sdkgen.runtime.CallOptions
import com.nabobery.sdkgen.runtime.SdkResponseResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Curated inference overloads on the generated `ChatClient`. These are additive extension functions in the
// generated client's own package, so `client.chat` stays the generated `ChatClient` (exact and curated entry
// points on one object) and nothing is re-delegated. The `options: CallOptions = CallOptions()` parameter
// mirrors the generated operations exactly, so the hybrid-defaults rule ("pass `options =
// client.options()` to get client defaults") is uniform across both layers.

/** Routine chat completion from a model and messages, with optional extra request fields via [configure]. */
public suspend fun ChatClient.send(
    model: String,
    messages: List<ChatMessages>,
    options: CallOptions = CallOptions(),
    configure: (ChatRequest.Builder.() -> Unit)? = null,
): ChatResult = send(buildRequest(model, messages, configure), options)

/** The exact request under the curated name. */
public suspend fun ChatClient.send(request: ChatRequest, options: CallOptions = CallOptions()): ChatResult =
    sendChatCompletionRequest(request.requireNotStreaming(), options = options)

/**
 * The exact request, returning the full [SdkResponseResult] (the response-alternative union, not [ChatResult]),
 * which exposes response headers such as `X-Generation-Id`.
 */
public suspend fun ChatClient.sendWithResponse(
    request: ChatRequest,
    options: CallOptions = CallOptions(),
): SdkResponseResult<ChatClient.SendChatCompletionRequestResponse> =
    sendChatCompletionRequestWithResponse(request.requireNotStreaming(), options = options)

/** Cold chat stream; each collection sends one request with `"stream": true` forced on. */
public fun ChatClient.stream(request: ChatRequest, options: CallOptions = CallOptions()): Flow<ChatStreamEvent> =
    sendChatCompletionRequestStream(request.withStreamFlag(), options = options).map {
        it.toEvent()
    }

/** Cold chat stream from a model and messages, with optional extra request fields via [configure]. */
public fun ChatClient.stream(
    model: String,
    messages: List<ChatMessages>,
    options: CallOptions = CallOptions(),
    configure: (ChatRequest.Builder.() -> Unit)? = null,
): Flow<ChatStreamEvent> = stream(buildRequest(model, messages, configure), options)

// The `stream` flag is not set here: the buffered `send(...)` seam rejects it via `requireNotStreaming`,
// and the streaming `stream(...)` seam forces it on via `withStreamFlag`, so this builder stays flag-neutral.
private fun buildRequest(
    model: String,
    messages: List<ChatMessages>,
    configure: (ChatRequest.Builder.() -> Unit)?,
): ChatRequest = chatRequest {
    this.model = model
    this.messages = messages
    configure?.invoke(this)
}
