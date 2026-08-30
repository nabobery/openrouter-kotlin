@file:OptIn(OpenRouterExperimentalApi::class)

package com.nabobery.openrouter

import com.nabobery.openrouter.analytics.AnalyticsClient
import com.nabobery.openrouter.anthropicmessages.AnthropicMessagesClient
import com.nabobery.openrouter.apikeys.ApiKeysClient
import com.nabobery.openrouter.benchmarks.BenchmarksClient
import com.nabobery.openrouter.byok.ByokClient
import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.openrouter.classifications.ClassificationsClient
import com.nabobery.openrouter.containers.ContainersClient
import com.nabobery.openrouter.credits.CreditsClient
import com.nabobery.openrouter.datasets.DatasetsClient
import com.nabobery.openrouter.embeddings.EmbeddingsClient
import com.nabobery.openrouter.endpoints.EndpointsClient
import com.nabobery.openrouter.files.FilesClient
import com.nabobery.openrouter.generations.GenerationsClient
import com.nabobery.openrouter.guardrails.GuardrailsClient
import com.nabobery.openrouter.images.ImagesClient
import com.nabobery.openrouter.internal.HeaderDefaultingTransport
import com.nabobery.openrouter.models.ModelsClient
import com.nabobery.openrouter.oauth.OAuthClient
import com.nabobery.openrouter.observability.ObservabilityClient
import com.nabobery.openrouter.organization.OrganizationClient
import com.nabobery.openrouter.presets.PresetsClient
import com.nabobery.openrouter.providers.ProvidersClient
import com.nabobery.openrouter.rerank.RerankClient
import com.nabobery.openrouter.responses.ResponsesClient
import com.nabobery.openrouter.scim.ScimClient
import com.nabobery.openrouter.stt.SttClient
import com.nabobery.openrouter.tts.TtsClient
import com.nabobery.openrouter.videogeneration.VideoGenerationClient
import com.nabobery.openrouter.workspaces.WorkspacesClient
import com.nabobery.sdkgen.runtime.CallOptions
import com.nabobery.sdkgen.runtime.CallOptionsBuilder
import com.nabobery.sdkgen.runtime.SdkClientConfig
import com.nabobery.sdkgen.runtime.SdkConfigurationException
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkTransport
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.runtime.auth.CredentialProvider
import com.nabobery.sdkgen.runtime.auth.TrustedHosts
import com.nabobery.sdkgen.runtime.bodies.TransferObserver
import com.nabobery.sdkgen.runtime.callOptions
import com.nabobery.sdkgen.runtime.observation.SdkLifecycleObserver
import com.nabobery.sdkgen.runtime.resilience.RetryBudget
import com.nabobery.sdkgen.transport.ktor.KtorSdkTransport
import io.ktor.client.HttpClient

/** [DslMarker] for the OpenRouter builder DSL: scopes receivers so nested builders cannot cross-talk. */
@DslMarker
public annotation class OpenRouterDsl

/**
 * Curated OpenRouter client root: one reusable entry point carrying client-level defaults
 * (retry, deadlines, attribution, observers, `User-Agent` product token) over the complete
 * generated surface.
 *
 * Client defaults apply to **every** generated call — they are carried into each generated
 * executor through `SdkClientConfig` at build time, so an operation invoked with no `options`
 * still retries, honours the client deadlines, and notifies the client observers. `options { }`
 * is for per-call *overrides* (which layer on top of the client defaults) and per-call
 * pagination bounds; a plain `client.options()` carries only the client-level pagination default.
 * Attribution and custom default headers apply to every call via the transport.
 */
