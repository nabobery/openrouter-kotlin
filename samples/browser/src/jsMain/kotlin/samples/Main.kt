package samples

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLPreElement

/**
 * Browser streaming consumer over Ktor's `Js` (fetch) engine. The OpenRouter API allows browser calls with a user
 * key. SECURITY: the key is read from an `<input>` and never bundled into the page; the attribution headers
 * (HTTP-Referer / X-OpenRouter-Title) are your app's identity on the wire — see docs/security-and-privacy.md
 * ("Header safety"). The webpack bundle is loaded by `index.html`.
 */
fun main() {
    val keyInput = document.getElementById("apiKey") as HTMLInputElement
    val runButton = document.getElementById("run") as HTMLButtonElement
    val output = document.getElementById("output") as HTMLPreElement
    val scope = CoroutineScope(Dispatchers.Main)

    runButton.addEventListener("click", {
        val apiKey = keyInput.value.trim()
        if (apiKey.isEmpty()) {
            output.textContent = "Enter your OpenRouter API key first."
            return@addEventListener
        }
        output.textContent = ""
        val http = HttpClient(Js)
        val client = OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = http)
        scope.launch {
            try {
                client.chat
                    .stream(model = "openrouter/free", messages = listOf(userMessage("Say hello in one sentence.")))
                    .contentDeltas()
                    .collect { delta -> output.textContent += delta }
            } catch (t: Throwable) {
                output.textContent += "\n[error: ${t.message}]"
            } finally {
                http.close()
            }
        }
    })
}
