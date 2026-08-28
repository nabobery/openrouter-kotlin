package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkConfigurationException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FromEnvironmentTest {
    private val chatJson =
        "{\"choices\":[],\"created\":1,\"id\":\"env-chat\",\"model\":\"test\"," +
            "\"object\":\"chat.completion\",\"system_fingerprint\":null}"

    private fun mockClient(capture: (HttpRequestData) -> Unit): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                capture(request)
                respond(
                    content = ByteReadChannel(chatJson.encodeToByteArray()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
    }

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

    @Test
    fun readsApiKeyFromEnvironment() = runBlocking {
        var seenAuth: String? = null
        val httpClient = mockClient { seenAuth = it.headers[HttpHeaders.Authorization] }
        try {
            val env = mapOf("OPENROUTER_API_KEY" to "sk-or-env")
            val client =
                OpenRouter.fromEnvironment(httpClient, null, RetryPolicy.Default, null) { env[it] }

            client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())
            assertEquals("Bearer sk-or-env", seenAuth)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun baseUrlOverrideChangesHost() = runBlocking {
        var seenHost: String? = null
        val httpClient = mockClient { seenHost = it.url.host }
        try {
            val env =
                mapOf(
                    "OPENROUTER_API_KEY" to "sk-or-env",
                    "OPENROUTER_BASE_URL" to "https://proxy.example/api/v1",
                )
            val client =
                OpenRouter.fromEnvironment(httpClient, null, RetryPolicy.Default, null) { env[it] }

            client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())
            assertEquals("proxy.example", seenHost)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun defaultBaseUrlWhenUnset() = runBlocking {
        var seenHost: String? = null
        val httpClient = mockClient { seenHost = it.url.host }
        try {
            val env = mapOf("OPENROUTER_API_KEY" to "sk-or-env")
            val client =
                OpenRouter.fromEnvironment(httpClient, null, RetryPolicy.Default, null) { env[it] }

            client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())
            assertEquals("openrouter.ai", seenHost)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun missingKeyThrowsNamingTheVariableWithoutEchoingValue() {
        val httpClient = mockClient { }
        try {
            val exception =
                assertFailsWith<SdkConfigurationException> {
                    OpenRouter.fromEnvironment(httpClient, null, RetryPolicy.Default, null) { null }
                }
            assertTrue(assertNotNull(exception.message).contains("OPENROUTER_API_KEY"))
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun blankKeyThrows() {
        val httpClient = mockClient { }
        try {
            val env = mapOf("OPENROUTER_API_KEY" to "   ")
            assertFailsWith<SdkConfigurationException> {
                OpenRouter.fromEnvironment(httpClient, null, RetryPolicy.Default, null) { env[it] }
            }
        } finally {
            httpClient.close()
        }
    }
}