public class OpenRouter internal constructor(
    private val generated: OpenRouterClient,
    private val paginationLimits: PaginationLimits? = null,
) {
    public val analytics: AnalyticsClient get() = generated.analytics
    public val anthropicMessages: AnthropicMessagesClient get() = generated.anthropicMessages
    public val apiKeys: ApiKeysClient get() = generated.apiKeys
    public val benchmarks: BenchmarksClient get() = generated.benchmarks
    public val byok: ByokClient get() = generated.byok
    public val chat: ChatClient get() = generated.chat
    public val classifications: ClassificationsClient get() = generated.classifications
    public val containers: ContainersClient get() = generated.containers
    public val credits: CreditsClient get() = generated.credits
    public val datasets: DatasetsClient get() = generated.datasets
    public val embeddings: EmbeddingsClient get() = generated.embeddings
    public val endpoints: EndpointsClient get() = generated.endpoints
    public val files: FilesClient get() = generated.files
    public val generations: GenerationsClient get() = generated.generations
    public val guardrails: GuardrailsClient get() = generated.guardrails
    public val images: ImagesClient get() = generated.images
    public val models: ModelsClient get() = generated.models
    public val oAuth: OAuthClient get() = generated.oAuth
    public val observability: ObservabilityClient get() = generated.observability
    public val organization: OrganizationClient get() = generated.organization
    public val presets: PresetsClient get() = generated.presets
    public val providers: ProvidersClient get() = generated.providers
    public val rerank: RerankClient get() = generated.rerank
    public val responses: ResponsesClient get() = generated.responses
    public val scim: ScimClient get() = generated.scim
    public val stt: SttClient get() = generated.stt
    public val tts: TtsClient get() = generated.tts
    public val videoGeneration: VideoGenerationClient get() = generated.videoGeneration
    public val workspaces: WorkspacesClient get() = generated.workspaces

    /**
     * A per-call [CallOptions] carrying the client-level pagination default; [overrides] layer curated per-call
     * values on top.
     *
     * Client retry/deadlines/observers are **not** re-emitted here — they reach the call through the generated
     * executor's `SdkClientConfig`. A field left untouched by [overrides] stays at the runtime's `Inherit` default,
     * so the client value applies; a per-call `retry`/`deadlines` in [overrides] wins over the client default per
     * the runtime precedence contract. Pagination bounds are not part of `SdkClientConfig`, so the client-level
     * [PaginationLimits] default (if any) is still materialised here.
     *
     * The override receiver is the curated [OpenRouterCallOptions], not the raw runtime [CallOptionsBuilder], so
     * the reserved-header guarantee (Authorization, Content-Type, and other SDK-controlled protocol headers cannot
     * be overridden) is enforced on this primary per-call path exactly as it is at build time.
     */
    public fun options(overrides: (OpenRouterCallOptions.() -> Unit)? = null): CallOptions = callOptions {
        paginationLimits?.let { pagination(it.toBounds()) }
        overrides?.let { OpenRouterCallOptions(this).apply(it) }
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://openrouter.ai/api/v1"
    }
}

/**
 * Curated per-call overrides layered on top of the client defaults by [OpenRouter.options].
 *
 * Only the safe subset is exposed — generic [header]s (reserved protocol names rejected), per-call [deadlines],
 * a per-call [retry] policy, per-call [pagination] bounds, and lifecycle [observer]s. Reserved protocol headers are
 * refused here exactly as at build time, keeping the reserved-header guarantee intact on the primary per-call path.
 * Advanced runtime hooks (middleware, request hooks) are intentionally not surfaced by the curated facade.
 */
@OpenRouterDsl
public class OpenRouterCallOptions internal constructor(private val builder: CallOptionsBuilder) {
    /** Adds a generic per-call header. Reserved SDK-controlled protocol headers are rejected. */
    public fun header(name: String, value: String) {
        requireNotReserved(name)
        builder.header(name, value)
    }

    /** Overrides the client's deadlines for this one call. */
    public fun deadlines(deadlines: RequestDeadlines) {
        builder.deadlines(deadlines.toSdkDeadlines())
    }

    /**
     * Sets automatic-pagination bounds for this one call, replacing any client-level [PaginationLimits] default.
     * Applies to the generated `xxxPages()` / `xxxItems()` flows walked with `options = client.options { ... }`.
     */
    @OpenRouterExperimentalApi
    public fun pagination(limits: PaginationLimits) {
        builder.pagination(limits.toBounds())
    }

    /**
     * Observes byte-transfer progress for this one call — upload and download start/progress/completion/failure
     * events carrying byte counts (never the bytes themselves). Used for the multipart and binary media operations.
     */
    @OpenRouterExperimentalApi
    public fun transferObserver(observer: TransferObserver) {
        builder.transferObserver(observer)
    }

    /** Overrides the client's retry policy for this one call. */
    public fun retry(policy: RetryPolicy) {
        builder.retry(policy.toOverride())
    }

    /** Adds a lifecycle observer scoped to this one call. */
    public fun observer(observer: SdkLifecycleObserver) {
        builder.observer(observer)
    }
}

