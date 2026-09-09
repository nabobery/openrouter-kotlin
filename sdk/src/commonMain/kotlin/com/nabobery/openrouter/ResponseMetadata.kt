package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkResponseResult

// OpenRouter stamps this header on every inference response; it keys the generation into analytics/cost lookups.
private const val GENERATION_ID_HEADER = "X-Generation-Id"

/**
 * The OpenRouter generation id (`X-Generation-Id`) carried by a response-aware call, or `null` if the response did
 * not include it. The header lookup is case-insensitive. Reach it from any `…WithResponse` result:
 *
 * ```kotlin
 * val result = client.chat.sendChatCompletionRequestWithResponse(request)
 * val generationId: String? = result.generationId()
 * ```
 */
public fun SdkResponseResult<*>.generationId(): String? =
    headers.firstOrNull { it.name.equals(GENERATION_ID_HEADER, ignoreCase = true) }?.value
