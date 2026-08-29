package com.nabobery.openrouter

import com.nabobery.openrouter.chat.ChatClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves credential -> SecuritySchemeAuthentication -> KtorSdkTransport -> decode, end to end on a
 * real Ktor pipeline (MockEngine), for both an inference POST and a management GET, plus a typed error.
 *
 * These run under [runBlocking] (real time) rather than `runTest`: a real Ktor engine hops dispatchers,
 * so under virtual time the operation's baked-in metadata deadline races the MockEngine response. This
 * mirrors the upstream adapter conformance suite, which drives every real-engine test with runBlocking.
 *
 * This test lives in the shared `engineTest` source set, so the same MockEngine lane runs on both the
 * JVM and macosArm64 test tasks (`runBlocking` resolves on both). JS has no `runBlocking` and does not
 * depend on `engineTest`.
 */
class KtorTransportLifecycleTest {
    private val apiKey = "sk-or-ktor-secret"

    private val chatJson =
        "{\"choices\":[],\"created\":1,\"id\":\"ktor-chat\",\"model\":\"test\"," +
            "\"object\":\"chat.completion\",\"system_fingerprint\":null}"

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
    fun inferenceRoundTripThroughKtor() = runBlocking {
        var seenAuth: String? = null
        var seenReferer: String? = null
        var seenTitle: String? = null
        val httpClient =
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        seenAuth = request.headers[HttpHeaders.Authorization]
                        seenReferer = request.headers["HTTP-Referer"]
                        seenTitle = request.headers["X-OpenRouter-Title"]
                        respond(
                            content = ByteReadChannel(chatJson.encodeToByteArray()),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }
        try {
            val client =
                OpenRouter(
                    credential = OpenRouterCredentials.static(apiKey),
                    httpClient = httpClient,
                    attribution = Attribution(referer = "https://example.com", title = "Example"),
                )

            val result = client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())

            assertEquals("ktor-chat", result.id)
            assertEquals("Bearer $apiKey", seenAuth)
            assertEquals("https://example.com", seenReferer)
            assertEquals("Example", seenTitle)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun managementRoundTripThroughKtor() = runBlocking {
        var seenPath: String? = null
        val httpClient =
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        seenPath = request.url.encodedPath
                        respond(
                            content = ByteReadChannel(
                                "{\"data\":{\"total_credits\":42.0,\"total_usage\":7.0}}".encodeToByteArray(),
                            ),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }
        try {
            val client =
                OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = httpClient)

            val result = client.credits.getCredits(options = client.options())

            assertEquals(42.0, result.data.totalCredits)
            assertTrue(assertNotNull(seenPath).contains("credits"), "unexpected path: $seenPath")
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun typedErrorFromKtorCarriesStatusAndRedactsSecret() = runBlocking {
        val httpClient =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = ByteReadChannel(
                                "{\"error\":{\"code\":401,\"message\":\"unauthorized\"}}".encodeToByteArray(),
                            ),
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }
        try {
            val client =
                OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = httpClient)

            val exception =
                assertFailsWith<ChatClient.SendChatCompletionRequestApiException> {
                    client.chat.sendChatCompletionRequest(chatRequest(), options = client.options())
                }

            assertEquals(401, exception.statusCode)
            assertFalse(exception.toString().contains(apiKey))
        } finally {
            httpClient.close()
        }
    }
}
