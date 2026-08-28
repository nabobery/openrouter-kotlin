package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkAuthentication
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fake-transport smoke test ported from the kotlin-sdkgen conformance corpus
 * (OpenRouterFixtureConformanceTest#ordinaryGeneratedCallUsesFakeTransportAndClosesBody).
 *
 * Proves the generated OpenRouter client + runtime executor + serialization wire together
 * end-to-end in the com.nabobery.openrouter package, with no network and no secrets.
 */
class SmokeTest {
    @Test
    fun ordinaryGeneratedCallUsesFakeTransportAndClosesBody() = runTest {
        val body =
            FakeByteStream(
                listOf(
                    (
                        "{\"choices\":[],\"created\":1,\"id\":\"chat-fixture\",\"model\":\"test\"," +
                            "\"object\":\"chat.completion\",\"system_fingerprint\":null}"
                        ).encodeToByteArray(),
                ),
            )
        val transport =
            FakeTransport().enqueueResponse(
                200,
                listOf(SdkHeader("Content-Type", "application/json")),
                body,
            )

        val result =
            OpenRouterClient(
                transport,
                "https://openrouter.test",
                authentication = SdkAuthentication { it },
            ).chat.sendChatCompletionRequest(chatRequest())

        assertEquals("chat-fixture", result.id)
        assertEquals("sendChatCompletionRequest", transport.capturedRequests.single().operationId)
        assertTrue(body.closed)
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
}
