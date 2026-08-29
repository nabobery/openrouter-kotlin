package com.nabobery.openrouter.conformance

import com.nabobery.openrouter.CreateScimGroupMappingRequest
import com.nabobery.openrouter.InlineCreateScimGroupMappingRequestRoleX822fc544
import com.nabobery.openrouter.InlineUpdateScimGroupMappingRequestRoleX8db0cf64
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.UpdateScimGroupMappingRequest
import com.nabobery.openrouter.WorkspaceBudgetInterval
import com.nabobery.openrouter.datasets.DatasetsClient
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkResponseResult
import com.nabobery.sdkgen.runtime.SdkSerializationException
import com.nabobery.sdkgen.runtime.UnknownApiException
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Resource conformance for the operations added by the 2026-08-29 re-pin.
 *
 * Each row drives one new operation through the generated client and asserts the encoded method + path on the
 * recorded request. `FakeTransport` captures the request *before* response decoding, so a stub `{}` body is fine
 * for the encode assertion; that stub then either fails to decode (`SdkSerializationException`) or is an unmapped
 * status (`UnknownApiException`), which the loop tolerates narrowly (any *other* failure — an encode error, a wrong
 * path, a transport fault — propagates and fails the row).
 * Response decoding and `WithResponse` agreement are additionally proven for one representative new op
 * (`getSessionCost`) with a real success body below.
 */
class ResourceConformanceTest {
    private val credential = OpenRouterCredentials.static("sk-or-conf")
    private val json = listOf(SdkHeader("Content-Type", "application/json"))

    private data class Row(
        val name: String,
        val method: String,
        val path: String,
        val call: suspend (OpenRouter) -> Unit,
    )

    @Test
    fun newOperationsEncodeCorrectMethodAndPath() = runTest {
        val rows = listOf(
            // Containers
            Row("listContainerFiles", "GET", "/containers/c1/files") { it.containers.listContainerFiles("c1") },
            Row("getContainerFile", "GET", "/containers/c1/files/f1") { it.containers.getContainerFile("c1", "f1") },
            Row("downloadContainerFileContent", "GET", "/containers/c1/files/f1/content") {
                it.containers.downloadContainerFileContent("c1", "f1")
            },
            Row("promoteContainerFile", "POST", "/containers/c1/files/f1/promote") {
                it.containers.promoteContainerFile("c1", "f1")
            },
            // Datasets
            Row("getSessionCost", "GET", "/datasets/session-cost") { it.datasets.getSessionCost() },
            // SCIM — reads
            Row("listScimGroupMappings", "GET", "/scim/group-mappings") { it.scim.listScimGroupMappings() },
            Row("getScimGroupMapping", "GET", "/scim/group-mappings/gm1") { it.scim.getScimGroupMapping("gm1") },
            Row("listScimGroups", "GET", "/scim/groups") { it.scim.listScimGroups() },
            // SCIM — writes (request-bodied POST/PATCH; the body is encoded on the captured request)
            Row("createScimGroupMapping", "POST", "/scim/group-mappings") {
                it.scim.createScimGroupMapping(
                    CreateScimGroupMappingRequest(
                        role = InlineCreateScimGroupMappingRequestRoleX822fc544.Admin,
                        scimGroupId = "g1",
                        workspaceId = "ws",
                    ),
                )
            },
            Row("updateScimGroupMapping", "PATCH", "/scim/group-mappings/gm1") {
                it.scim.updateScimGroupMapping(
                    UpdateScimGroupMappingRequest(role = InlineUpdateScimGroupMappingRequestRoleX8db0cf64.Admin),
                    id = "gm1",
                )
            },
        )
        for (row in rows) {
            val transport = FakeTransport().enqueueResponse(200, json, FakeByteStream(listOf("{}".encodeToByteArray())))
            val client = OpenRouter(credential, transport)
            try {
                row.call(client)
            } catch (e: Throwable) {
                // The stub 200/`{}` response need not satisfy the op's declared success status or schema: a decode
                // failure (SdkSerializationException) or an unmapped-status result (UnknownApiException — e.g. a POST
                // whose success is 201) is expected and harmless here, because the encoded request was captured
                // before the response was evaluated. Anything else (a genuine encode/path/transport fault) re-throws
                // and fails the row loudly; a pre-capture failure additionally trips the `single()` assertion below.
                if (e !is SdkSerializationException && e !is UnknownApiException) throw e
            }
            val req = transport.capturedRequests.single()
            assertEquals(row.method, req.method, "${row.name} method")
            assertTrue(req.uri.contains(row.path), "${row.name}: '${req.uri}' does not contain '${row.path}'")
        }
    }

