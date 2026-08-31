@file:OptIn(OpenRouterExperimentalApi::class)

package guides

// region imports
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.RequestDeadlines
import com.nabobery.openrouter.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
// endregion

/** How-to: retries and deadlines. Injected into configure-retries-and-deadlines.md. */
fun retriesAndDeadlines(apiKey: String, http: HttpClient) {
    // region retry-default
    // The default policy retries only 429 (ADR 0004): a rate-limited request is always safe to replay, whereas a
    // 5xx may have already applied a billable side effect. Connection failures that provably never reached the
    // server are also replayed. Defaults: maxAttempts = 3, 500 ms initial, 60 s cap, x2.0 backoff.
    val client = OpenRouter(
        credential = OpenRouterCredentials.static(apiKey),
        httpClient = http,
        retryPolicy = RetryPolicy(),
    )
    // endregion

    // region retry-opt-in
    // Opt into extra idempotent-safe statuses explicitly when your workload tolerates the replay risk.
    val withServerErrors = OpenRouter(
        credential = OpenRouterCredentials.static(apiKey),
        httpClient = http,
        retryPolicy = RetryPolicy(retryableStatusCodes = setOf(429, 503, 529)),
    )
    // endregion

    // region deadlines
    // Deadlines are opt-in and layered; nothing times out by default. `attempt` bounds one physical attempt,
    // `total` bounds the whole logical call including retries.
    val bounded = OpenRouter(
        credential = OpenRouterCredentials.static(apiKey),
        httpClient = http,
        deadlines = RequestDeadlines(attempt = 30.seconds, total = 2.minutes),
    )
    // endregion
    println(listOf(client, withServerErrors, bounded))
}
