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
   `retry`, `observer`, and (experimental) `pagination`/`transferObserver` — keeping the guarantee intact on the
   primary per-call path. Advanced runtime hooks (middleware, request hooks) are intentionally not surfaced by the
   curated facade.
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
   those guarantees.

**Client defaults.** Generated operations inherit client-level retry, deadlines, observers, shared retry budget, and
the User-Agent product token through `SdkClientConfig`. Callers use `options { }` only for per-call overrides and
pagination bounds; attribution and custom default headers apply to every call via the header-defaulting transport.

## Inference and streaming implementation

The inference and streaming surface is validated against kotlin-sdkgen 0.4.0. All curated inference ops carry
`options: CallOptions = CallOptions()`, mirroring the generated ops exactly. The client defaults reach
every call through `SdkClientConfig` (deltas 18–21 below), so `options { }` is per-call *overrides* only — the former
hybrid rule ("pass `options = client.options()` to get client defaults") no longer applies. Continuing the numbered
list above:

5. **Curated inference overloads are extension functions on the generated clients.** `client.chat` stays the generated
   `ChatClient`; the curated `send`/`stream`/message-helpers/`messages { }` DSL are `com.nabobery.openrouter.chat`
   extensions on it (responses under `...responses` — GA'd and renamed from `...betaresponses` at the 2026-08-29
   re-pin; reached via `client.responses` — messages under `...anthropicmessages`). Exact and curated
   entry points live on one object and the addition is binary-additive. No `*Resource` wrapper is introduced.
6. **`ChatStreamEvent` is a sealed interface with two variants — `Chunk` and `Error`, no `Done`.** (This supersedes the
   three-variant sketch under **Streaming** above.) Flow completion is the terminal signal; the `[DONE]` sentinel is
   consumed by the runtime and never emitted. `Error` carries an in-band error chunk (`chunk.error != null`) as a value,
   after which the flow completes normally. A `finish_reason: "error"` without an `error` object stays a `Chunk`.
   `Flow<ChatStreamEvent>.contentDeltas(): Flow<String>` projects assistant text deltas.
7. **Responses and Messages streams expose the generated unions directly** — `stream(...): Flow<StreamEvents>` and
   `Flow<MessagesStreamEvents>` — rather than curated wrappers, which would fork the model. Curated additions are
   limited to ergonomic overloads plus the text-delta helpers `outputTextDeltas()` (responses) and `textDeltas()`
   (messages).
8. **Message helpers build union branches by decoding canonical JSON through the union serializer.** This survives
   inline-type renames and always carries a validated `raw` JSON payload on each branch.
9. **Curated `stream()` forces `"stream": true` by JSON round-trip** (encode the request → add the key → decode through
   the generated serializer). Exact-vs-curated payload byte-identity for equivalent requests is pinned by golden tests.
10. **The SSE payload overlay is the third spec overlay.** kotlin-sdkgen 0.3.0 decoded each SSE `data:` string as a
    Speakeasy event envelope (`{ "data": <payload> }`), but OpenRouter sends the payload directly, so every generated
    `*Stream` op threw `SdkSerializationException` on its first real event. `spec/overlays/sse-payload.yaml` re-points
    each `text/event-stream` schema at its payload type: chat → `Flow<ChatStreamChunk>`, responses → `Flow<StreamEvents>`,
    messages → `Flow<MessagesStreamEvents>`, images → `Flow<ImageStreamEvent>` (a new named union component the overlay
    adds). Proven by `StreamingWireTruthTest` (RED before the overlay, GREEN after). **Removal condition:** kotlin-sdkgen
    unwraps SSE envelopes natively.
11. **`:sdk` exposes `sdkgen-runtime` and `kotlinx-serialization-json` as `api` dependencies** (previously
    `implementation`). The generated + curated *public* API references their types (`CredentialProvider`, `CallOptions`,
    `SdkTransport`, typed exceptions, `JsonElement` on model `raw`), so external consumers need them transitively.
    Discovered via the sample consumers.

### Current implementation deltas

12. **Pagination is bounds + idioms, not a parallel paginator.** `PaginationLimits(maxPages, maxItems, maxElapsed)`
    is surfaced as a client default (`OpenRouterBuilder.paginationLimits`, in the `OpenRouter { … }` DSL) and per-call
    (`options { pagination(...) }`); the idiomatic walk is the generated `xxxPages()`/`xxxItems()` flows with
    `take(n)`. Because `PaginationLimits` is `@OpenRouterExperimentalApi`, it is deliberately **not** a parameter on
    the routine `OpenRouter(credential, httpClient, …)` constructors — that would drag the experimental opt-in onto
    every ordinary caller (see delta 16); the builder DSL and per-call DSL are the opt-in-scoped entry points. There
    is **no** `Page.next()` (the generated per-page fetch is private; duplicating it per operation is the
    per-operation duplication the system design forbids). No default truncation bound is imposed.
