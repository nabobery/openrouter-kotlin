@file:OptIn(OpenRouterExperimentalApi::class)

package com.nabobery.openrouter.live

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.PaginationLimits
import com.nabobery.openrouter.RequestDeadlines
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
 * Opt-in live smoke test for the zero-cost management surface: a bounded 2-page `models.getModelsPages` walk and
 * `credits.getCredits`. Returns without making live calls unless `OPENROUTER_LIVE_TESTS=1`; once opted in, a missing/blank
 * `OPENROUTER_API_KEY` is a hard failure. Never prints response bodies beyond bounded assertion messages.
 */
class LiveManagementSmokeTest {
    private val optedIn = System.getenv("OPENROUTER_LIVE_TESTS") == "1"

    @Test
    fun boundedModelsWalkAndCreditsAgainstTheRealApi() {
        if (!optedIn) {
            println("LiveManagementSmokeTest skipped: set OPENROUTER_LIVE_TESTS=1 (and OPENROUTER_API_KEY) to run it")
            return
        }
        require(!System.getenv("OPENROUTER_API_KEY").isNullOrBlank()) {
            "OPENROUTER_LIVE_TESTS=1 but OPENROUTER_API_KEY is unset or blank; provide a real key or unset " +
                "OPENROUTER_LIVE_TESTS to skip the live smoke test."
        }
        runBlocking {
            HttpClient(CIO).use { http ->
                val client = OpenRouter.fromEnvironment(http)
                val options = client.options {
                    deadlines(RequestDeadlines(total = 60.seconds))
                    pagination(PaginationLimits(maxPages = 2))
                }

                val pages = client.models.getModelsPages(limit = 5, options = options).toList()
                assertTrue(pages.size <= 2, "maxPages(2) should cap the walk, got ${pages.size} pages")
                assertTrue(pages.isNotEmpty(), "expected at least one page of models")

                val credits = client.credits.getCredits(options = options)
                assertNotNull(credits, "expected a credits response")
            }
        }
    }
}
