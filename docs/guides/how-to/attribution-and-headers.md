# Attribution and headers

OpenRouter uses two optional headers for app attribution: `HTTP-Referer` and `X-OpenRouter-Title`. The SDK surfaces
these as **typed** `Attribution`, separate from the generic header escape hatch, so you cannot accidentally collide
with a reserved protocol header:

<!-- snippet: samples/docs/src/main/kotlin/guides/AttributionAndHeaders.kt#attribution -->
```kotlin
// Typed attribution is separate from the generic header escape hatch: these map to the documented
// `HTTP-Referer` / `X-OpenRouter-Title` wire headers and inherit to every call (a per-call value overrides).
val client = OpenRouter(
    credential = OpenRouterCredentials.static(apiKey),
    httpClient = http,
    attribution = Attribution(
        referer = "https://github.com/nabobery/openrouter-kotlin",
        title = "openrouter-kotlin guide",
    ),
)
```
<!-- /snippet -->

Client-level attribution inherits to every call; a per-call value overrides it (inherit / replace / clear).

## Reserved headers

The generic header override is deliberately an escape hatch. These are protected (case-insensitively) and cannot be
overridden through it: `Authorization`, `Host`, `Content-Length`, the SDK-controlled content type / accept headers,
and the SDK `User-Agent` product token (`openrouter-kotlin/<version>`). Credentials are attached only after
trusted-host validation, so a header override can never redirect your key to another host.
