package guides

// region imports
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.userMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
// endregion

/** Tutorial: your first chat request. Compiled by `samplesCheck`; injected into first-chat-request.md. */
suspend fun firstChatRequest(apiKey: String) {
    // The consumer owns the engine (ADR 0003); `use { }` closes it on every path, including exceptions.
    HttpClient(CIO).use { http ->
        // region client
        val client = OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = http)
        // endregion

        // region send
        val result = client.chat.send(
            model = "openrouter/free",
            messages = listOf(userMessage("Say hello in one sentence.")),
        ) { maxTokens = 32 }
        println(result.choices.firstOrNull()?.message?.content?.raw)
        // endregion
    }
}
