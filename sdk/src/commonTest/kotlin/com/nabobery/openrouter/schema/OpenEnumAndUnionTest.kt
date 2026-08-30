package com.nabobery.openrouter.schema

import com.nabobery.openrouter.ChatFinishReasonEnum
import com.nabobery.openrouter.FileListResponse
import com.nabobery.openrouter.FileListResponseNoMatchException
import com.nabobery.openrouter.FileListResponseSerializer
import com.nabobery.openrouter.FileProvider
import com.nabobery.openrouter.InlineCreateScimGroupMappingRequestRoleX822fc544
import com.nabobery.openrouter.InlineSessionCostMetaVersionX5f6b74eb
import com.nabobery.openrouter.Inputs
import com.nabobery.openrouter.SdkJson
import com.nabobery.openrouter.WorkspaceBudgetInterval
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Open enums preserve unknown values (`SdkUnknown`, round-trips byte-identical); discriminated unions are strict —
 * an unknown discriminator throws the union's `NoMatchException` at decode (a documented forward-compat gap,
 * recorded in `docs/coverage/exception-register.md`).
 */
class OpenEnumAndUnionTest {
    private fun <T> assertUnknownRoundTrip(serializer: KSerializer<T>, wireValue: String, assertUnknown: (T) -> Unit) {
        val encoded = "\"$wireValue\""
        val decoded = SdkJson.decodeFromString(serializer, encoded)
        assertUnknown(decoded)
        assertEquals(encoded, SdkJson.encodeToString(serializer, decoded))
    }

    @Test
    fun openEnumUnknownValueRoundTripsUnchanged() {
        // Five distinct resource families: files, chat, workspaces, scim, datasets. Each preserves an unknown wire
        // value as `SdkUnknown` and re-encodes it byte-identically (forward compatibility, FR-API-007).
        assertUnknownRoundTrip(FileProvider.serializer(), "some-future-provider") {
            assertIs<FileProvider.SdkUnknown>(it)
        }
        assertUnknownRoundTrip(ChatFinishReasonEnum.serializer(), "some-future-reason") {
            assertIs<ChatFinishReasonEnum.SdkUnknown>(it)
        }
        assertUnknownRoundTrip(WorkspaceBudgetInterval.serializer(), "biweekly") {
            assertIs<WorkspaceBudgetInterval.SdkUnknown>(it)
        }
        assertUnknownRoundTrip(InlineCreateScimGroupMappingRequestRoleX822fc544.serializer(), "superadmin") {
            assertIs<InlineCreateScimGroupMappingRequestRoleX822fc544.SdkUnknown>(it)
        }
        assertUnknownRoundTrip(InlineSessionCostMetaVersionX5f6b74eb.serializer(), "v99") {
            assertIs<InlineSessionCostMetaVersionX5f6b74eb.SdkUnknown>(it)
        }
    }

    @Test
    fun knownEnumValuesDecodeToNamedObjects() {
        assertEquals(FileProvider.Openai, FileProvider.fromValue("openai"))
        assertEquals(ChatFinishReasonEnum.Stop, ChatFinishReasonEnum.fromValue("stop"))
        assertEquals(WorkspaceBudgetInterval.Daily, WorkspaceBudgetInterval.fromValue("daily"))
        assertEquals(
            InlineCreateScimGroupMappingRequestRoleX822fc544.Admin,
            InlineCreateScimGroupMappingRequestRoleX822fc544.fromValue("admin"),
        )
        assertEquals(InlineSessionCostMetaVersionX5f6b74eb.V1, InlineSessionCostMetaVersionX5f6b74eb.fromValue("v1"))
    }

    @Test
    fun unknownDiscriminatorThrowsBoundedNoMatch() {
        val bogus = """{"_shape":"martian","cursor":"c","data":[],"first_id":"a","has_more":false,"last_id":"z"}"""
        val error = assertFailsWith<FileListResponseNoMatchException> {
            SdkJson.decodeFromString(FileListResponseSerializer, bogus)
        }
        // The message names the branches without echoing the (potentially large) payload back.
        assertTrue((error.message?.length ?: 0) < 2000, "message should be bounded, was ${error.message?.length}")
        assertTrue(error.message!!.contains("matched 0 branches"))
    }

    @Test
    fun unknownExtraFieldsAreIgnoredOnDecodeAndPreservedInRaw() {
        val withExtra = """{"_shape":"openrouter","cursor":"c","data":[],"first_id":"a","has_more":false,""" +
            """"last_id":"z","x_future_field":{"nested":42}}"""
        val decoded = SdkJson.decodeFromString(FileListResponseSerializer, withExtra)
        val openRouter = assertIs<FileListResponse.OpenRouterFileList>(decoded)
        assertTrue(openRouter.raw.containsKey("x_future_field"), "unknown field should survive in raw")
        assertEquals(false, openRouter.hasMore)
    }

    @Test
    fun fromRawAcceptsArbitraryJsonAsEscapeHatch() {
        // US-009: the union's fromRaw builds a validated wrapper around arbitrary matching JSON.
        val inputs = Inputs.fromRaw(JsonPrimitive("just a string prompt"))
        assertTrue(SdkJson.encodeToString(Inputs.serializer(), inputs).contains("just a string prompt"))
    }
}
