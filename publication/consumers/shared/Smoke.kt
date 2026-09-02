package consumers

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import com.nabobery.openrouter.testing.enqueueChatCompletion
import com.nabobery.openrouter.testing.enqueueChatStream
import com.nabobery.openrouter.testing.fake
import com.nabobery.openrouter.testing.openRouterFakeTransport
import kotlinx.coroutines.flow.toList

/** Exercises both published artifacts end-to-end without network; returns a line the consumer prints. */
suspend fun smoke(): String {
    val transport = openRouterFakeTransport()
    val client = OpenRouter.fake(transport)
    transport.enqueueChatCompletion("resolved")
    val reply = client.chat.send("openrouter/test", listOf(userMessage("ping")))
    transport.enqueueChatStream("str", "eam")
    val deltas = client.chat.stream("openrouter/test", listOf(userMessage("ping"))).contentDeltas().toList()
    check(deltas == listOf("str", "eam")) { "stream mismatch: $deltas" }
    return "openrouter-kotlin OK: ${reply.choices.first().message.content?.branch1}"
}
