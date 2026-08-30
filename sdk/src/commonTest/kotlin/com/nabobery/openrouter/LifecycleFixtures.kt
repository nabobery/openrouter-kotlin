package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.testing.FakeByteStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * Shared lifecycle-test fixtures reused by [LifecycleContractTest] and [ClientDefaultsContractTest]: a static
 * credential, JSON headers, and the canonical chat/credits/error bodies + a minimal chat request. Kept in one
 * place so the two suites exercise the same wire shapes.
 */
internal object LifecycleFixtures {
    const val API_KEY: String = "sk-or-lifecycle-secret"

    val credential = OpenRouterCredentials.static(API_KEY)

    val jsonHeaders = listOf(SdkHeader("Content-Type", "application/json"))

    fun chatSuccessBody(id: String = "chat-fixture"): FakeByteStream = FakeByteStream(
        listOf(
            (
                "{\"choices\":[],\"created\":1,\"id\":\"$id\",\"model\":\"test\"," +
                    "\"object\":\"chat.completion\",\"system_fingerprint\":null}"
                ).encodeToByteArray(),
        ),
    )

    fun creditsBody(): FakeByteStream =
        FakeByteStream(listOf("{\"data\":{\"total_credits\":10.0,\"total_usage\":2.5}}".encodeToByteArray()))

    fun errorBody(code: Int): FakeByteStream =
        FakeByteStream(listOf("{\"error\":{\"code\":$code,\"message\":\"e$code\"}}".encodeToByteArray()))

    fun chatRequest(): ChatRequest = chatRequest {
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
