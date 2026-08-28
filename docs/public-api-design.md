# Public API design

## Purpose

This document defines the intended public Kotlin shape. Examples are contract sketches until compile-validated by the
implementation. Generated operation and model names ultimately come from `kotlin-sdkgen` naming rules.

## Design rules

1. One final, reusable `OpenRouter` root.
2. Complete generated resources plus selected curated overloads on the same resource objects.
3. One immutable model graph; DSLs are construction syntax, not another domain model.
4. Suspend for one result, cold `Flow` for multiple incremental results.
5. Exceptions for terminal failure; values for valid in-band protocol errors.
6. Exact wire names through serialization metadata; Kotlin names follow Kotlin conventions.
7. Unknown server values remain representable.
8. Client defaults are immutable; request overrides never mutate the client.

## Coordinates and packages

Maven coordinates use the `io.github.nabobery` group (ADR 0006 amendment); Kotlin packages keep the
`com.nabobery.openrouter` namespace.

```text
io.github.nabobery:openrouter-kotlin
io.github.nabobery:openrouter-kotlin-testing

com.nabobery.openrouter
com.nabobery.openrouter.models
com.nabobery.openrouter.resources
com.nabobery.openrouter.beta
com.nabobery.openrouter.errors
com.nabobery.openrouter.transport
com.nabobery.openrouter.testing
```

Generated implementation details may live under internal packages. Public generated models should have meaningful
OpenRouter names and source KDoc.

## Client construction

```kotlin
public class OpenRouter(
    credential: CredentialProvider,
    httpClient: HttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    attribution: Attribution? = null,
    retryPolicy: RetryPolicy = RetryPolicy.default(),
    deadlines: RequestDeadlines = RequestDeadlines.default(),
    observers: List<SdkObserver> = emptyList(),
) : AutoCloseable {
    public val chat: ChatResource
    public val responses: ResponsesResource
    public val models: ModelsResource
    public val beta: BetaResource
}
```

The actual constructor may use a configuration object to protect binary compatibility, but routine construction must
remain concise. A builder DSL can delegate to the same configuration:

```kotlin
val client = OpenRouter {
    credential = CredentialProvider.static(apiKey)
    httpClient = ktorClient
    attribution {
        referer = "https://example.com"
        title = "Example"
        categories = listOf("mobile-app")
    }
}
```

### Environment configuration

```kotlin
val client = OpenRouter.fromEnvironment(httpClient)
```

This function exists only where environment variables are an expected, secure facility. It reads documented names
explicitly and fails when required values are absent. Common/mobile/browser code never performs implicit environment
lookup.

## Credentials

```kotlin
public fun interface CredentialProvider {
    public suspend fun resolve(): Secret

    public companion object {
        public fun static(value: String): CredentialProvider
        public fun dynamic(block: suspend () -> String): CredentialProvider
    }
}
```

Resolution occurs for every physical attempt. `Secret.toString()` is redacted. A request may override credentials:

```kotlin
client.models.list(
    options = RequestOptions {
        credential(otherCredential)
    },
)
```

## Attribution and headers

```kotlin
public data class Attribution(
    val referer: String? = null,
    val title: String? = null,
    val categories: List<String> = emptyList(),
)
```

Per-request semantics:

```kotlin
RequestOptions {
    attribution(replacement)
    clearAttribution()
    header("X-Correlation-ID", correlationId)
    removeHeader("X-Legacy")
}
```

Authorization, host, content length/type, and SDK-controlled protocol headers are reserved. Attempts to override them
through generic header APIs fail locally with a typed configuration error. This holds on **both** header paths — the
builder's `header(...)` default and the per-call `client.options { header(...) }` override — so the guarantee cannot be
bypassed by moving a reserved header from build time to call time.

## Resource organization

Resource names follow the pinned OpenRouter operation taxonomy and official SDK discoverability:

```kotlin
client.chat.send(request)
client.responses.create(request)
client.models.list(request)
client.providers.list(request)
client.credits.get(request)
client.generations.get(request)
client.keys.create(request)
client.beta.responses.create(request)
```

No `.raw` client exists. Exact and curated entry points appear together:

```kotlin
// Generated exact request
client.chat.send(ChatRequest(...))

// Curated overload
client.chat.send(
    model = "openai/gpt-5.2",
    messages = listOf(UserMessage("Hello")),
)
```