/**
 * Mutable builder for an [OpenRouter] root. Configure it inside the `OpenRouter { ... }` DSL; the SDK calls
 * [build] for you. A [credential] is required; everything else has a safe default. Provide *either* an
 * [httpClient] (Ktor) *or* a [transport] (neutral [SdkTransport]) — never both.
 */
@OpenRouterDsl
public class OpenRouterBuilder internal constructor() {
    /** Credential applied to every call, resolved per physical attempt. Required. */
    public var credential: CredentialProvider? = null

    /** Consumer-owned Ktor client the SDK sends over; never mutated or closed by the SDK. Mutually exclusive with [transport]. */
    public var httpClient: HttpClient? = null

    /** Neutral transport for specialised runtimes and tests. Mutually exclusive with [httpClient]. */
    public var transport: SdkTransport? = null

    /** Absolute `http(s)` base URL; its origin is always trusted for credential forwarding. */
    public var baseUrl: String = OpenRouter.DEFAULT_BASE_URL

    /** Client-level retry default applied to every call through the runtime client config; overridable per call. */
    public var retryPolicy: RetryPolicy = RetryPolicy.Default

    /** Client-level layered deadlines applied to every call through the runtime client config; overridable per call. */
    public var deadlines: RequestDeadlines? = null

    /**
     * Client-level automatic-pagination bounds applied to every call through [OpenRouter.options]; unbounded when
     * null. (Pagination bounds ride the per-call options, unlike retry/deadlines, which the runtime client config
     * inherits directly on every call.)
     */
    public var paginationLimits: PaginationLimits? = null

    /**
     * Client-wide retry capacity: one [RetryBudget] is shared by the root facade and every resource client built
     * from it (ADR 0022 D2), so a burst of retries on one resource depletes the quota for the others. `null` uses
     * the runtime default capacity; an explicit value must be `>= 1`.
     */
    public var retryBudget: Int? = null

    /** Attribution headers (HTTP-Referer / X-OpenRouter-Title / categories) applied to every call. */
    public var attribution: Attribution? = null

    private val defaultHeaders = mutableListOf<SdkHeader>()
    private val observers = mutableListOf<SdkLifecycleObserver>()
    private val extraTrustedOrigins = mutableSetOf<String>()

    /** Sets [attribution] from its parts; a convenience over assigning an [Attribution] directly. */
    public fun attribution(referer: String? = null, title: String? = null, categories: List<String> = emptyList()) {
        attribution = Attribution(referer, title, categories)
    }

    /**
     * Adds a default header applied to every call when the call does not already carry it. Reserved
     * SDK-controlled protocol headers (Authorization, Content-Type, …) are rejected. If the same name is also
     * emitted by [attribution], this explicit header wins.
     */
    public fun header(name: String, value: String) {
        requireNotReserved(name)
        defaultHeaders += SdkHeader(name, value)
    }

    /** Registers a client-level lifecycle observer applied to every call. */
    public fun observer(observer: SdkLifecycleObserver) {
        observers += observer
    }

    /**
     * Trusts an additional origin for credential forwarding (the [baseUrl] origin is always trusted). The
     * argument must be an absolute `http(s)` origin such as `"https://proxy.example.com"` — a bare host is
     * rejected by the runtime's origin allowlist. Because this widens where the credential may be sent, prefer
     * a scheme- and (where relevant) port-qualified origin.
     */
    public fun trustOrigin(origin: String) {
        extraTrustedOrigins += origin
    }

