# Your first chat request

**Outcome:** you will send one non-streaming chat completion and print the reply.

## 1. Add the dependency and construct a client

You own the Ktor `HttpClient` (ADR 0003) — pick any engine on your target and close it when you are done. Here we
use CIO on the JVM:

<!-- snippet: samples/docs/src/main/kotlin/guides/FirstChatRequest.kt#client -->
```kotlin
val client = OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = http)
```
<!-- /snippet -->

`OpenRouterCredentials.static(...)` wraps the key in a redacting `Secret`; it never appears in `toString()`,
exceptions, or observers.

## 2. Send a request

The curated `chat.send(model, messages) { … }` extension takes a model id and a message list, plus an optional
builder for extra request fields:

<!-- snippet: samples/docs/src/main/kotlin/guides/FirstChatRequest.kt#send -->
```kotlin
val result = client.chat.send(
    model = "openrouter/free",
    messages = listOf(userMessage("Say hello in one sentence.")),
) { maxTokens = 32 }
println(result.choices.firstOrNull()?.message?.content?.raw)
```
<!-- /snippet -->

`userMessage(...)` builds a `ChatMessages` value; `result.choices.first().message.content.raw` is the assistant
text.

## Next steps

- [Stream the response token by token](streaming-with-flow.md).
- [Handle errors](../how-to/handle-errors.md) and [configure retries and deadlines](../how-to/configure-retries-and-deadlines.md).
