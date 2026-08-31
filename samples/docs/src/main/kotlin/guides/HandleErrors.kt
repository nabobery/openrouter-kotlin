package guides

// region imports
import com.nabobery.openrouter.ChatMessages
import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.sendWithResponse
import com.nabobery.openrouter.chat.userMessage
import com.nabobery.openrouter.chatRequest
import com.nabobery.sdkgen.runtime.SdkResponseResult
import com.nabobery.sdkgen.runtime.SdkTimeoutException
// endregion

/** How-to: handle typed failures, response metadata, and timeout phases. Injected into handle-errors.md. */
suspend fun handleErrors(chat: ChatClient) {
    val messages = listOf(userMessage("Hello"))

    // region typed-error
    // Non-success responses declared by the operation surface as a typed exception carrying the status code.
    try {
        chat.send(model = "openrouter/free", messages = messages)
    } catch (e: ChatClient.SendChatCompletionRequestApiException) {
        System.err.println("chat failed with HTTP ${e.statusCode}")
    } catch (e: SdkTimeoutException) {
        // `phase` tells you which deadline fired (ATTEMPT, TOTAL, STREAM_IDLE, PAGINATION_BUDGET).
        System.err.println("timed out in phase ${e.phase}")
    }
    // endregion

    // region with-response
    // `sendWithResponse` exposes status, headers, and the request id without giving up typed decoding.
    when (val result = chat.sendWithResponse(buildChatRequest(messages))) {
        is SdkResponseResult.Matched -> println("ok ${result.statusCode}, request ${result.requestId}")
        else -> println("unmatched response alternative")
    }
    // endregion
}

private fun buildChatRequest(chatMessages: List<ChatMessages>) =
    chatRequest {
        model = "openrouter/free"
        messages = chatMessages
    }
