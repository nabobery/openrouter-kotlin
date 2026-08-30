package com.nabobery.openrouter

import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.sdkgen.runtime.SdkConfigurationException
import com.nabobery.sdkgen.runtime.SdkResponse
import com.nabobery.sdkgen.runtime.SdkTimeoutException
import com.nabobery.sdkgen.runtime.SdkTransportException
import com.nabobery.sdkgen.runtime.TimeoutPhase
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.runtime.observation.SdkLifecycleObserver
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the client-defaults guarantee: values configured once on the builder reach **every** generated call through
 * `SdkClientConfig`, with no `options` argument required. This is the inverse of the retired
 * `LifecycleContractTest.withoutOptionsThereIsNoRetry` behavior from when defaults lived only in `options()`.
 */
class ClientDefaultsContractTest {
    private val credential = LifecycleFixtures.credential
    private val jsonHeaders = LifecycleFixtures.jsonHeaders

    private fun root(
        transport: FakeTransport,
        retryPolicy: RetryPolicy = RetryPolicy.Default,
        deadlines: RequestDeadlines? = null,
    ): OpenRouter = OpenRouter(
        credential = credential,
        transport = transport,
        retryPolicy = retryPolicy,
        deadlines = deadlines,
    )

    // 1. The client retry policy applies to a call made with NO options() — the hybrid gap is closed.
    @Test
    fun withoutOptionsClientRetryPolicyApplies() = runTest {
        val success = LifecycleFixtures.chatSuccessBody()
        val transport =
            FakeTransport()
                .enqueueResponse(429, jsonHeaders, LifecycleFixtures.errorBody(429))
                .enqueueResponse(200, jsonHeaders, success)
        val client = root(transport)

        val result = client.chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())

        assertEquals("chat-fixture", result.id)
        assertEquals(2, transport.capturedRequests.size)
    }

    // 2. A per-call retry override (Replace/Disabled) still wins over the client default.
    @Test
    fun perCallReplaceStillWinsOverClientDefault() = runTest {
        val transport =
            FakeTransport()
                .enqueueResponse(429, jsonHeaders, LifecycleFixtures.errorBody(429))
                .enqueueResponse(200, jsonHeaders, LifecycleFixtures.chatSuccessBody())
        val client = root(transport, retryPolicy = RetryPolicy.Default)

        assertFailsWith<ChatClient.SendChatCompletionRequestApiException> {
            client.chat.sendChatCompletionRequest(
                LifecycleFixtures.chatRequest(),
                options = client.options { retry(RetryPolicy.None) },
            )
        }
        assertEquals(1, transport.capturedRequests.size)
    }

    // 3. The client deadline applies to a call made with NO options().
    @Test
    fun clientDeadlineAppliesWithoutOptions() = runTest {
        val transport =
            FakeTransport().enqueueExchange { _ ->
                delay(10.minutes)
                SdkResponse(200, jsonHeaders, LifecycleFixtures.chatSuccessBody())
            }
        val client = root(transport, deadlines = RequestDeadlines(attempt = 1.seconds))

        val timeout =
            assertFailsWith<SdkTimeoutException> {
                client.chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())
            }
        assertEquals(TimeoutPhase.ATTEMPT, timeout.phase)
    }

    // 4. A client-registered lifecycle observer fires for an ordinary call made with NO options().
    @Test
    fun observersFireWithoutOptions() = runTest {
        val startedOperations = mutableListOf<String>()
        val observer = object : SdkLifecycleObserver {
            override fun callStarted(callId: String, operationId: String, method: String, normalizedRoute: String) {
                startedOperations += operationId
            }
        }
        val transport =
            FakeTransport().enqueueResponse(200, jsonHeaders, LifecycleFixtures.chatSuccessBody())
        val client = OpenRouter {
            credential = LifecycleFixtures.credential
            this.transport = transport
            observer(observer)
        }

        client.chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())

        assertEquals(listOf("sendChatCompletionRequest"), startedOperations)
    }

    // 5. The User-Agent product token (openrouter-kotlin/<version>) is carried on every call by the client config.
    @Test
    fun userAgentCarriesProductToken() = runTest {
        val transport =
            FakeTransport(TransportCapabilities(canSetUserAgent = true))
                .enqueueResponse(200, jsonHeaders, LifecycleFixtures.chatSuccessBody())
        val client = OpenRouter(credential = credential, transport = transport)

        client.chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())

        val userAgent =
            transport.capturedRequests.single().headers
                .single { it.name.equals("User-Agent", ignoreCase = true) }
                .value
        assertTrue(
            userAgent.contains("openrouter-kotlin/$SDK_VERSION"),
            "expected the product token in User-Agent, got '$userAgent'",
        )
    }

    // 6. One RetryBudget is shared across every resource client (ADR 0022 D2). Two design facts shape this test:
    //    (a) the runtime's RetryBudget restores a token on each *successful* logical call, so a shared budget can
    //    only be observed depleted by a call that FAILS without restoring it; and (b) getCredits does not declare a
    //    429 response, so its 429 surfaces as a non-retryable UnknownApiException. We therefore drain the capacity-1
    //    budget with a pre-send connection failure on the idempotent credits GET (retryable, replayed once, which
    //    consumes the only token; the second failure has no token and the call fails), then prove the budget is
    //    shared by showing the *chat* POST on a different resource client cannot retry its (declarable, retryable)
    //    429 — the shared quota is already empty. (The plan's 429->200 credits fixture would have restored the token
    //    and let chat retry; corrected here to match the real RetryBudget and getCredits semantics.)
    @Test
    fun resourceClientsShareOneRetryBudget() = runTest {
        val transport =
            FakeTransport()
                .enqueueFailure(SdkTransportException("connect refused", requestMayHaveReachedServer = false))
                .enqueueFailure(SdkTransportException("connect refused", requestMayHaveReachedServer = false))
                .enqueueResponse(429, jsonHeaders, LifecycleFixtures.errorBody(429)) // chat attempt 1 (no budget left)
        val client = OpenRouter {
            credential = LifecycleFixtures.credential
            this.transport = transport
            retryPolicy = RetryPolicy(maxAttempts = 3)
            retryBudget = 1
        }

        // Credits GET replays the pre-send failure once (consuming the single shared token) then fails.
        assertFailsWith<SdkTransportException> { client.credits.getCredits() }
        assertEquals(2, transport.capturedRequests.size, "credits should make exactly 2 attempts")

        // Chat POST cannot retry: the shared budget is empty, so a single 429 attempt fails immediately.
        assertFailsWith<ChatClient.SendChatCompletionRequestApiException> {
            client.chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())
        }
        assertEquals(3, transport.capturedRequests.size, "chat should make exactly 1 attempt (3 total)")
    }

    // 7. retryBudget < 1 is rejected eagerly at construction.
    @Test
    fun invalidRetryBudgetIsRejected() {
        assertFailsWith<SdkConfigurationException> {
            OpenRouter {
                credential = LifecycleFixtures.credential
                transport = FakeTransport()
                retryBudget = 0
            }
        }
    }
}
