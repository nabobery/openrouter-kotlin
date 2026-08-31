package guides

// region imports
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.userMessage
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
// endregion

/**
 * How-to: test without a network using the published `kotlin-sdkgen-testing` FakeTransport. Injected into
 * test-with-a-fake-transport.md. In a real test this body runs inside `runTest { }`.
 */
suspend fun testWithFakeTransport() {
    // region fake
    // FakeTransport scripts responses in FIFO order and records every request it received — no network, no engine.
    val json = listOf(SdkHeader("Content-Type", "application/json"))
    val body = """{"id":"c1","choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
    val transport = FakeTransport().enqueueResponse(200, json, FakeByteStream(listOf(body.encodeToByteArray())))

    val client = OpenRouter(credential = OpenRouterCredentials.static("sk-or-test"), transport = transport)
    client.chat.send(model = "openrouter/free", messages = listOf(userMessage("hi")))

    // Assert on what was actually sent.
    check(transport.capturedRequests.single().uri.contains("/chat/completions"))
    // endregion
}
