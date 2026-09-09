@file:OptIn(OpenRouterExperimentalApi::class)

package com.nabobery.openrouter

import com.nabobery.openrouter.anthropicmessages.AnthropicMessagesClient.CreateMessagesApiException
import com.nabobery.openrouter.anthropicmessages.userMessageParam
import com.nabobery.openrouter.chat.ChatClient.SendChatCompletionRequestApiException
import com.nabobery.openrouter.responses.ResponsesClient.CreateResponsesApiException
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for [openRouterErrorType]: the uniform reader that pulls OpenRouter's stable `error_type`
 * off a caught inference exception across the chat, Anthropic-messages, and responses skins.
 */
class ErrorTypesTest {
    private val apiKey = "sk-or-error-types-secret"
    private val credential = OpenRouterCredentials.static(apiKey)
    private val json = listOf(SdkHeader("Content-Type", "application/json"))

    // Retries disabled so a single enqueued 4xx surfaces its decoded typed error directly
    // (429 is retryable by default and would otherwise exhaust the one-shot fake transport).
    private fun client(transport: FakeTransport) = OpenRouter(credential, transport, retryPolicy = RetryPolicy.None)

    private fun body(s: String) = FakeByteStream(listOf(s.encodeToByteArray()))

    // ---- chat: error.metadata.error_type ----

    private fun chatEnvelope(errorType: String?): String {
        val metadata = if (errorType == null) "" else ""","metadata":{"error_type":"$errorType"}"""
        return """{"error":{"code":429,"message":"rate limited"$metadata}}"""
    }

    @Test
    fun chatReadsErrorTypeFromMetadata() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body(chatEnvelope("rate_limit_exceeded")))
        val e = assertFailsWith<SendChatCompletionRequestApiException> {
            client(transport).chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())
        }
        assertEquals(ApiErrorType.fromValue("rate_limit_exceeded"), e.openRouterErrorType())
        assertEquals(ApiErrorType.RateLimitExceeded, e.openRouterErrorType())
    }

    @Test
    fun chatUnknownErrorTypeIsPreservedAsOpenEnum() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body(chatEnvelope("brand_new_type")))
        val e = assertFailsWith<SendChatCompletionRequestApiException> {
            client(transport).chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())
        }
        val type = e.openRouterErrorType()
        assertIs<ApiErrorType.SdkUnknown>(type)
        assertEquals("brand_new_type", type.value)
    }

    @Test
    fun chatUnmappedErrorTypeRemainsTyped() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body(chatEnvelope("unmapped")))
        val e = assertFailsWith<SendChatCompletionRequestApiException> {
            client(transport).chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())
        }
        assertEquals(ApiErrorType.Unmapped, e.openRouterErrorType())
    }

    @Test
    fun chatMissingErrorTypeIsNull() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body(chatEnvelope(null)))
        val e = assertFailsWith<SendChatCompletionRequestApiException> {
            client(transport).chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())
        }
        assertNull(e.openRouterErrorType())
    }

    @Test
    fun chatErrorNeverLeaksSecretMaterial() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body(chatEnvelope("rate_limit_exceeded")))
        val e = assertFailsWith<SendChatCompletionRequestApiException> {
            client(transport).chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())
        }
        assertTrue("sk-or-" !in e.toString(), "exception rendering leaked secret material: $e")
        assertTrue(apiKey !in (e.message ?: ""), "exception message leaked the api key")
    }

    // ---- messages: error.error_type (typed) ----

    private fun messagesEnvelope(errorType: String?): String {
        val et = if (errorType == null) "" else ",\"error_type\":\"$errorType\""
        return """{"type":"error","request_id":null,"error":{"type":"rate_limit_error","message":"rate limited"$et}}"""
    }

    private fun messagesRequestMinimal(): MessagesRequest = messagesRequest {
        model = "test/model"
        maxTokens = 16
        messages = listOf(userMessageParam("hi"))
    }

    @Test
    fun messagesReadsTypedErrorType() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body(messagesEnvelope("rate_limit_exceeded")))
        val e = assertFailsWith<CreateMessagesApiException> {
            client(transport).anthropicMessages.createMessages(messagesRequestMinimal())
        }
        assertEquals(ApiErrorType.RateLimitExceeded, e.openRouterErrorType())
    }

    @Test
    fun messagesUnknownErrorTypeIsPreserved() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body(messagesEnvelope("some_new_thing")))
        val e = assertFailsWith<CreateMessagesApiException> {
            client(transport).anthropicMessages.createMessages(messagesRequestMinimal())
        }
        assertEquals("some_new_thing", e.openRouterErrorType()?.value)
    }

    @Test
    fun messagesMissingErrorTypeIsNull() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body(messagesEnvelope(null)))
        val e = assertFailsWith<CreateMessagesApiException> {
            client(transport).anthropicMessages.createMessages(messagesRequestMinimal())
        }
        assertNull(e.openRouterErrorType())
    }

    // ---- responses: reuse shared *Response models -> error.metadata.error_type ----

    private fun responsesRequestMinimal(): ResponsesRequest = responsesRequest {
        model = "test/model"
        input = Inputs.fromRaw(JsonPrimitive("hi"))
    }

    @Test
    fun responsesReadsErrorTypeFromMetadata() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body(chatEnvelope("provider_overloaded")))
        val e = assertFailsWith<CreateResponsesApiException> {
            client(transport).responses.createResponses(responsesRequestMinimal())
        }
        assertEquals(ApiErrorType.ProviderOverloaded, e.openRouterErrorType())
    }

    @Test
    fun responsesMissingErrorTypeIsNull() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body(chatEnvelope(null)))
        val e = assertFailsWith<CreateResponsesApiException> {
            client(transport).responses.createResponses(responsesRequestMinimal())
        }
        assertNull(e.openRouterErrorType())
    }

    // ---- fallbacks: unrelated / undecodable throwables return null (never throw) ----

    @Test
    fun unrelatedThrowableReturnsNull() {
        assertNull(RuntimeException("boom").openRouterErrorType())
        assertNull(IllegalStateException().openRouterErrorType())
    }

    @Test
    fun nonJsonBodyReturnsNull() = runTest {
        val transport = FakeTransport().enqueueResponse(429, json, body("this is not json at all"))
        val thrown: Throwable = try {
            client(transport).chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())
            error("expected the call to fail")
        } catch (t: Throwable) {
            t
        }
        // A body that never decodes into a typed error surfaces as some other exception; the reader
        // must return null for it rather than throw.
        assertNull(thrown.openRouterErrorType())
    }
}
