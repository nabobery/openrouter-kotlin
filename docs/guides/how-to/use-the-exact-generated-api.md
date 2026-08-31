# Use the exact generated API

The curated extensions (`chat.send(...)`, `models.getModelsPages(...)`, `files.upload(...)`) are ergonomic wrappers
over the **generated** operation methods. The generated surface — named after the OpenAPI `operationId`s — is
always available when you need full fidelity or a field the wrapper does not surface:

<!-- snippet: samples/docs/src/main/kotlin/guides/UseExactGeneratedApi.kt#exact -->
```kotlin
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
```
<!-- /snippet -->

The generated API is public and SemVer-governed (ADR 0001), so you can depend on it directly. Everything the
curated helpers do is expressed in terms of these methods — there is no hidden private surface. Use `WithResponse`
variants for response metadata and the `chatRequest { … }` builder for the full request shape.
