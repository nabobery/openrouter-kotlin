package samples

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/**
 * Node.js streaming consumer over the Js engine. Run with
 * `OPENROUTER_API_KEY=… ./gradlew :samples:js:jsNodeProductionRun`. The same program shape runs on the JVM
 * (`:samples:jvm`) and macOS-native (`:samples:apple`).
 */
@OptIn(DelicateCoroutinesApi::class)
fun main() {
    val apiKey = js("process.env.OPENROUTER_API_KEY").unsafeCast<String?>() ?: error("Set OPENROUTER_API_KEY")
    val model = "openrouter/free"
    // Returning the promise keeps the Node process alive until the coroutine completes.
    GlobalScope.promise {
        val http = HttpClient(Js)
        try {
            val client = OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = http)

            val result =
                client.chat.send(
                    model = model,
                    messages = listOf(userMessage("Say hello in one sentence.")),
                    options = client.options(),
                ) { maxTokens = 32 }
            println(result.choices.firstOrNull()?.message?.content?.raw)

            client.chat
                .stream(model = model, messages = listOf(userMessage("Count from 1 to 5, digits only.")))
                .contentDeltas()
                .collect { print(it) }
            println()
        } finally {
            http.close()
        }
    }
}
