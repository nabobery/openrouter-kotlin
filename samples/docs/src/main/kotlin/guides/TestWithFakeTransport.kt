package guides

// region imports
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
// endregion

/**
 * How-to: test without a network using the `openrouter-kotlin-testing` kit. Injected into
 * test-with-a-fake-transport.md. In a real test this body runs inside `runTest { }`.
 */
suspend fun testWithFakeTransport() {
    // region fake
    // openRouterFakeTransport() reports the curated transport's capabilities (streaming on); OpenRouter.fake wires it
    // up with a static test credential — no network, no secrets. The enqueue helpers script OpenRouter responses.
    val transport = openRouterFakeTransport()
    val client = OpenRouter.fake(transport)

    transport.enqueueChatCompletion(content = "hi")
    val reply = client.chat.send(model = "openrouter/free", messages = listOf(userMessage("hi")))
    check(reply.choices.first().message.content?.branch1 == "hi")

    // Streaming is scripted the same way; contentDeltas() projects the token deltas.
    transport.enqueueChatStream("he", "llo")
    val deltas = client.chat.stream(model = "openrouter/free", messages = listOf(userMessage("hi"))).contentDeltas().toList()
    check(deltas == listOf("he", "llo"))

    // Assert on what was actually sent.
    check(transport.capturedRequests.first().uri.contains("/chat/completions"))
    // endregion
}
