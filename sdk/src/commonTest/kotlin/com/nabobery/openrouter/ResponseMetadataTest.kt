package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Evidence for `SdkResponseResult.generationId()` — OpenRouter returns `X-Generation-Id` on every inference
 * response; the accessor surfaces it from the response-aware result regardless of header casing.
 */
class ResponseMetadataTest {
    private val credential = OpenRouterCredentials.static("sk-or-meta")
    private val jsonHeaders = listOf(SdkHeader("Content-Type", "application/json"))

    private suspend fun withResponse(transport: FakeTransport) = OpenRouter(credential, transport).let { client ->
        client.chat.sendChatCompletionRequestWithResponse(
            LifecycleFixtures.chatRequest(),
            options = client.options(),
        )
    }

    @Test
    fun generationIdReadsTheXGenerationIdHeader() = runTest {
        val transport = FakeTransport().enqueueResponse(
            200,
            jsonHeaders + SdkHeader("X-Generation-Id", "gen-123"),
            LifecycleFixtures.chatSuccessBody(),
        )
        assertEquals("gen-123", withResponse(transport).generationId())
    }

    @Test
    fun generationIdIsNullWhenTheHeaderIsAbsent() = runTest {
        val transport = FakeTransport().enqueueResponse(200, jsonHeaders, LifecycleFixtures.chatSuccessBody())
        assertNull(withResponse(transport).generationId())
    }

    @Test
    fun generationIdLookupIsCaseInsensitive() = runTest {
        val transport = FakeTransport().enqueueResponse(
            200,
            jsonHeaders + SdkHeader("x-generation-id", "gen-lower"),
            LifecycleFixtures.chatSuccessBody(),
        )
        assertEquals("gen-lower", withResponse(transport).generationId())
    }
}
