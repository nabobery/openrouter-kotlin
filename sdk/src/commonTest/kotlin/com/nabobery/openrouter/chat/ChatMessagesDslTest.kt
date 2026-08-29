package com.nabobery.openrouter.chat

import com.nabobery.openrouter.ChatMessages
import com.nabobery.openrouter.SdkJson
import com.nabobery.openrouter.chatRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The typed message helpers and `messages { }` DSL build [ChatMessages] branches by decoding canonical
 * JSON through the union serializer, so `raw` (the serialization authority) carries exactly what goes on
 * the wire. Assertions compare the encoded JSON object (order-independent).
 */
class ChatMessagesDslTest {
    private fun encoded(message: ChatMessages) = SdkJson.encodeToJsonElement<ChatMessages>(message).jsonObject

    @Test
    fun systemMessageEncodesRoleAndStringContent() {
        val m = systemMessage("Answer concisely.")
        assertIs<ChatMessages.ChatSystemMessage>(m)
        assertEquals(
            buildJsonObject {
                put("role", "system")
                put("content", "Answer concisely.")
            },
            encoded(m),
        )
    }

    @Test
    fun userMessageWithPartsEncodesContentArray() {
        val m = userMessage(listOf(textPart("a"), imagePart("https://x/y.png")))
        val obj = encoded(m)
        assertEquals("user", obj["role"]!!.jsonPrimitive.content)
        val content = obj["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("a", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "https://x/y.png",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun assistantMessageCarriesContent() {
        val m = assistantMessage("ok")
        assertIs<ChatMessages.ChatAssistantMessage>(m)
        val obj = encoded(m)
        assertEquals("assistant", obj["role"]!!.jsonPrimitive.content)
        assertEquals("ok", obj["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolMessageCarriesToolCallId() {
        val m = toolMessage("call_1", "42")
        assertIs<ChatMessages.ChatToolMessage>(m)
        val obj = encoded(m)
        assertEquals("tool", obj["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", obj["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("42", obj["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun messagesDslPreservesOrderAndCopiesDefensively() {
        val request =
            chatRequest {
                model = "m"
                messages {
                    system("s")
                    user("u")
                    assistant("a")
                    tool("id", "t")
                }
            }
        assertEquals(
            listOf("system", "user", "assistant", "tool"),
            request.messages.map { it.raw["role"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun addAcceptsPrebuiltMessages() {
        val existing = userMessage("hi")
        val request =
            chatRequest {
                model = "m"
                messages { add(existing) }
            }
        assertSame(existing, request.messages.single())
    }
}
