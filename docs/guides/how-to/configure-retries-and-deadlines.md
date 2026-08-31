# Configure retries and deadlines

## Retries

The default `RetryPolicy` retries **only HTTP 429** (`maxAttempts = 3`, `500 ms` initial delay, `60 s` cap,
`×2.0` backoff), plus connection failures that provably never reached the server. A rate-limited request is always
safe to replay; a `5xx` may already have applied a billable side effect, so it is **not** retried by default
(ADR 0004):

<!-- snippet: samples/docs/src/main/kotlin/guides/RetriesAndDeadlines.kt#retry-default -->
```kotlin
// The default policy retries only 429 (ADR 0004): a rate-limited request is always safe to replay, whereas a
// 5xx may have already applied a billable side effect. Connection failures that provably never reached the
// server are also replayed. Defaults: maxAttempts = 3, 500 ms initial, 60 s cap, x2.0 backoff.
val client = OpenRouter(
    credential = OpenRouterCredentials.static(apiKey),
    httpClient = http,
    retryPolicy = RetryPolicy(),
)
```
<!-- /snippet -->

Opt into additional statuses explicitly when your workload tolerates the replay risk:

<!-- snippet: samples/docs/src/main/kotlin/guides/RetriesAndDeadlines.kt#retry-opt-in -->
```kotlin
// Opt into extra idempotent-safe statuses explicitly when your workload tolerates the replay risk.
val withServerErrors = OpenRouter(
    credential = OpenRouterCredentials.static(apiKey),
    httpClient = http,
    retryPolicy = RetryPolicy(retryableStatusCodes = setOf(429, 503, 529)),
)
```
<!-- /snippet -->

Streaming requests are never retried, regardless of policy.

## Deadlines

Deadlines are **opt-in and layered** — nothing times out by default. `attempt` bounds a single physical attempt,
`total` bounds the whole logical call including retries, and `streamIdle` bounds the gap between streaming progress
signals. Each fires an `SdkTimeoutException` whose `phase` says which one tripped:

<!-- snippet: samples/docs/src/main/kotlin/guides/RetriesAndDeadlines.kt#deadlines -->
```kotlin
// Deadlines are opt-in and layered; nothing times out by default. `attempt` bounds one physical attempt,
// `total` bounds the whole logical call including retries.
val bounded = OpenRouter(
    credential = OpenRouterCredentials.static(apiKey),
    httpClient = http,
    deadlines = RequestDeadlines(attempt = 30.seconds, total = 2.minutes),
)
```
<!-- /snippet -->

See [handle errors](handle-errors.md) for inspecting `SdkTimeoutException.phase`.
