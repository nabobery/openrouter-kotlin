package com.nabobery.openrouter.scim

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkResponseResult
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import com.nabobery.sdkgen.testing.assertClosedNormally
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Evidence tests for the SCIM sync-job operations landed by the `d49dda78` re-pin: `createScimSyncJob`
 * (POST `/scim/sync-jobs`, documented success `202`) and `getScimSyncJob` (GET `/scim/sync-jobs/{id}`). A fake
 * transport enqueues the minimal documented job body; the call goes through the curated root (`OpenRouter.scim`);
 * the captured request's exact method and path are asserted; and the response stream is proven closed.
 */
class ScimSyncJobsContractTest {
    private val credential = OpenRouterCredentials.static("sk-or-scim")
    private val json = listOf(SdkHeader("Content-Type", "application/json"))

    private fun client(transport: FakeTransport) = OpenRouter(credential, transport)

    private fun body(s: String) = FakeByteStream(listOf(s.encodeToByteArray()))

    // A minimal ScimSyncJob: the nullable fields are required-but-nullable, so the keys are present with `null`.
    private fun jobBody(id: String, status: String) = body(
        """{"data":{"created_at":"2026-01-01T00:00:00Z","deleted_groups":null,"error_message":null,""" +
            """"finished_at":null,"id":"$id","started_at":null,"status":"$status","synced_groups":null}}""",
    )

    @Test
    fun createScimSyncJobIsAPostThatQueuesAndDecodesTheJob() = runTest {
        val payload = jobBody(id = "job_1", status = "queued")
        // The spec documents 202 (job queued) with a Location poll header.
        val headers = json + SdkHeader("Location", "/scim/sync-jobs/job_1")
        val transport = FakeTransport().enqueueResponse(202, headers, payload)

        val result = client(transport).scim.createScimSyncJob()

        assertEquals("job_1", result.data.id)
        assertEquals("queued", result.data.status.value)
        assertNull(result.data.syncedGroups)
        val request = transport.capturedRequests.single()
        assertEquals("POST", request.method)
        assertTrue(request.uri.endsWith("/scim/sync-jobs"), request.uri)
        payload.assertClosedNormally()
    }

    @Test
    fun getScimSyncJobIsAGetThatDecodesTheJobByIdOnThePath() = runTest {
        val payload = jobBody(id = "job_42", status = "running")
        val transport = FakeTransport().enqueueResponse(200, json, payload)

        val result = client(transport).scim.getScimSyncJob("job_42")

        assertEquals("job_42", result.data.id)
        assertEquals("running", result.data.status.value)
        val request = transport.capturedRequests.single()
        assertEquals("GET", request.method)
        assertTrue(request.uri.endsWith("/scim/sync-jobs/job_42"), request.uri)
        payload.assertClosedNormally()
    }

    @Test
    fun getScimSyncJobWithResponseExposesTheStatus() = runTest {
        val payload = jobBody(id = "job_7", status = "succeeded")
        val transport = FakeTransport().enqueueResponse(200, json, payload)

        val result = client(transport).scim.getScimSyncJobWithResponse("job_7")

        val matched = assertIs<SdkResponseResult.Matched<*>>(result)
        assertEquals(200, matched.statusCode)
        payload.assertClosedNormally()
    }
}
