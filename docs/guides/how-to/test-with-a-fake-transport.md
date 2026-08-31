# Test with a fake transport

The published `io.github.nabobery:kotlin-sdkgen-testing` artifact provides `FakeTransport` — an in-memory transport
that scripts responses in FIFO order and records every request it received. Your tests need no network and no Ktor
engine. Add it as a `testImplementation` dependency and drive the SDK inside `runTest { }`:

<!-- snippet: samples/docs/src/main/kotlin/guides/TestWithFakeTransport.kt#fake -->
```kotlin
// FakeTransport scripts responses in FIFO order and records every request it received — no network, no engine.
val json = listOf(SdkHeader("Content-Type", "application/json"))
val body = """{"id":"c1","choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
val transport = FakeTransport().enqueueResponse(200, json, FakeByteStream(listOf(body.encodeToByteArray())))

val client = OpenRouter(credential = OpenRouterCredentials.static("sk-or-test"), transport = transport)
client.chat.send(model = "openrouter/free", messages = listOf(userMessage("hi")))

// Assert on what was actually sent.
check(transport.capturedRequests.single().uri.contains("/chat/completions"))
```
<!-- /snippet -->

`OpenRouter(credential, transport = …)` injects the fake transport in place of a Ktor client. Assert on
`transport.capturedRequests` (method, URI, headers, body) to prove exactly what the SDK sent — the same technique
the SDK's own contract tests use.
