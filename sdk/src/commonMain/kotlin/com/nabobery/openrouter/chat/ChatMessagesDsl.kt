package com.nabobery.openrouter.chat

import com.nabobery.openrouter.ChatContentItems
import com.nabobery.openrouter.ChatMessages
import com.nabobery.openrouter.ChatRequest
import com.nabobery.openrouter.OpenRouterDsl
import com.nabobery.openrouter.SdkJson
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Typed builders for the generated `ChatMessages` union and its multimodal `ChatContentItems`.
//
// Each helper builds a branch by decoding canonical JSON through the union serializer (rather than the
// generated `of(...)` factory with inline union/enum argument types), so the helpers survive inline-type
// renames across regenerations and always carry validated `raw` JSON — the serialization authority.

/** System-role message with plain-text content. */
public fun systemMessage(text: String): ChatMessages = message("system") { put("content", text) }

/** Developer-role message with plain-text content. */
public fun developerMessage(text: String): ChatMessages = message("developer") { put("content", text) }

/** User-role message with plain-text content. */
public fun userMessage(text: String): ChatMessages = message("user") { put("content", text) }

/** User-role message with multimodal content [parts] (text and/or image parts). */
public fun userMessage(parts: List<ChatContentItems>): ChatMessages =
    message("user") { put("content", SdkJson.encodeToJsonElement(parts)) }

/** Assistant-role message carrying [text] content (proves this does not use the content-less `of(role)` factory). */
public fun assistantMessage(text: String): ChatMessages = message("assistant") { put("content", text) }

/** Tool-role message correlating a tool result to its call via [toolCallId]. */
public fun toolMessage(toolCallId: String, content: String): ChatMessages = message("tool") {
    put("tool_call_id", toolCallId)
    put("content", content)
}

/** Text part for multimodal user content. */
public fun textPart(text: String): ChatContentItems = part {
    put("type", "text")
    put("text", text)
}

/** Image part for multimodal user content (an `https`/`data:` URL, with an optional detail hint). */
public fun imagePart(url: String, detail: String? = null): ChatContentItems = part {
    put("type", "image_url")
    putJsonObject("image_url") {
        put("url", url)
        detail?.let { put("detail", it) }
    }
}

private inline fun message(role: String, body: JsonObjectBuilder.() -> Unit): ChatMessages =
    SdkJson.decodeFromJsonElement(
        buildJsonObject {
            put("role", role)
            body()
        },
    )

private inline fun part(body: JsonObjectBuilder.() -> Unit): ChatContentItems =
    SdkJson.decodeFromJsonElement(buildJsonObject(body))

/**
 * Ordered [ChatMessages] list builder used by the [messages] DSL. Messages are appended in call order and
 * copied defensively on [build].
 */
@OpenRouterDsl
public class ChatMessagesBuilder internal constructor() {
    private val items = mutableListOf<ChatMessages>()

    /** Appends a system-role message. */
    public fun system(text: String) {
        items += systemMessage(text)
    }

    /** Appends a developer-role message. */
    public fun developer(text: String) {
        items += developerMessage(text)
    }

    /** Appends a user-role message with plain-text content. */
    public fun user(text: String) {
        items += userMessage(text)
    }

    /** Appends a user-role message with multimodal content [parts]. */
    public fun user(parts: List<ChatContentItems>) {
        items += userMessage(parts)
    }

    /** Appends an assistant-role message. */
    public fun assistant(text: String) {
        items += assistantMessage(text)
    }

    /** Appends a tool-role message. */
    public fun tool(toolCallId: String, content: String) {
        items += toolMessage(toolCallId, content)
    }

    /** Appends a prebuilt [message] (the same instance). */
    public fun add(message: ChatMessages) {
        items += message
    }

    internal fun build(): List<ChatMessages> = items.toList()
}

/**
 * Sets [ChatRequest.Builder.messages] from an ordered DSL block:
 * `chatRequest { model = "…"; messages { system("…"); user("…") } }`.
 *
 * Note: the generated [ChatRequest.Builder] is not `@OpenRouterDsl`-annotated, so scalar assignments such
 * as `model = …` written inside the `messages { }` block still resolve against the outer request builder.
 */
public fun ChatRequest.Builder.messages(block: ChatMessagesBuilder.() -> Unit) {
    messages = ChatMessagesBuilder().apply(block).build()
}
