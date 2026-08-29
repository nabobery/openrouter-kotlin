package com.nabobery.openrouter.schema

import com.nabobery.openrouter.FieldPresence
import com.nabobery.openrouter.SdkJson
import com.nabobery.openrouter.UpdateByokKeyRequest
import com.nabobery.openrouter.UpdateGuardrailRequest
import com.nabobery.openrouter.UpdateObservabilityDestinationRequest
import com.nabobery.openrouter.UpdateWorkspaceRequest
import com.nabobery.openrouter.updateByokKeyRequest
import com.nabobery.openrouter.updateGuardrailRequest
import com.nabobery.openrouter.updateObservabilityDestinationRequest
import com.nabobery.openrouter.updateWorkspaceRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Presence-state fixtures: absent, explicit null, and value are three distinct wire states on every generated
 * PATCH/update body. Verified rule (generated `toNullableFieldState`): a builder property left untouched is Absent
 * (key omitted); set to `null` it is PresentNull (`"field":null` on the wire); set to a value it is PresentValue.
 * FR-API-008 is met — builders can express an explicit null, so no `clearX()` seam is needed.
 *
 * Coverage spans all four generated update models that expose tri-state presence accessors: `UpdateByokKeyRequest`,
 * `UpdateWorkspaceRequest`, `UpdateGuardrailRequest`, and `UpdateObservabilityDestinationRequest`.
 */
class PresenceStateTest {
    private fun <T> wire(serializer: KSerializer<T>, request: T): JsonObject =
        SdkJson.encodeToJsonElement(serializer, request).jsonObject

    @Test
    fun byokAllowedModelsThreeStatesAreDistinct() {
        assertEquals(FieldPresence.Absent, updateByokKeyRequest { }.allowedModelsPresence())
        assertEquals(FieldPresence.PresentNull, updateByokKeyRequest { allowedModels = null }.allowedModelsPresence())
        assertEquals(
            FieldPresence.PresentValue,
            updateByokKeyRequest { allowedModels = listOf("openai/gpt-4") }.allowedModelsPresence(),
        )

        val absent = wire(UpdateByokKeyRequest.serializer(), updateByokKeyRequest { })
        val explicitNull = wire(UpdateByokKeyRequest.serializer(), updateByokKeyRequest { allowedModels = null })
        val value = wire(
            UpdateByokKeyRequest.serializer(),
            updateByokKeyRequest {
                allowedModels =
                    listOf("openai/gpt-4")
            },
        )
        assertFalse(absent.containsKey("allowed_models"))
        assertEquals(JsonNull, explicitNull["allowed_models"])
        assertTrue(value["allowed_models"].toString().contains("openai/gpt-4"))
    }

    @Test
    fun workspaceDescriptionThreeStatesAreDistinct() {
        assertEquals(FieldPresence.Absent, updateWorkspaceRequest { }.descriptionPresence())
        assertEquals(FieldPresence.PresentNull, updateWorkspaceRequest { description = null }.descriptionPresence())
        assertEquals(FieldPresence.PresentValue, updateWorkspaceRequest { description = "team" }.descriptionPresence())

        val absent = wire(UpdateWorkspaceRequest.serializer(), updateWorkspaceRequest { })
        val explicitNull = wire(UpdateWorkspaceRequest.serializer(), updateWorkspaceRequest { description = null })
        val value = wire(UpdateWorkspaceRequest.serializer(), updateWorkspaceRequest { description = "team" })
        assertFalse(absent.containsKey("description"))
        assertEquals(JsonNull, explicitNull["description"])
        assertEquals("team", value["description"].toString().trim('"'))
    }

    @Test
    fun guardrailDescriptionThreeStatesAreDistinct() {
        assertEquals(FieldPresence.Absent, updateGuardrailRequest { }.descriptionPresence())
        assertEquals(FieldPresence.PresentNull, updateGuardrailRequest { description = null }.descriptionPresence())
        assertEquals(FieldPresence.PresentValue, updateGuardrailRequest { description = "pii" }.descriptionPresence())

        val absent = wire(UpdateGuardrailRequest.serializer(), updateGuardrailRequest { })
        val explicitNull = wire(UpdateGuardrailRequest.serializer(), updateGuardrailRequest { description = null })
        val value = wire(UpdateGuardrailRequest.serializer(), updateGuardrailRequest { description = "pii" })
        assertFalse(absent.containsKey("description"))
        assertEquals(JsonNull, explicitNull["description"])
        assertEquals("pii", value["description"].toString().trim('"'))
    }

    @Test
    fun observabilityApiKeyHashesThreeStatesAreDistinct() {
        assertEquals(FieldPresence.Absent, updateObservabilityDestinationRequest { }.apiKeyHashesPresence())
        assertEquals(
            FieldPresence.PresentNull,
            updateObservabilityDestinationRequest { apiKeyHashes = null }.apiKeyHashesPresence(),
        )
        assertEquals(
            FieldPresence.PresentValue,
            updateObservabilityDestinationRequest { apiKeyHashes = listOf("h1") }.apiKeyHashesPresence(),
        )

        val serializer = UpdateObservabilityDestinationRequest.serializer()
        val absent = wire(serializer, updateObservabilityDestinationRequest { })
        val explicitNull = wire(serializer, updateObservabilityDestinationRequest { apiKeyHashes = null })
        val value = wire(serializer, updateObservabilityDestinationRequest { apiKeyHashes = listOf("h1") })
        assertFalse(absent.containsKey("api_key_hashes"))
        assertEquals(JsonNull, explicitNull["api_key_hashes"])
        assertTrue(value["api_key_hashes"].toString().contains("h1"))
    }

    @Test
    fun decodedPresenceDistinguishesNullFromAbsent() {
        // Decode side: an explicit null and an omitted field decode to distinct presence states.
        val withNull = SdkJson.decodeFromString(UpdateWorkspaceRequest.serializer(), """{"description":null}""")
        val without = SdkJson.decodeFromString(UpdateWorkspaceRequest.serializer(), """{}""")
        assertEquals(FieldPresence.PresentNull, withNull.descriptionPresence())
        assertEquals(FieldPresence.Absent, without.descriptionPresence())
    }
}
