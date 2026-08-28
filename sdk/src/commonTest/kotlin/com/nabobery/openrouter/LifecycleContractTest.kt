package com.nabobery.openrouter

import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkResponse
import com.nabobery.sdkgen.runtime.SdkResponseResult
import com.nabobery.sdkgen.runtime.SdkTimeoutException
import com.nabobery.sdkgen.runtime.SdkTransportException
import com.nabobery.sdkgen.runtime.TimeoutPhase
import com.nabobery.sdkgen.runtime.auth.CredentialProvider
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import com.nabobery.sdkgen.testing.assertClosedNormally
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LifecycleContractTest {
    private val apiKey = "sk-or-lifecycle-secret"
    private val credential = OpenRouterCredentials.static(apiKey)

    private fun chatSuccessBody(id: String = "chat-fixture"): FakeByteStream = FakeByteStream(
        listOf(
            (
                "{\"choices\":[],\"created\":1,\"id\":\"$id\",\"model\":\"test\"," +
                    "\"object\":\"chat.completion\",\"system_fingerprint\":null}"
                ).encodeToByteArray(),
        ),
    )

    private fun errorBody(code: Int): FakeByteStream =
        FakeByteStream(listOf("{\"error\":{\"code\":$code,\"message\":\"e$code\"}}".encodeToByteArray()))

    private val jsonHeaders = listOf(SdkHeader("Content-Type", "application/json"))

    private fun chatRequest(): ChatRequest = chatRequest {
        messages =
            listOf(
                SdkJson.decodeFromJsonElement(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "hello")
                    },
                ),
            )
    }

    private fun root(
        transport: FakeTransport,
        retryPolicy: RetryPolicy = RetryPolicy.Default,
        deadlines: RequestDeadlines? = null,
        attribution: Attribution? = null,
        credential: CredentialProvider = this.credential,
    ): OpenRouter = OpenRouter(
        credential = credential,
        transport = transport,
        retryPolicy = retryPolicy,
        deadlines = deadlines,
        attribution = attribution,
    )

    // 1. Retry on an allowlisted 429, then succeed (billable inference POST).
    @Test
    fun retriesOn429ThenSucceeds() = runTest {
        val success = chatSuccessBody()
        val transport =
            FakeTransport()
                .enqueueResponse(429, jsonHeaders, errorBody(429))
                .enqueueResponse(200, jsonHeaders, success)
        val client = root(transport)

        val result = client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())

        assertEquals("chat-fixture", result.id)
        assertEquals(2, transport.capturedRequests.size)
        success.assertClosedNormally()
    }

    // 2. Without options(), the generated default (maxAttempts=1) never retries — pins the hybrid gap.
    @Test
    fun withoutOptionsThereIsNoRetry() = runTest {
        val transport =
            FakeTransport()
                .enqueueResponse(429, jsonHeaders, errorBody(429))
                .enqueueResponse(200, jsonHeaders, chatSuccessBody())
        val client = root(transport)

        assertFailsWith<ChatClient.SendChatCompletionRequestApiException> {
            client.chat.sendChatCompletionRequest(chatRequest())
        }
        assertEquals(1, transport.capturedRequests.size)
    }

    // 3. A non-allowlisted 500 is not retried even with options() — protects billable POSTs.
    @Test
    fun doesNotRetryNonAllowlisted500() = runTest {
        val transport =
            FakeTransport()
                .enqueueResponse(500, jsonHeaders, errorBody(500))
                .enqueueResponse(200, jsonHeaders, chatSuccessBody())
        val client = root(transport)

        assertFailsWith<ChatClient.SendChatCompletionRequestApiException> {
            client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())
        }
        assertEquals(1, transport.capturedRequests.size)
    }

    // 3b. An ambiguous mid-flight connection failure (request may have reached the server) is NEVER replayed
    //     for a non-idempotent inference POST, even with retryConnectionFailures enabled by default. This is the
    //     spend-safety guarantee: a request that may have produced a billable completion is not retried.
    @Test
    fun doesNotReplayAmbiguousConnectionFailureOnInferencePost() = runTest {
        val transport =
            FakeTransport()
                .enqueueFailure(SdkTransportException("connection reset", requestMayHaveReachedServer = true))
                .enqueueResponse(200, jsonHeaders, chatSuccessBody())
        val client = root(transport)

        assertFailsWith<SdkTransportException> {
            client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())
        }
        assertEquals(1, transport.capturedRequests.size)
    }

    // 3c. A connection failure that provably never reached the server IS safe to replay for the same POST,
    //     because no completion could have been produced.
    @Test
    fun replaysPreSendConnectionFailureOnInferencePost() = runTest {
        val success = chatSuccessBody()
        val transport =
            FakeTransport()
                .enqueueFailure(SdkTransportException("connect refused", requestMayHaveReachedServer = false))
                .enqueueResponse(200, jsonHeaders, success)
        val client = root(transport)

        val result = client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())

        assertEquals("chat-fixture", result.id)
        assertEquals(2, transport.capturedRequests.size)
    }

    // 4. A transport-level connection failure is retried on an idempotent GET.
    @Test
    fun retriesConnectionFailureOnIdempotentGet() = runTest {
        val creditsBody =
            FakeByteStream(listOf("{\"data\":{\"total_credits\":10.0,\"total_usage\":2.5}}".encodeToByteArray()))
        val transport =
            FakeTransport()
                .enqueueFailure(IllegalStateException("offline"))
                .enqueueResponse(200, jsonHeaders, creditsBody)
        val client = root(transport)

        val result = client.credits.getCredits(options = client.options())

        assertEquals(10.0, result.data.totalCredits)
        assertEquals(2, transport.capturedRequests.size)
        creditsBody.assertClosedNormally()
    }

    // 5. Credentials are resolved per physical attempt; a rotated key reaches the second attempt.
    @Test
    fun resolvesCredentialPerAttempt() = runTest {
        var calls = 0
        val rotating = OpenRouterCredentials.dynamic {
            calls += 1
            "key-$calls"
        }
        val transport =
            FakeTransport()
                .enqueueResponse(429, jsonHeaders, errorBody(429))
                .enqueueResponse(200, jsonHeaders, chatSuccessBody())
        val client = root(transport, credential = rotating)

        client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())

        assertEquals(2, calls)
        val secondAuth =
            transport.capturedRequests[1].headers.single {
                it.name.equals("Authorization", ignoreCase = true)
            }.value
        assertTrue(secondAuth.contains("key-2"), "expected rotated key in second attempt, got '$secondAuth'")
    }

    // 6. Attribution defaults apply to every call; an explicit per-call header wins.
    @Test
    fun attributionDefaultsAndPerCallOverride() = runTest {
        val transport =
            FakeTransport()
                .enqueueResponse(200, jsonHeaders, chatSuccessBody())
                .enqueueResponse(200, jsonHeaders, chatSuccessBody())
        val client = root(transport, attribution = Attribution(referer = "https://example.com", title = "Example"))

        client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())
        val first = transport.capturedRequests[0].headers
        assertEquals("https://example.com", first.single { it.name.equals("HTTP-Referer", true) }.value)
        assertEquals("Example", first.single { it.name.equals("X-OpenRouter-Title", true) }.value)

        client.chat.sendChatCompletionRequest(
            chatRequest(),
            httpReferer = "https://override.example",
            options = client.options(),
        )
        val secondReferers = transport.capturedRequests[1].headers.filter { it.name.equals("HTTP-Referer", true) }
        assertEquals(1, secondReferers.size)
        assertEquals("https://override.example", secondReferers.single().value)
    }

    // 7. The attempt deadline aborts a slow physical attempt with a typed timeout.
    @Test
    fun attemptDeadlineTimesOut() = runTest {
        val transport =
            FakeTransport().enqueueExchange { _ ->
                delay(10.minutes)
                SdkResponse(200, jsonHeaders, chatSuccessBody())
            }
        val client = root(transport, deadlines = RequestDeadlines(attempt = 1.seconds))

        val timeout =
            assertFailsWith<SdkTimeoutException> {
                client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())
            }
        assertEquals(TimeoutPhase.ATTEMPT, timeout.phase)
    }

    // 8. withResponse surfaces status, headers, and the request id.
    @Test
    fun withResponseExposesMetadata() = runTest {
        val success = chatSuccessBody()
        val headers = jsonHeaders + SdkHeader("x-request-id", "req-abc-123")
        val transport = FakeTransport().enqueueResponse(200, headers, success)
        val client = root(transport)

        val result = client.chat.sendChatCompletionRequestWithResponse(chatRequest(), options = client.options())

        val matched = assertIs<SdkResponseResult.Matched<*>>(result)
        assertEquals(200, matched.statusCode)
        assertEquals("req-abc-123", matched.requestId)
        assertEquals("application/json", matched.headers.single { it.name.equals("Content-Type", true) }.value)
        success.assertClosedNormally()
    }

    // 9. The API key never leaks into exception text or captured-request diagnostics.
    @Test
    fun secretIsRedactedEndToEnd() = runTest {
        val transport =
            FakeTransport().enqueueResponse(401, jsonHeaders, errorBody(401))
        val client = root(transport)

        val exception =
            assertFailsWith<ChatClient.SendChatCompletionRequestApiException> {
                client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())
            }

        assertFalse(exception.toString().contains(apiKey))
        assertFalse((exception.message ?: "").contains(apiKey))
        assertFalse(transport.capturedRequests.toString().contains(apiKey))
    }
}