    @Test
    fun getSessionCostDecodesSuccessBodyAndWithResponseAgrees() = runTest {
        // A real, well-formed success body for a new op: proves the response actually decodes (not just encodes),
        // and that the `WithResponse` variant reports the same successful exchange.
        val bodyText =
            """{"data":[],"meta":{"as_of":"2026-01-01","version":"v1","window_days":null,"window_end_date":null}}"""
        fun transport() =
            FakeTransport().enqueueResponse(200, json, FakeByteStream(listOf(bodyText.encodeToByteArray())))

        val decoded = OpenRouter(credential, transport()).datasets.getSessionCost()
        assertTrue(decoded.data.isEmpty(), "expected empty session-cost data")
        assertEquals("2026-01-01", decoded.meta.asOf)

        val result = OpenRouter(credential, transport()).datasets.getSessionCostWithResponse()
        val matched = assertIs<SdkResponseResult.Matched<*>>(result)
        assertEquals(200, matched.statusCode)
        val success = assertIs<DatasetsClient.GetSessionCostResponse.SuccessJson>(matched.value)
        assertEquals(decoded.data, success.json.data)
        assertEquals(decoded.meta.asOf, success.json.meta.asOf)
    }

    // ---- Known generator defect: enum-typed PATH parameters render as the enum NAME, not the wire value. ----
    // getWorkspaceBudget/deleteWorkspaceBudget/upsertWorkspaceBudget send `/budgets/Daily` where OpenRouter documents
    // the lowercase wire value `daily`. Tracked in docs/coverage/exception-register.md and the upstream proposal. We
    // do NOT assert the broken path as if it were correct: the characterization test pins current behaviour so a
    // regression is loud, and the @Ignore-d intent test carries the correct expectation to un-ignore once fixed.

    @Test
    fun getWorkspaceBudgetEncodesEnumNameNotWireValue_knownGeneratorDefect() = runTest {
        // The correct wire value exists on the model and is what the request SHOULD use.
        assertEquals("daily", WorkspaceBudgetInterval.Daily.value)

        val transport = FakeTransport().enqueueResponse(200, json, FakeByteStream(listOf("{}".encodeToByteArray())))
        val client = OpenRouter(credential, transport)
        try {
            client.workspaces.getWorkspaceBudget("ws", WorkspaceBudgetInterval.Daily)
        } catch (expected: SdkSerializationException) {
            // stub body; see the matrix above.
        }
        val uri = transport.capturedRequests.single().uri
        // DEFECT: renders the enum object name, so this currently holds. When the generator is fixed, this fails and
        // the @Ignore-d intent test below should be un-ignored.
        assertTrue(uri.contains("/budgets/Daily"), "expected the known-defect path, got '$uri'")
        assertTrue(!uri.contains("/budgets/daily"), "path unexpectedly already uses the correct wire value: '$uri'")
    }

    // Un-ignore when kotlin-sdkgen renders enum path parameters by their wire value; then delete the defect test above.
    @Test
    @Ignore
    fun getWorkspaceBudgetShouldEncodeLowercaseWireValue() = runTest {
        val transport = FakeTransport().enqueueResponse(200, json, FakeByteStream(listOf("{}".encodeToByteArray())))
        val client = OpenRouter(credential, transport)
        try {
            client.workspaces.getWorkspaceBudget("ws", WorkspaceBudgetInterval.Daily)
        } catch (expected: SdkSerializationException) {
        }
        assertTrue(transport.capturedRequests.single().uri.contains("/budgets/daily"))
    }
}