13. **Byte-stream helpers** live in `com.nabobery.openrouter.io`: `byteStreamOf(bytes)`, `readAllBytes(maxBytes)`,
    `asFlow(chunkSize)` (cold; closes on completion/failure/cancellation with the corresponding cause).
14. **Files:** `listFiles` returns the `_shape`-discriminated union and loses generated pagination flows. Curated
    helpers are `FilesClient.upload`, `downloadBytes`, and now `listAllFiles` — a bounded curated cursor walk over
    the union (`@OpenRouterExperimentalApi`), unblocked by kotlin-sdkgen 0.4.0's fix for the explicit `cursor: null`
    terminal page. It yields a cold `Flow<FileListEntry>` (a small sealed interface wrapping the three provider file
    records so the shapes are not flattened into one lossy model), follows the provider-specific continuation
    (`cursor` for OpenRouter; `after`/`after_id` = `last_id` for OpenAI/Anthropic), honours **every**
    `PaginationLimits` field (a `maxElapsed` budget covers the whole walk via a monotonic deadline applied per fetch,
    never around `emit`, so already-emitted items are never replayed), and fails closed with `SdkPaginationException`
    if a server repeats a continuation token. Only the *generated* multi-page flow over a union envelope remains
    unsupported (exception register + upstream proposal). `uploadFile` returns `FileResponse`, and the multipart
    codec fixes part name/content-type (no curated `filename` param).
15. **STT:** curated `SttClient.transcribe(audio, model) { … }` over the multipart op.
16. **`client.beta` is not present** — the 2026-08-29 contract GA'd Responses and Analytics, so no beta resources are
    generated. `@OpenRouterExperimentalApi` (WARNING-level opt-in) guards the pre-1.0 byte-stream/pagination/media
    helpers instead. `client.betaResponses` → `client.responses`; `betaAnalytics` removed.
17. **Exception register** (`docs/coverage/exception-register.md`) records every omission/degradation; the coverage
    dashboard (`docs/coverage/operation-coverage.md`) is generated and CI-gated.

### Client defaults reach every call

18. **The hybrid gap is closed.** Client-level retry, deadlines, and observers are carried into every generated
    executor through the runtime's `SdkClientConfig` at build time (kotlin-sdkgen 0.4.0, ADR 0022). A call made with
    **no** `options` now retries, honours the client deadlines, and notifies the client observers — the former
    `withoutOptionsThereIsNoRetry` behaviour is gone; its inverse
    `ClientDefaultsContractTest.withoutOptionsClientRetryPolicyApplies` pins the new contract.
19. **`options()` is per-call only.** `OpenRouter.options { … }` no longer re-emits the client defaults; it carries
    the client-level pagination bounds (which are *not* part of `SdkClientConfig`) plus any per-call overrides. A
    field left untouched stays at the runtime `Inherit` default, so the client value applies; a per-call
    `retry`/`deadlines` override wins per the runtime precedence contract (ADR 0022 D2).
20. **`User-Agent` product token.** Every call carries `openrouter-kotlin/<SDK_VERSION>` as the product token
    (`SdkClientConfig.productToken`) when the transport can set that header. The reserved-header guard refuses a
    caller-set `User-Agent`, so the SDK remains the sole owner of the product token. `SDK_VERSION` is kept in lockstep
    with `project.version` by the `checkSdkVersionConstant` verification gate.
21. **One shared retry budget.** `OpenRouterBuilder.retryBudget` (nullable `Int`, validated `>= 1`) sets a single
    client-wide `RetryBudget` shared by the root facade and every resource client (ADR 0022 D2); `null` uses the
    runtime default capacity. `ClientDefaultsContractTest.resourceClientsShareOneRetryBudget` pins the sharing.

### Streaming retry

kotlin-sdkgen 0.3.0 disables retry **entirely** for the streaming response mode (`SdkExecutor.kt`:
`retry ... .takeUnless { responseMode == STREAMING }`). Streaming ops are therefore never retried — not even a
pre-first-byte 429, which surfaces immediately as the typed `ApiException`. This is stricter and safer than the buffered
path (which retries an allowlisted 429), because an opened stream cannot be transparently restarted. It supersedes any
earlier assumption that pre-first-byte stream retry is allowed.
