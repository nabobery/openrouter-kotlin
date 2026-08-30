package samples

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv

/**
 * macOS-native streaming consumer over the Darwin engine. Run with
 * `OPENROUTER_API_KEY=… ./gradlew :samples:apple:runDebugExecutableMacosArm64`. The same program shape runs on the
 * JVM (`:samples:jvm`) and Node.js (`:samples:js`).
 */
@OptIn(ExperimentalForeignApi::class)
fun main() =
    runBlocking {
        val apiKey = getenv("OPENROUTER_API_KEY")?.toKString() ?: error("Set OPENROUTER_API_KEY")
        val model = "openrouter/free"
        val http = HttpClient(Darwin)
        try {
            val client = OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = http)

            val result =
                client.chat.send(
                    model = model,
                    messages = listOf(userMessage("Say hello in one sentence.")),
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