    internal fun build(): OpenRouter {
        val credential =
            credential
                ?: throw SdkConfigurationException(
                    "OpenRouter requires a credential; use OpenRouterCredentials.static or .dynamic.",
                )
        validateBaseUrl(baseUrl)
        if (httpClient != null && transport != null) {
            throw SdkConfigurationException("Provide either httpClient or transport, not both.")
        }
        val baseTransport =
            transport
                ?: httpClient?.let {
                    KtorSdkTransport(
                        it,
                        TransportCapabilities(supportsStreaming = true, supportsHttp2 = true, canSetUserAgent = true),
                    )
                }
                ?: throw SdkConfigurationException(
                    "Provide an httpClient (Ktor) or a transport (neutral SdkTransport).",
                )
        // Merge attribution-derived headers with explicit header() defaults, deduped case-insensitively so a
        // single-valued header is never sent twice. Precedence: an explicit header() wins over an attribution
        // header of the same name (it overwrites the value at the attribution slot, preserving order).
        val mergedDefaults = LinkedHashMap<String, SdkHeader>()
        attribution?.toHeaders()?.forEach { mergedDefaults[it.name.lowercase()] = it }
        defaultHeaders.forEach { mergedDefaults[it.name.lowercase()] = it }
        val defaults = mergedDefaults.values.toList()
        val effectiveTransport =
            if (defaults.isEmpty()) baseTransport else HeaderDefaultingTransport(baseTransport, defaults)
        retryBudget?.let {
            if (it < 1) throw SdkConfigurationException("retryBudget must be at least 1 when set, got $it.")
        }
        // One SdkClientConfig carries the client defaults into every generated executor (ADR 0022): retry and
        // deadlines fold into each call's CallOptions when the call leaves them at Inherit; observers, the shared
        // retry budget, and the User-Agent product token are applied once, to the executor. Pagination bounds are
        // deliberately absent (they are not part of SdkClientConfig; OpenRouter.options() materialises them).
        val clientConfig =
            SdkClientConfig(
                retry = retryPolicy.toOverride(),
                deadlines = deadlines?.toSdkDeadlines(),
                observers = observers.toList(),
                retryBudget = retryBudget?.let { RetryBudget(capacity = it) } ?: RetryBudget(),
                productToken = "openrouter-kotlin/$SDK_VERSION",
            )
        val generated =
            OpenRouterClient(
                transport = effectiveTransport,
                baseUri = baseUrl,
                clientConfig = clientConfig,
                // One generated resource client binds scheme id "bearer" instead of "apiKey"; register both.
                credentialProviders = mapOf("apiKey" to credential, "bearer" to credential),
                trustedHosts = TrustedHosts.of(baseUrl, extraTrustedOrigins),
            )
        return OpenRouter(generated, paginationLimits)
    }

    private fun validateBaseUrl(url: String) {
        if (url.isBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw SdkConfigurationException("baseUrl must be an absolute http(s) URL, got '$url'.")
        }
    }
}

/** Builder-DSL construction. */
public fun OpenRouter(block: OpenRouterBuilder.() -> Unit): OpenRouter = OpenRouterBuilder().apply(block).build()

/**
 * Routine construction over a consumer-owned Ktor [HttpClient] (never mutated or closed by the SDK).
 *
 * Automatic-pagination bounds are intentionally *not* a parameter here: [PaginationLimits] is an experimental
 * (`@OpenRouterExperimentalApi`) type, and putting it in this stable constructor's signature would force every
 * ordinary caller to opt in. Set a client-level default through the builder DSL instead
 * (`OpenRouter { paginationLimits = PaginationLimits(...) }`), or bound a single walk per call with
 * `client.options { pagination(...) }`.
 */
public fun OpenRouter(
    credential: CredentialProvider,
    httpClient: HttpClient,
    baseUrl: String = OpenRouter.DEFAULT_BASE_URL,
    attribution: Attribution? = null,
    retryPolicy: RetryPolicy = RetryPolicy.Default,
    deadlines: RequestDeadlines? = null,
    observers: List<SdkLifecycleObserver> = emptyList(),
): OpenRouter = OpenRouter {
    this.credential = credential
    this.httpClient = httpClient
    this.baseUrl = baseUrl
    this.attribution = attribution
    this.retryPolicy = retryPolicy
    this.deadlines = deadlines
    observers.forEach(::observer)
}

/**
 * Advanced construction over a neutral [SdkTransport] (specialized runtimes and tests).
 *
 * As with the [HttpClient] overload, automatic-pagination bounds are not a parameter (they would drag the
 * experimental [PaginationLimits] into this stable signature); use the builder DSL or `client.options { pagination(...) }`.
 */
public fun OpenRouter(
    credential: CredentialProvider,
    transport: SdkTransport,
    baseUrl: String = OpenRouter.DEFAULT_BASE_URL,
    attribution: Attribution? = null,
    retryPolicy: RetryPolicy = RetryPolicy.Default,
    deadlines: RequestDeadlines? = null,
    observers: List<SdkLifecycleObserver> = emptyList(),
): OpenRouter = OpenRouter {
    this.credential = credential
    this.transport = transport
    this.baseUrl = baseUrl
    this.attribution = attribution
    this.retryPolicy = retryPolicy
    this.deadlines = deadlines
    observers.forEach(::observer)
}
