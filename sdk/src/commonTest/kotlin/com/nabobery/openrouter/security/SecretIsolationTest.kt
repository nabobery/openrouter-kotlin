@file:OptIn(OpenRouterExperimentalApi::class)

package com.nabobery.openrouter.security

import com.nabobery.openrouter.LifecycleFixtures
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.SdkJson
import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.openrouter.pagination.PaginationFixtures
import com.nabobery.sdkgen.runtime.AttemptClassification
import com.nabobery.sdkgen.runtime.UnknownApiException
import com.nabobery.sdkgen.runtime.observation.AttemptOutcomeSignal
import com.nabobery.sdkgen.runtime.observation.SdkLifecycleObserver
import com.nabobery.sdkgen.runtime.observation.SdkOutcomeKind
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The secret-isolation contract: **SDK-owned representations never carry the credential**. Docs
 * (docs/security/secret-isolation-report.md, docs/security/threat-model.md B1/B2) reference this test.
 *
 * Server-supplied bytes are explicitly out of scope: an untrusted server that echoes the key back can place it
 * into a *bounded* body preview, and no credential-aware redaction of server bodies exists or is planned — the
 * threat model records that as an accepted residual, mitigated by the byte bound asserted in
 * [errorBodyPreviewIsBoundedSoAnEchoingServerLeaksAtMostAPrefix].
 */
class SecretIsolationTest {
    private val apiKey = LifecycleFixtures.API_KEY
    private val credential = LifecycleFixtures.credential
    private val jsonHeaders = LifecycleFixtures.jsonHeaders

    /** Captures the string form of every lifecycle callback argument for leak inspection. */
    private class CapturingObserver(val log: MutableList<String>) : SdkLifecycleObserver {
        override fun callStarted(callId: String, operationId: String, method: String, normalizedRoute: String) {
            log += listOf(callId, operationId, method, normalizedRoute)
        }

        override fun attemptStarted(callId: String, attemptNumber: Int) {
            log += listOf(callId, attemptNumber.toString())
        }

        override fun attemptCompleted(
            callId: String,
            attemptNumber: Int,
            outcome: AttemptOutcomeSignal,
            durationMillis: Long,
        ) {
            log += listOf(callId, attemptNumber.toString(), outcome.toString(), durationMillis.toString())
        }

        override fun retryScheduled(callId: String, delayMillis: Long, classification: AttemptClassification) {
            log += listOf(callId, delayMillis.toString(), classification.toString())
        }

        override fun callCompleted(callId: String, outcome: SdkOutcomeKind, totalAttempts: Int, durationMillis: Long) {
            log += listOf(callId, outcome.toString(), totalAttempts.toString(), durationMillis.toString())
        }

        override fun callFailed(callId: String, kind: SdkOutcomeKind) {
            log += listOf(callId, kind.toString())
        }
    }

    // (a) A lifecycle observer never receives the credential in any callback argument.
    @Test
    fun observerNeverReceivesTheCredential() = runTest {
        val events = mutableListOf<String>()
        val transport = FakeTransport().enqueueResponse(200, jsonHeaders, LifecycleFixtures.chatSuccessBody())
        val client = OpenRouter {
            credential = this@SecretIsolationTest.credential
            this.transport = transport
            observer(CapturingObserver(events))
        }

        client.chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())