## Requests and DSLs

Public request models are immutable. Builders accumulate mutable state privately and return the same immutable models:

```kotlin
val request = chatRequest {
    model = "openai/gpt-5.2"
    messages {
        system("Answer concisely.")
        user("Explain Kotlin structured concurrency.")
    }
    provider {
        sort = ProviderSort.Latency
        allowFallbacks = true
        requireParameters = true
    }
    reasoning {
        effort = ReasoningEffort.Medium
    }
}
```

DSL requirements:

- `@DslMarker` prevents accidental receiver mixing.
- Validation is shared with direct construction.
- Collection inputs are defensively copied.
- Builders do not escape or remain attached to the result.
- No DSL-only capability exists.

## Optionality

The generated layer preserves contract presence:

```kotlin
sealed interface FieldState<out T> {
    data object Absent : FieldState<Nothing>
    data object Null : FieldState<Nothing>
    data class Value<T>(val value: T) : FieldState<T>
}
```

Curated overloads hide `FieldState` where omission and null do not matter for the task. Update/PATCH APIs must retain the
distinction. No use of `null` should ambiguously mean both “clear” and “inherit.”

## Open enums and unions

```kotlin
sealed interface ProviderSort {
    data object Price : ProviderSort
    data object Throughput : ProviderSort
    data object Latency : ProviderSort
    data class SdkUnknown(val value: String) : ProviderSort
}
```

Unknown union discriminators preserve raw JSON. Closed exhaustive `when` is offered only when the upstream contract is
actually closed.

## Normal and response-aware calls

```kotlin
suspend fun send(
    request: ChatRequest,
    options: RequestOptions = RequestOptions.Default,
): ChatResponse

suspend fun sendWithResponse(
    request: ChatRequest,
    options: RequestOptions = RequestOptions.Default,
): SdkResponse<ChatResponse>
```

```kotlin
data class SdkResponse<out T>(
    val value: T,
    val statusCode: Int,
    val headers: Headers,
    val requestId: String?,
)
```

Header values are immutable and case-insensitive. Sensitive response headers, if introduced, are redacted in
`toString()`.

## Streaming

```kotlin
fun stream(
    request: ChatRequest,
    options: RequestOptions = RequestOptions.Default,
): Flow<ChatStreamEvent>
```

```kotlin
sealed interface ChatStreamEvent {
    data class Chunk(val value: ChatCompletionChunk) : ChatStreamEvent
    data class Error(val value: OpenRouterStreamError) : ChatStreamEvent
    data object Done : ChatStreamEvent
}
```

The final event hierarchy must follow the pinned schema. Usage remains inside its wire chunk unless OpenRouter defines a
separate event. Collecting starts the request; two collections start two calls. `take`, cancellation, and downstream
failure close upstream resources.

## Pagination

```kotlin
data class Page<out T>(
    val items: List<T>,
    val next: NextPage?,
) {
    suspend fun next(): Page<T>?
}

fun listPages(...): Flow<Page<Model>>
fun listItems(...): Flow<Model>
```

Request options include maximum pages/items. Following absolute next links never forwards credentials outside trusted
hosts.

## Request options

```kotlin
class RequestOptions private constructor(
    val retryPolicy: RetryPolicy?,
    val deadlines: RequestDeadlines?,
    val credential: CredentialProvider?,
    val headers: Headers,
    internal val attributionOverride: Override<Attribution>,
    val observers: List<SdkObserver>,
) {
    companion object {
        val Default: RequestOptions
        operator fun invoke(block: Builder.() -> Unit): RequestOptions
    }
}
```

Lists and maps are snapshots. Request options can be reused concurrently.

## Retry policy

```kotlin
data class RetryPolicy(
    val maxAttempts: Int,
    val initialDelay: Duration,
    val maxDelay: Duration,
    val multiplier: Double,
    val retryConnectionFailures: Boolean,
    val retryableStatusCodes: Set<Int>,
    val replayMode: ReplayMode,
)
```

`ReplayMode.SafeOnly` is the recommended default. Explicit unsafe replay requires prominent naming and documentation.
Flow-level `retry` is not used internally after events have been emitted.

## Deadlines

```kotlin
data class RequestDeadlines(
    val total: Duration? = null,
    val attempt: Duration? = null,
    val streamIdle: Duration? = null,
)
```

