package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkConfigurationException
import io.ktor.client.HttpClient

/**
 * Explicit environment-based construction (JVM only; FR-CLI-003): reads OPENROUTER_API_KEY
 * and optional OPENROUTER_BASE_URL. Common/mobile/browser code never does implicit
 * environment lookup, so this lives in jvmMain.
 */
public fun OpenRouter.Companion.fromEnvironment(
    httpClient: HttpClient,
    attribution: Attribution? = null,
    retryPolicy: RetryPolicy = RetryPolicy.Default,
    deadlines: RequestDeadlines? = null,
): OpenRouter = fromEnvironment(httpClient, attribution, retryPolicy, deadlines, System::getenv)

internal fun OpenRouter.Companion.fromEnvironment(
    httpClient: HttpClient,
    attribution: Attribution?,
    retryPolicy: RetryPolicy,
    deadlines: RequestDeadlines?,
    env: (String) -> String?,
): OpenRouter {
    val key =
        env("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }
            ?: throw SdkConfigurationException("OPENROUTER_API_KEY is not set; fromEnvironment requires it.")
    return OpenRouter(
        credential = OpenRouterCredentials.static(key),
        httpClient = httpClient,
        baseUrl = env("OPENROUTER_BASE_URL")?.takeIf { it.isNotBlank() } ?: OpenRouter.DEFAULT_BASE_URL,
        attribution = attribution,
        retryPolicy = retryPolicy,
        deadlines = deadlines,
    )
}
