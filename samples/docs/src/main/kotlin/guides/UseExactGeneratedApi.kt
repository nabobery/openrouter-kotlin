package guides

// region imports
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.chatRequest
import com.nabobery.openrouter.chat.userMessage
// endregion

/**
 * How-to: call the exact generated API. The curated `chat.send(...)`/`models.getModelsPages(...)` extensions are
 * ergonomic wrappers; the generated operation methods (named after the OpenAPI operationIds) are always available
 * for full fidelity. Injected into use-the-exact-generated-api.md.
 */
suspend fun useExactGeneratedApi(client: OpenRouter) {
    // region exact
    // Generated operation method + generated request builder — the same surface the curated helpers wrap.
    val request = chatRequest {
        model = "openrouter/free"
        messages = listOf(userMessage("Say hello."))
    }
    val response = client.chat.sendChatCompletionRequest(request)
    println(response)

    // Generated list operation (offset pagination is a curated flow; the single-page generated call is direct).
    val page = client.models.getModels(limit = 10)
    println(page)
    // endregion
}
