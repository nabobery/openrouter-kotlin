package com.nabobery.openrouter.testing

import com.nabobery.openrouter.ChatResult
import com.nabobery.openrouter.ModelsListResponse
import com.nabobery.openrouter.SdkJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins every fixture to the pinned contract by round-tripping it through the generated serializers. A drift re-pin
 * that changes a required field on `ChatResult` / `ModelsListResponse` fails here first, before a consumer's tests.
 */
class OpenRouterFixturesTest {
    @Test
    fun chatCompletionJsonDecodesThroughGeneratedSerializer() {
        val result = SdkJson.decodeFromString<ChatResult>(OpenRouterFixtures.chatCompletionJson("hi"))
        assertEquals("hi", result.choices.first().message.content?.branch1)
        assertNotNull(result.usage)
    }

    @Test
    fun modelsPageJsonDecodesThroughGeneratedSerializer() {
        val page = SdkJson.decodeFromString<ModelsListResponse>(OpenRouterFixtures.modelsPageJson())
        assertEquals(0, page.data.size)
    }

    // Content with control characters (newline, tab), quotes, a backslash, a low control code, and non-ASCII must
    // still produce VALID JSON that decodes back to the exact input — the old partial encoder (escaping only \ and ")
    // produced invalid JSON for all of these.
    private val tricky = "line1\nline2\ttab \"quoted\" back\\slash \u0001ctrl é unicode"

    @Test
    fun chatCompletionJsonEscapesSpecialCharactersAndDecodesToExactContent() {
        val json = OpenRouterFixtures.chatCompletionJson(tricky, id = "id\"with\"quotes", model = "model\\with\\slash")
        val result = SdkJson.decodeFromString<ChatResult>(json)
        assertEquals(tricky, result.choices.first().message.content?.branch1)
        assertEquals("id\"with\"quotes", result.id)
        assertEquals("model\\with\\slash", result.model)
    }

    @Test
    fun chatChunkJsonEscapesSpecialCharactersAndStaysValidJson() {
        val chunk = Json.parseToJsonElement(OpenRouterFixtures.chatChunkJson(tricky)).jsonObject
        val delta = chunk["choices"]!!.jsonArray.first().jsonObject["delta"]!!.jsonObject
        assertEquals(tricky, delta["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorJsonEscapesSpecialCharactersAndStaysValidJson() {
        val element = Json.parseToJsonElement(OpenRouterFixtures.errorJson(429, tricky))
        assertEquals(tricky, element.jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }
}
