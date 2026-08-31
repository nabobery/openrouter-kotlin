package guides

// region imports
import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import kotlinx.coroutines.flow.collect
// endregion

/** Tutorial: streaming assistant text as a cold Flow. Injected into streaming-with-flow.md. */
suspend fun streamingWithFlow(chat: ChatClient) {
    // region stream
    val events = chat.stream(
        model = "openrouter/free",
        messages = listOf(userMessage("Stream a haiku about Kotlin.")),
    )
    // endregion

    // region deltas
    // `contentDeltas()` projects just the assistant text deltas; collection sends the request and cancellation
    // stops consumption immediately. The stream is cold: each `collect` starts a fresh request.
    events.contentDeltas().collect { delta -> print(delta) }
    // endregion
}
