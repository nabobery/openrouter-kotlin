package com.nabobery.openrouter.live

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.RequestDeadlines
import com.nabobery.openrouter.chat.ChatStreamEvent
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import com.nabobery.openrouter.chatStreamOptions
import com.nabobery.openrouter.fromEnvironment
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Opt-in live smoke test against the real OpenRouter API. Skipped (not failed) unless the run opts in with
 * `OPENROUTER_LIVE_TESTS=1`; once opted in, a missing/blank `OPENROUTER_API_KEY` is a hard failure, not a skip,
 * so the nightly workflow cannot silently pass when its secret is unset (GitHub resolves an unset secret to the
 * empty string). Budget: exactly two requests, `maxTokens = 24`, `temperature = 0.0`, on the zero-cost
 * `openrouter/free` router (override via `OPENROUTER_LIVE_MODEL`); 60 s total and 20 s idle deadlines. Never
 * prints prompts, responses, or headers beyond the bounded assertion messages.
 */
class LiveChatSmokeTest {
    private val optedIn = System.getenv("OPENROUTER_LIVE_TESTS") == "1"
    private val model = System.getenv("OPENROUTER_LIVE_MODEL") ?: "openrouter/free"

    @Test
    fun nonStreamingAndStreamingChatAgainstTheRealApi() {
        if (!optedIn) {
            println("LiveChatSmokeTest skipped: set OPENROUTER_LIVE_TESTS=1 (and OPENROUTER_API_KEY) to run it")
            return
        }
        // Opted in: the key must be present. Failing (rather than skipping) turns a missing/empty secret into a
        // red build instead of a false green, keeping the live drift signal honest.
        require(!System.getenv("OPENROUTER_API_KEY").isNullOrBlank()) {
            "OPENROUTER_LIVE_TESTS=1 but OPENROUTER_API_KEY is unset or blank; provide a real key or unset " +
                "OPENROUTER_LIVE_TESTS to skip the live smoke test."
        }
        runBlocking {
            HttpClient(CIO).use { http ->
                val client = OpenRouter.fromEnvironment(http)
                val options = client.options {
                    deadlines(RequestDeadlines(total = 60.seconds, streamIdle = 20.seconds))
                }

                val result =
                    client.chat.send(
                        model = model,
                        messages = listOf(userMessage("Reply with the single word: pong")),
                        options = options,
                    ) {
                        maxTokens = 24
                        temperature = 0.0
                    }
                assertTrue(result.choices.isNotEmpty())
                assertNotNull(result.usage)

                val events =
                    client.chat
                        .stream(
                            model = model,
                            messages = listOf(userMessage("Count from 1 to 5, digits only.")),
                            options = options,
                        ) {
                            maxTokens = 24
                            streamOptions = chatStreamOptions { includeUsage = true }
                        }.toList()
                assertTrue(events.filterIsInstance<ChatStreamEvent.Chunk>().isNotEmpty())
                assertTrue(
                    events.none { it is ChatStreamEvent.Error },
                    "in-band error: ${events.filterIsInstance<ChatStreamEvent.Error>().map { it.error.message }}",
                )
                assertNotNull(events.last().chunk.usage, "usage expected on the final chunk with include_usage")
            }
        }
    }
}
