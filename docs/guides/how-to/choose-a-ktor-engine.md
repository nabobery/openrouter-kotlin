# Choose a Ktor engine

The SDK does not bundle an HTTP engine — you inject a Ktor `HttpClient`, so you choose the engine that fits your
target and own its lifecycle (ADR 0003). Any Ktor 3 engine works.

## Recommended engines per target

| Target | Common engine choices |
| --- | --- |
| JVM / Android | CIO, OkHttp, Java |
| Android | OkHttp (recommended), CIO |
| Apple (macOS/iOS) | Darwin |
| Linux / native desktop | CIO |
| Windows (mingw) | WinHttp |
| JS (Node / browser) | Js |

Each engine choice is demonstrated by a runnable sample — see the one-sample-per-engine table in
[`samples/README.md`](../../../samples/README.md) rather than duplicating it here.

## Owning the client

```kotlin
// Construct once, share across calls, and close when your application shuts down.
HttpClient(CIO).use { http ->
    val client = OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = http)
    // ... use client ...
}
```

The SDK never closes a client you injected on a per-call path; you own construction and disposal. Scope one client
to your application (or a DI graph), not to each request.
