package com.nabobery.openrouter

import com.nabobery.openrouter.analytics.AnalyticsClient
import com.nabobery.openrouter.anthropicmessages.AnthropicMessagesClient
import com.nabobery.openrouter.apikeys.ApiKeysClient
import com.nabobery.openrouter.benchmarks.BenchmarksClient
import com.nabobery.openrouter.betaanalytics.BetaAnalyticsClient
import com.nabobery.openrouter.betaresponses.BetaResponsesClient
import com.nabobery.openrouter.byok.ByokClient
import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.openrouter.classifications.ClassificationsClient
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
import com.nabobery.openrouter.stt.SttClient
import com.nabobery.openrouter.tts.TtsClient
import com.nabobery.openrouter.videogeneration.VideoGenerationClient
import com.nabobery.openrouter.workspaces.WorkspacesClient
import com.nabobery.sdkgen.runtime.CallOptions
import com.nabobery.sdkgen.runtime.CallOptionsBuilder
import com.nabobery.sdkgen.runtime.SdkConfigurationException
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkTransport
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.runtime.auth.CredentialProvider
import com.nabobery.sdkgen.runtime.auth.TrustedHosts
import com.nabobery.sdkgen.runtime.callOptions
import com.nabobery.sdkgen.runtime.observation.SdkLifecycleObserver
import com.nabobery.sdkgen.transport.ktor.KtorSdkTransport
import io.ktor.client.HttpClient

/** [DslMarker] for the OpenRouter builder DSL: scopes receivers so nested builders cannot cross-talk. */
@DslMarker
public annotation class OpenRouterDsl

/**
 * Curated OpenRouter client root: one reusable entry point carrying client-level defaults
 * (retry, deadlines, attribution, observers) over the complete generated surface.
 *
 * Client defaults reach a call through [options]: pass `options = client.options()` (or
 * `client.options { ... }` for per-call overrides) to any generated operation. Generated
 * operations invoked without it use the generated defaults — which include NO retries.
 * Attribution and custom default headers apply to every call regardless, via the transport.
 */
public class OpenRouter internal constructor(
    private val generated: OpenRouterClient,
    private val retryPolicy: RetryPolicy,
    private val deadlines: RequestDeadlines?,
    private val observers: List<SdkLifecycleObserver>,
) {
    public val analytics: AnalyticsClient get() = generated.analytics
    public val anthropicMessages: AnthropicMessagesClient get() = generated.anthropicMessages
    public val apiKeys: ApiKeysClient get() = generated.apiKeys
    public val benchmarks: BenchmarksClient get() = generated.benchmarks
    public val betaAnalytics: BetaAnalyticsClient get() = generated.betaAnalytics
    public val betaResponses: BetaResponsesClient get() = generated.betaResponses
    public val byok: ByokClient get() = generated.byok
    public val chat: ChatClient get() = generated.chat
    public val classifications: ClassificationsClient get() = generated.classifications
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
    public val stt: SttClient get() = generated.stt
    public val tts: TtsClient get() = generated.tts
    public val videoGeneration: VideoGenerationClient get() = generated.videoGeneration
    public val workspaces: WorkspacesClient get() = generated.workspaces

    /**
     * Client defaults materialised as a per-call [CallOptions]; [overrides] layer curated per-call values on top.
     *
     * The override receiver is the curated [OpenRouterCallOptions], not the raw runtime [CallOptionsBuilder], so
     * the reserved-header guarantee (Authorization, Content-Type, and other SDK-controlled protocol headers cannot
     * be overridden) is enforced on this primary per-call path exactly as it is at build time.
     */
    public fun options(overrides: (OpenRouterCallOptions.() -> Unit)? = null): CallOptions = callOptions {
        retry(retryPolicy.toOverride())
        deadlines?.let { deadlines(it.toSdkDeadlines()) }
        observers.forEach { observer(it) }
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
 * a per-call [retry] policy, and lifecycle [observer]s. Reserved protocol headers are refused here exactly as at
 * build time, keeping the reserved-header guarantee intact on the primary per-call path. Advanced runtime hooks
 * (middleware, request hooks, pagination bounds) are intentionally not surfaced by the curated facade.
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

    /** Client-level retry defaults reached per call via [OpenRouter.options]. */
    public var retryPolicy: RetryPolicy = RetryPolicy.Default

    /** Client-level layered deadlines reached per call via [OpenRouter.options]. */
    public var deadlines: RequestDeadlines? = null

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
        val generated =
            OpenRouterClient(
                transport = effectiveTransport,
                baseUri = baseUrl,
                // One generated resource client binds scheme id "bearer" instead of "apiKey"; register both.
                credentialProviders = mapOf("apiKey" to credential, "bearer" to credential),
                trustedHosts = TrustedHosts.of(baseUrl, extraTrustedOrigins),
            )
        return OpenRouter(generated, retryPolicy, deadlines, observers.toList())
    }

    private fun validateBaseUrl(url: String) {
        if (url.isBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw SdkConfigurationException("baseUrl must be an absolute http(s) URL, got '$url'.")
        }
    }
}

/** Builder-DSL construction. */
public fun OpenRouter(block: OpenRouterBuilder.() -> Unit): OpenRouter = OpenRouterBuilder().apply(block).build()

/** Routine construction over a consumer-owned Ktor [HttpClient] (never mutated or closed by the SDK). */
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

/** Advanced construction over a neutral [SdkTransport] (specialized runtimes and tests). */
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
