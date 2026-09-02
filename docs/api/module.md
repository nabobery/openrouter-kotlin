# Module openrouter-kotlin

A Kotlin Multiplatform client for the OpenRouter API, generated from OpenRouter's OpenAPI contract and wrapped with
a small curated facade. The reference below documents both surfaces: the **generated** operation clients and models
(public and SemVer-governed, ADR 0001) and the **curated** extensions that make the common paths ergonomic
(`chat.send`/`stream`, pagination and byte-stream helpers, typed retry/deadline/attribution configuration). You own
the Ktor `HttpClient` engine and its lifecycle.

Start with the [guides](https://github.com/nabobery/openrouter-kotlin/tree/main/docs/guides) for task-oriented
tutorials and how-tos; this package overview complements the KDoc carried by public source declarations.

# Package com.nabobery.openrouter

The root facade (`OpenRouter`, its builder DSL, `OpenRouterCallOptions`), the layered client policy
(`RetryPolicy`, `RequestDeadlines`, `PaginationLimits`, `Attribution`, `OpenRouterCredentials`), and the generated
operation clients and models for every OpenRouter resource.

# Package com.nabobery.openrouter.chat

Curated chat-completion extensions on the generated `ChatClient`: `send`/`stream`, the `messages { }` DSL and
`userMessage`/`systemMessage` builders, and the streaming `ChatStreamEvent` projections such as `contentDeltas()`.

# Package com.nabobery.openrouter.responses

Curated extensions on the generated Responses client (the GA successor to the former beta responses surface).

# Package com.nabobery.openrouter.files

Curated file helpers on the generated `FilesClient`: `upload`, `downloadBytes`, and the bounded `listAllFiles`
cursor walk over the provider-specific file-list union.

# Package com.nabobery.openrouter.io

Byte-stream helpers: `byteStreamOf`, bounded `readAllBytes(maxBytes)`, and `asFlow(chunkSize)` for streaming large
payloads without buffering.

# Module openrouter-kotlin-testing

The `io.github.nabobery:openrouter-kotlin-testing` artifact: a deterministic, network-free test kit for consumers of
the SDK. It re-exports the `kotlin-sdkgen-testing` fake-transport primitives and adds OpenRouter-specific
conveniences — a fake client factory, a capability-matched fake transport, and contract-proven chat/stream/error
fixtures — so a consumer's tests exercise the real curated facade without a network or a secret.

# Package com.nabobery.openrouter.testing

`OpenRouter.fake` (a curated root over a fake transport, wired with the static `TEST_API_KEY`),
`openRouterFakeTransport()` (a `FakeTransport` whose capabilities match the curated Ktor factory), the
`OpenRouterFixtures` JSON builders, and the fluent `enqueueChatCompletion` / `enqueueChatStream` / `enqueueError` /
`enqueueJson` transport helpers.