        assertTrue(events.isNotEmpty(), "expected lifecycle callbacks to fire")
        assertTrue(events.none { it.contains(apiKey) }, "a lifecycle callback carried the credential: $events")
    }

    // (b) A typed error (401) exposes neither the credential nor the Authorization header value; the redacted
    //     request representation reachable through diagnostics hides the header too.
    @Test
    fun typedErrorAndRequestDiagnosticsNeverCarryTheCredential() = runTest {
        val transport = FakeTransport().enqueueResponse(401, jsonHeaders, LifecycleFixtures.errorBody(401))
        val client = OpenRouter(credential = credential, transport = transport)

        val error = assertFailsWith<ChatClient.SendChatCompletionRequestApiException> {
            client.chat.sendChatCompletionRequest(LifecycleFixtures.chatRequest())
        }

        assertFalse(error.toString().contains(apiKey), "exception.toString() carried the credential")
        assertFalse((error.message ?: "").contains(apiKey), "exception.message carried the credential")
        // FakeTransport records what was sent; SdkRequest.toString() redacts Authorization, so the diagnostic
        // representation of the captured requests must not reveal the key even though it was really transmitted.
        assertFalse(
            transport.capturedRequests.toString().contains(apiKey),
            "captured-request diagnostics leaked the key",
        )
        val authValue = transport.capturedRequests.single().headers
            .single { it.name.equals("Authorization", ignoreCase = true) }.value
        assertTrue(authValue.contains(apiKey), "sanity: the key really was transmitted on the wire")
        assertFalse(error.toString().contains(authValue), "exception.toString() carried the raw Authorization value")
    }

    // (c) An undeclared status surfaces UnknownApiException with a byte-bounded body preview: an echoing server can
    //     leak at most a bounded prefix, never an unbounded body. Assert the bound, not absence.
    @Test
    fun errorBodyPreviewIsBoundedSoAnEchoingServerLeaksAtMostAPrefix() = runTest {
        val oversized = "A".repeat(UnknownApiException.MAX_BODY_PREVIEW_BYTES + 4096)
        val transport = FakeTransport().enqueueResponse(
            418,
            jsonHeaders,
            FakeByteStream(listOf(oversized.encodeToByteArray())),
        )
        val client = OpenRouter(credential = credential, transport = transport)

        val error = assertFailsWith<UnknownApiException> {
            client.models.getModels(limit = 1)
        }

        val preview = error.redactedBodyPreview
        assertTrue(preview != null, "an undeclared-status body should surface a bounded preview")
        assertTrue(
            preview.encodeToByteArray().size <= UnknownApiException.MAX_BODY_PREVIEW_BYTES,
            "body preview exceeded the documented ${UnknownApiException.MAX_BODY_PREVIEW_BYTES}-byte bound",
        )
        assertFalse(preview.contains(apiKey), "the body preview carried the credential")
    }

    // (d) The client root and its credential representation never render the credential.
    @Test
    fun clientAndCredentialToStringNeverRenderTheCredential() {
        val transport = FakeTransport()
        val client = OpenRouter(credential = credential, transport = transport)
        assertFalse(client.toString().contains(apiKey), "OpenRouter.toString() carried the credential")
        assertFalse(credential.toString().contains(apiKey), "the credential provider's toString() carried the key")
        assertFalse(
            OpenRouterCredentials.static(apiKey).toString().contains(apiKey),
            "static credential rendered the key",
        )
    }

    // (e) Offset pagination constructs its own next request from the offset descriptor and never follows a
    //     server-supplied absolute next link, so a foreign `links.next` host is never contacted (and therefore
    //     never receives the credential). Pinned behaviour: the foreign URL is ignored, not fetched.
    @Test
    fun paginationNeverFollowsAForeignNextLink() = runTest {
        val transport = FakeTransport()
            .enqueueResponse(
                200,
                jsonHeaders,
                FakeByteStream(listOf(modelsPageWithForeignNext("m1", "m2").encodeToByteArray())),
            )
            .enqueueResponse(
                200,
                jsonHeaders,
                FakeByteStream(listOf(modelsPageWithForeignNext("m3").encodeToByteArray())),
            )
        val client = OpenRouter(credential = credential, transport = transport)

        client.models.getModelsPages(limit = 2).toList()

        assertTrue(
            transport.capturedRequests.none { it.uri.contains("evil.example") },
            "a request was issued to the foreign next-link host",
        )
        assertTrue(
            transport.capturedRequests.all { it.uri.contains("openrouter.ai") },
            "every request must stay on the configured base origin",
        )
        assertEquals(2, transport.capturedRequests.size)
        assertTrue(
            transport.capturedRequests[1].uri.contains("offset=2"),
            "the second page must advance by offset, not the foreign link",
        )
    }

    private fun modelsPageWithForeignNext(vararg ids: String): String = SdkJson.encodeToString(
        buildJsonObject {
            put("data", buildJsonArray { ids.forEach { add(PaginationFixtures.modelJson(it)) } })
            put("links", buildJsonObject { put("next", "https://evil.example/api/v1/models?offset=2") })
            put("total_count", 999)
        },
    )
}
