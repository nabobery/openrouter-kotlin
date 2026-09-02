# Test with a fake transport

The published `io.github.nabobery:kotlin-sdkgen-testing` artifact provides `FakeTransport` — an in-memory transport
that scripts responses in FIFO order and records every request it received. Your tests need no network and no Ktor
engine. Add it as a `testImplementation` dependency and drive the SDK inside `runTest { }`:

<!-- snippet: samples/docs/src/main/kotlin/guides/TestWithFakeTransport.kt#fake -->
```kotlin
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
```
<!-- /snippet -->

`OpenRouter(credential, transport = …)` injects the fake transport in place of a Ktor client. Assert on
`transport.capturedRequests` (method, URI, headers, body) to prove exactly what the SDK sent — the same technique
the SDK's own contract tests use.
