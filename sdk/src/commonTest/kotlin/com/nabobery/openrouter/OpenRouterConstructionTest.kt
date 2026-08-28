package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.PolicyOverride
import com.nabobery.sdkgen.runtime.SdkConfigurationException
import com.nabobery.sdkgen.testing.FakeTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class OpenRouterConstructionTest {
    private val credential = OpenRouterCredentials.static("sk-or-test")

    private fun root(
        retryPolicy: RetryPolicy = RetryPolicy.Default,
        deadlines: RequestDeadlines? = null,
        attribution: Attribution? = null,
    ): OpenRouter = OpenRouter(
        credential = credential,
        transport = FakeTransport(),
        retryPolicy = retryPolicy,
        deadlines = deadlines,
        attribution = attribution,
    )

    @Test
    fun exposesResourceClients() {
        val client = root()
        assertNotNull(client.chat)
        assertNotNull(client.models)
        assertNotNull(client.credits)
    }

    @Test
    fun missingCredentialThrows() {
        assertFailsWith<SdkConfigurationException> {
            OpenRouter {
                transport = FakeTransport()
            }
        }
    }

    @Test
    fun blankOrSchemelessBaseUrlThrows() {
        assertFailsWith<SdkConfigurationException> {
            OpenRouter {
                this.credential = credential
                transport = FakeTransport()
                baseUrl = "openrouter.ai/api/v1"
            }
        }
    }

    @Test
    fun bothHttpClientAndTransportThrows() {
        val httpClient = HttpClient(MockEngine { respond("") })
        try {
            assertFailsWith<SdkConfigurationException> {
                OpenRouter {
                    this.credential = credential
                    this.transport = FakeTransport()
                    this.httpClient = httpClient
                }
            }
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun optionsRetryIsReplaceForDefaultPolicy() {
        val options = root(retryPolicy = RetryPolicy.Default).options()
        assertIs<PolicyOverride.Replace<*>>(options.retry)
    }

    @Test
    fun optionsRetryIsDisabledForNonePolicy() {
        val options = root(retryPolicy = RetryPolicy.None).options()
        assertEquals(PolicyOverride.Disabled, options.retry)
    }

    @Test
    fun optionsMergesPerCallHeaderOnTopOfDefaults() {
        val options = root().options { header("X-Correlation-ID", "abc") }
        assertEquals("abc", options.headers.single { it.name == "X-Correlation-ID" }.value)
    }

    @Test
    fun optionsCarriesClientDeadlines() {
        val options = root(deadlines = RequestDeadlines(total = 30.seconds)).options()
        val deadlines = assertNotNull(options.deadlines)
        assertEquals(30_000L, deadlines.totalMillis)
        assertNull(deadlines.attemptMillis)
        assertNull(deadlines.idleMillis)
    }

    @Test
    fun reservedHeaderInBuilderThrows() {
        assertFailsWith<SdkConfigurationException> {
            OpenRouter {
                this.credential = credential
                transport = FakeTransport()
                header("Authorization", "Bearer x")
            }
        }
    }

    @Test
    fun reservedHeaderInPerCallOptionsThrows() {
        val client = root()
        assertFailsWith<SdkConfigurationException> {
            client.options { header("Content-Type", "text/plain") }
        }
        assertFailsWith<SdkConfigurationException> {
            client.options { header("authorization", "Bearer leaked") }
        }
    }

    @Test
    fun trustOriginAcceptsAbsoluteOrigin() {
        val cred = credential // bind outside the DSL: `credential` inside would resolve to the builder's own var
        val client = OpenRouter {
            this.credential = cred
            transport = FakeTransport()
            trustOrigin("https://proxy.example.com")
        }
        assertNotNull(client.chat)
    }

    @Test
    fun trustOriginRejectsBareHost() {
        val cred = credential
        assertFailsWith<SdkConfigurationException> {
            OpenRouter {
                this.credential = cred
                transport = FakeTransport()
                trustOrigin("proxy.example")
            }
        }
    }
}
