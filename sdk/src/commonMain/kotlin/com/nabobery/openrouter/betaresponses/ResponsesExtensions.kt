package com.nabobery.openrouter.betaresponses

import com.nabobery.openrouter.Inputs
import com.nabobery.openrouter.OpenResponsesResult
import com.nabobery.openrouter.ResponsesRequest
import com.nabobery.openrouter.StreamEvents
import com.nabobery.openrouter.internal.requireNotStreaming
import com.nabobery.openrouter.internal.withStreamFlag
import com.nabobery.openrouter.responsesRequest
import com.nabobery.sdkgen.runtime.CallOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.JsonPrimitive

// Curated overloads on the generated `BetaResponsesClient`. The Responses stream already exposes a typed event
// hierarchy (`StreamEvents`) with typed error events, so the curated stream returns it directly — a wrapper would
// fork the model. Curated additions are limited to ergonomic overloads and one text-delta helper.

/** Routine Responses call from a model and a text input, with optional extra request fields via [configure]. */
public suspend fun BetaResponsesClient.create(
    model: String,
    input: String,
    options: CallOptions = CallOptions(),
    configure: (ResponsesRequest.Builder.() -> Unit)? = null,
): OpenResponsesResult = createResponses(buildRequest(model, input, configure).requireNotStreaming(), options = options)

/** Cold Responses stream; each collection sends one request with `"stream": true` forced on. */
public fun BetaResponsesClient.stream(
    request: ResponsesRequest,
    options: CallOptions = CallOptions(),
): Flow<StreamEvents> = createResponsesStream(request.withStreamFlag(), options = options)

/** Cold Responses stream from a model and a text input, with optional extra request fields via [configure]. */
public fun BetaResponsesClient.stream(
    model: String,
    input: String,
    options: CallOptions = CallOptions(),
    configure: (ResponsesRequest.Builder.() -> Unit)? = null,
): Flow<StreamEvents> = stream(buildRequest(model, input, configure), options)

/** `response.output_text.delta` payloads only, in arrival order. */
public fun Flow<StreamEvents>.outputTextDeltas(): Flow<String> =
    transform { event -> if (event is StreamEvents.TextDeltaEvent) emit(event.delta) }

// Flag-neutral: `create(...)` rejects `stream = true` and `stream(...)` forces it on (as in ChatExtensions).
private fun buildRequest(
    model: String,
    input: String,
    configure: (ResponsesRequest.Builder.() -> Unit)?,
): ResponsesRequest = responsesRequest {
    this.model = model
    this.input = Inputs.fromRaw(JsonPrimitive(input))
    configure?.invoke(this)
}
