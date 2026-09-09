package com.nabobery.openrouter

import com.nabobery.openrouter.anthropicmessages.AnthropicMessagesClient
import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.openrouter.responses.ResponsesClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads OpenRouter's stable [ApiErrorType] off a caught inference exception, uniformly across the
 * chat, Anthropic-messages, and responses skins.
 *
 * OpenRouter carries a canonical `error_type` on its error envelopes so callers can branch on a
 * single vocabulary regardless of which API shape they called. Each skin's buffered call throws a
 * per-operation `*ApiException` carrying a typed error body, but the three bodies expose
 * `error_type` at different places on the wire:
 *
 * - **chat** ([ChatClient.SendChatCompletionRequestApiException]) — under `error.metadata.error_type`,
 *   an untyped metadata map on the shared `*Response` error models.
 * - **responses** ([ResponsesClient.CreateResponsesApiException]) — the responses skin reuses those
 *   same shared `*Response` models, so `error.metadata.error_type` as well.
 * - **messages** ([AnthropicMessagesClient.CreateMessagesApiException]) — a typed `error.error_type`
 *   field (`AnthropicMessagesErrorResponse.error.errorType`).
 *
 * This extension hides those differences: call it on whatever you caught and get back the same
 * open-enum [ApiErrorType], or `null` when the value is absent or unreadable, or the throwable is
 * not one of the three inference exceptions. It never throws and requires no reflection.
 *
 * Unknown wire values are preserved as [ApiErrorType.SdkUnknown] rather than discarded, so forward
 * compatibility is intact:
 *
 * ```kotlin
 * try {
 *     client.chat.sendChatCompletionRequest(request)
 * } catch (e: Throwable) {
 *     when (e.openRouterErrorType()?.value) {
 *         "rate_limit_exceeded" -> backOffAndRetry()
 *         "provider_overloaded" -> failOverToAnotherProvider()
 *         else -> throw e
 *     }
 * }
 * ```
 */
public fun Throwable.openRouterErrorType(): ApiErrorType? = when (this) {
    is ChatClient.SendChatCompletionRequestApiException -> chatMetadata(error).errorType()
    is ResponsesClient.CreateResponsesApiException -> responsesMetadata(error).errorType()
    is AnthropicMessagesClient.CreateMessagesApiException -> messagesErrorType(error)
    else -> null
}

/** Reads the `error_type` string out of a shared `*Response` error's untyped `metadata` map. */
private fun Map<String, JsonElement>?.errorType(): ApiErrorType? {
    val value = (this?.get("error_type") as? JsonPrimitive)?.contentOrNull ?: return null
    return ApiErrorType.fromValue(value)
}

/** Chat error variants all wrap a shared `*Response` model whose `error.metadata` may carry `error_type`. */
private fun chatMetadata(error: ChatClient.SendChatCompletionRequestError): Map<String, JsonElement>? = when (error) {
    is ChatClient.SendChatCompletionRequestResponse.Http400Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http401Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http402Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http403Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http404Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http408Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http413Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http422Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http429Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http500Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http502Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http503Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http524Json -> error.json.error.metadata
    is ChatClient.SendChatCompletionRequestResponse.Http529Json -> error.json.error.metadata
}

/** Responses reuse the same shared `*Response` models as chat; `error.metadata` may carry `error_type`. */
private fun responsesMetadata(error: ResponsesClient.CreateResponsesError): Map<String, JsonElement>? = when (error) {
    is ResponsesClient.CreateResponsesResponse.Http400Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http401Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http402Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http403Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http404Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http408Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http413Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http422Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http429Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http500Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http502Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http503Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http524Json -> error.json.error.metadata
    is ResponsesClient.CreateResponsesResponse.Http529Json -> error.json.error.metadata
}

/** Messages variants all wrap `AnthropicMessagesErrorResponse`, which carries a typed `error.errorType`. */
private fun messagesErrorType(error: AnthropicMessagesClient.CreateMessagesError): ApiErrorType? = when (error) {
    is AnthropicMessagesClient.CreateMessagesResponse.Http400Json -> error.json.error.errorType
    is AnthropicMessagesClient.CreateMessagesResponse.Http401Json -> error.json.error.errorType
    is AnthropicMessagesClient.CreateMessagesResponse.Http403Json -> error.json.error.errorType
    is AnthropicMessagesClient.CreateMessagesResponse.Http404Json -> error.json.error.errorType
    is AnthropicMessagesClient.CreateMessagesResponse.Http429Json -> error.json.error.errorType
    is AnthropicMessagesClient.CreateMessagesResponse.Http500Json -> error.json.error.errorType
    is AnthropicMessagesClient.CreateMessagesResponse.Http503Json -> error.json.error.errorType
    is AnthropicMessagesClient.CreateMessagesResponse.Http529Json -> error.json.error.errorType
}