Engine connection/socket timeouts are configured on Ktor by the consumer.

## Failures

```kotlin
sealed class SdkException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

Typed subclasses expose stable fields. Exception message wording is not a compatibility contract.
`CancellationException` is never wrapped.

## Transport injection

Most consumers inject Ktor:

```kotlin
OpenRouter(credential, httpClient)
```

Advanced consumers inject a neutral adapter:

```kotlin
OpenRouter(credential, transport = customTransport)
```

The two constructors/factories converge on the same executor. The public endpoint API is independent of either transport.

## Observability

```kotlin
fun interface SdkObserver {
    fun onEvent(event: SdkObservation)
}
```

Events are redacted before the callback. Observers cannot mutate requests or outcomes. Logical and attempt sequence
numbers make retries clear.

## Java interoperability

- Use `@JvmOverloads` only where it materially improves Java calls and does not explode signatures.
- Provide Java-friendly static factories through companions with `@JvmStatic`.
- Avoid Kotlin function types in essential Java-facing entry points when a `fun interface` works.
- Do not create duplicate blocking and future clients. Java consumers may use coroutine interop or a future adapter
  outside the core artifact.

## API review checklist

- Can a routine call be read without knowing the generator?
- Is every advanced wire field still reachable?
- Are input and output types immutable?
- Is absence distinct from null where required?
- Can unknown server values be preserved?
- Are cancellation and ownership obvious?
- Does the change add rather than modify an existing stable contract?
- Is the symbol documented and covered by source/binary validation?
- Does Java exposure remain reasonable?
- Does the signature compile for every published target?


## Implementation notes

The sketches above hold "until compile-validated." Validation against the published kotlin-sdkgen 0.3.0 runtime
produced these deliberate deviations:

1. **Per-call options materialize the runtime's `CallOptions` through a *curated* override receiver.**
   `client.options { ... }` still produces the runtime's `CallOptions` (built via `callOptions {}`), but the override
   block's receiver is the curated `OpenRouterCallOptions`, not the raw runtime `CallOptionsBuilder`. This was a
   safety correction: exposing the raw builder would let a caller set a reserved header (`Authorization`,
   `Content-Type`, …) per call, silently bypassing the reserved-header guarantee that the builder's
   `header(...)` enforces. `OpenRouterCallOptions` exposes only the safe subset — validated `header`, `deadlines`,
   `retry`, `observer` — keeping the guarantee intact on the primary per-call path. Advanced runtime hooks
   (middleware, request hooks, pagination bounds) are intentionally not surfaced by the curated facade.
2. **Credential factories are `OpenRouterCredentials.static` / `.dynamic`.** Kotlin cannot add companion members to
   the runtime's `CredentialProvider` fun-interface, so the curated factories live on a dedicated `object`. Both
   return `CredentialProvider`; `dynamic` resolves before every physical attempt (rotating keys).
3. **`AutoCloseable` on the root is deferred.** The SDK owns no closeable resource today (the consumer owns and closes
   the Ktor `HttpClient`, per ADR 0003). Adding an interface later is binary-compatible; shipping a no-op `close()`
   now would invite misuse against the ownership rules, so it is omitted.
4. **`RetryPolicy.replayMode` is deferred.** The runtime's idempotency gating already implements Safe-only semantics:
   an ambiguous mid-flight connection failure (the request may have reached the server) is never replayed for a
   non-idempotent POST without an idempotency key, and a stream is never restarted after emitting an event (ADR 0004).
   The curated default status allowlist contains only `429`. Service/provider failures such as `503` and `529` are
   explicit caller opt-ins because OpenRouter may already have attempted a provider and BYOK billing can occur outside
   OpenRouter's zero-completion insurance. A separate replay-mode knob remains unnecessary because the
   runtime already protects ambiguous transport failures and partially emitted streams; lifecycle-contract tests pin
   those guarantees (see the outcome record).

**Hybrid client-defaults caveat.** Generated operations invoked directly take the *generated* defaults — which include
**no retries** (`maxAttempts = 1`). Client-level retry/deadlines/observers apply only when a call passes
`options = client.options()`; attribution and custom default headers apply to every call via the header-defaulting
transport regardless. This gap is intentional and pinned by the `withoutOptionsThereIsNoRetry` lifecycle-contract
test. If kotlin-sdkgen gains client-default injection, the wrapper can thin out around that capability.
