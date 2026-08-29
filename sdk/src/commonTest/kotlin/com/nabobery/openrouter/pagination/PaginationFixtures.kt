package com.nabobery.openrouter.pagination

import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.SdkJson
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.auth.CredentialProvider
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared fixtures for the pagination contract matrix. `modelsPage`/`modelJson` are adapted from the kotlin-sdkgen
 * corpus `OpenRouterFixtureConformanceTest`, kept minimal to the re-pinned `Model` required fields
 * (architecture, canonical_slug, created, id, links, name, pricing, top_provider).
 */
internal object PaginationFixtures {
    val jsonHeaders: List<SdkHeader> = listOf(SdkHeader("Content-Type", "application/json"))

    val credential: CredentialProvider = OpenRouterCredentials.static("sk-or-page")

    /** A `FakeTransport` scripted with the given JSON page bodies, in order. */
    fun transportOf(vararg pages: String): FakeTransport {
        val transport = FakeTransport()
        pages.forEach { transport.enqueueResponse(200, jsonHeaders, FakeByteStream(listOf(it.encodeToByteArray()))) }
        return transport
    }

    fun modelsPage(vararg ids: String): String = SdkJson.encodeToString(
        buildJsonObject {
            put("data", buildJsonArray { ids.forEach { add(modelJson(it)) } })
            put("links", buildJsonObject { put("next", JsonNull) })
            put("total_count", ids.size)
        },
    )

    fun modelJson(id: String): JsonObject = buildJsonObject {
        put(
            "architecture",
            buildJsonObject {
                put("input_modalities", buildJsonArray { add(JsonPrimitive("text")) })
                put("instruct_type", JsonNull)
                put("modality", "text->text")
                put("output_modalities", buildJsonArray { add(JsonPrimitive("text")) })
                put("tokenizer", "GPT")
            },
        )
        put("canonical_slug", id)
        put("context_length", 128000)
        put("created", 1)
        put("default_parameters", JsonNull)
        put("expiration_date", JsonNull)
        put("id", id)
        put("knowledge_cutoff", JsonNull)
        put("links", buildJsonObject { put("details", "/api/v1/models/$id/endpoints") })
        put("name", id)
        put("per_request_limits", JsonNull)
        put(
            "pricing",
            buildJsonObject {
                put("completion", "0")
                put("prompt", "0")
            },
        )
        put("supported_parameters", buildJsonArray {})
        put("supported_voices", JsonNull)
        put("top_provider", buildJsonObject { put("is_moderated", false) })
    }
}
