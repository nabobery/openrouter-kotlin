package com.nabobery.openrouter.testing

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterBuilder
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.testing.FakeTransport

/**
 * The static test API key wired into [fake]. It is a syntactically valid `sk-or-…` token with no real value — a
 * fixed placeholder so a fake client authenticates deterministically without a secret. Never a real credential.
 */
public const val TEST_API_KEY: String = "sk-or-test-0000000000000000"

/**
 * A [FakeTransport] whose reported capabilities match the three the curated Ktor factory sets — streaming, HTTP/2,
 * and a settable `User-Agent`. It reports `supportsStreaming = true`, so a streaming call does not fail preflight
 * with `SdkCapabilityException` the way the default [FakeTransport] (streaming off) would. Other capability fields
 * (redirects, deadlines) keep the runtime defaults; this matches only the subset the OpenRouter factory constrains.
 */
public fun openRouterFakeTransport(): FakeTransport =
    FakeTransport(TransportCapabilities(supportsStreaming = true, supportsHttp2 = true, canSetUserAgent = true))

/**
 * A curated [OpenRouter] root over a [FakeTransport]: no network, no secrets, deterministic. Enqueue responses on
 * [transport] with the fixture helpers ([enqueueChatCompletion], [enqueueChatStream], [enqueueError],
 * [enqueueJson]) before driving the client. Defaults: the static [TEST_API_KEY] credential and the builder's own
 * `RetryPolicy.Default`; [configure] layers arbitrary builder overrides (retry policy, attribution, headers) on top
 * — a `credential`/`transport` set there wins over these defaults.
 */
public fun OpenRouter.Companion.fake(
    transport: FakeTransport = openRouterFakeTransport(),
    configure: OpenRouterBuilder.() -> Unit = {},
): OpenRouter = OpenRouter {
    credential = OpenRouterCredentials.static(TEST_API_KEY)
    this.transport = transport
    configure()
}
