package samples

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.runBlocking

/** The HTTP engine for this native target: CIO on Linux, WinHttp on Windows (see the per-target `Engine.kt`). */
expect fun httpEngine(): HttpClientEngineFactory<*>

/** Reads an environment variable (platform-specific: POSIX `getenv` on every desktop native target). */
expect fun readEnv(name: String): String?

/**
 * Native-desktop streaming consumer — one program, engine chosen per target: **Linux** uses the CIO engine,
 * **Windows** uses WinHttp. Same shape as the JVM/macOS/Node samples. Run (on a matching host) with
 * `OPENROUTER_API_KEY=… ./gradlew :samples:native-desktop:runDebugExecutable<Target>`.
 */
fun main() =
    runBlocking {
        val apiKey = readEnv("OPENROUTER_API_KEY") ?: error("Set OPENROUTER_API_KEY")
        val model = "openrouter/free"
        val http = HttpClient(httpEngine())
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
