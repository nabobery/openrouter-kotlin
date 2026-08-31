package guides

// region imports
import com.nabobery.openrouter.Attribution
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import io.ktor.client.HttpClient
// endregion

/** How-to: app attribution and safe headers. Injected into attribution-and-headers.md. */
fun attributionAndHeaders(apiKey: String, http: HttpClient) {
    // region attribution
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
    // endregion
    println(client)
}
