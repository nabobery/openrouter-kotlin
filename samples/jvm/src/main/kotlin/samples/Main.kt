@file:OptIn(OpenRouterExperimentalApi::class)

package samples

import com.nabobery.openrouter.Attribution
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.PaginationLimits
import com.nabobery.openrouter.RequestDeadlines
import com.nabobery.openrouter.chat.ChatStreamEvent
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * JVM streaming consumer over the CIO engine. Run with `OPENROUTER_API_KEY=… ./gradlew :samples:jvm:run`.
 * The same program shape runs on Node.js (`:samples:js`) and macOS-native (`:samples:apple`).
 */
fun main(args: Array<String>) =
    runBlocking {
        val apiKey = System.getenv("OPENROUTER_API_KEY") ?: error("Set OPENROUTER_API_KEY")
        val model = args.firstOrNull() ?: "openrouter/free"
        HttpClient(CIO).use { http ->
            val client =
                OpenRouter(
                    credential = OpenRouterCredentials.static(apiKey),
                    httpClient = http,
                    attribution =
                        Attribution(
                            referer = "https://github.com/nabobery/openrouter-kotlin",
                            title = "openrouter-kotlin sample",
                        ),
                )

            // Bounded pagination: walk at most 2 pages of 5 models each (PaginationLimits caps the walk).
            val modelPages =
                client.models
                    .getModelsPages(
                        limit = 5,
                        options = client.options { pagination(PaginationLimits(maxPages = 2)) },
                    ).toList()
            println("Fetched ${modelPages.sumOf { it.items.size }} models across ${modelPages.size} page(s).")

            // Non-streaming completion.
            val result =
                client.chat.send(
                    model = model,
                    messages = listOf(userMessage("Say hello in one sentence.")),
                    options = client.options(),
                ) { maxTokens = 32 }
            println(result.choices.firstOrNull()?.message?.content?.raw)

            // Streaming with a 20 s idle deadline; stop after 200 characters to demonstrate cancellation ownership.
            var printed = 0
            client.chat
                .stream(
                    model = model,
                    messages = listOf(userMessage("Explain structured concurrency in three sentences.")),
                    options = client.options { deadlines(RequestDeadlines(streamIdle = 20.seconds)) },
                ).onEach { if (it is ChatStreamEvent.Error) println("\n[in-band error ${it.error.code}: ${it.error.message}]") }
                .contentDeltas()
                .takeWhile { printed < 200 }
                .collect { delta ->
                    print(delta)
                    printed += delta.length
                }
            println()
        }
    }
