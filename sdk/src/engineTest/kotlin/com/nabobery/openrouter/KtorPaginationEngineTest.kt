package com.nabobery.openrouter

import com.nabobery.openrouter.pagination.PaginationFixtures.modelsPage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-engine pagination lane over Ktor `MockEngine`, shared by the JVM and macosArm64 test tasks via the
 * `engineTest` source set. Runs under `runBlocking` (real time) with generous timeouts, like the streaming lane.
 * Proves the generated offset flows walk and cancel correctly through a real Ktor pipeline.
 */
class KtorPaginationEngineTest {
    private val credential = OpenRouterCredentials.static("sk-or-page-engine")

    @Test
    fun pagesWalkThroughKtor() = runBlocking {
        val http =
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val page = if (request.url.parameters["offset"] ==
                            "2"
                        ) {
                            modelsPage("m3")
                        } else {
                            modelsPage("m1", "m2")
                        }
                        respond(
                            content = page,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }
        try {
            val client = OpenRouter(credential = credential, httpClient = http)
            val pages = withTimeout(5_000) { client.models.getModelsPages(limit = 2).toList() }
            assertEquals(2, pages.size)
            val uris = (http.engine as MockEngine).requestHistory.map { it.url.toString() }
            assertEquals(2, uris.size)
            assertTrue(uris[0].contains("limit=2"))
            assertTrue(uris[1].contains("offset=2"))
        } finally {
            http.close()
        }
    }

    @Test
    fun cancellationMidWalkThroughKtor() = runBlocking {
        val secondPageCancelled = CompletableDeferred<Unit>()
        val http =
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        if (request.url.parameters["offset"] == "2") {
                            try {
                                while (true) delay(25) // hold the second page open until the collector cancels
                            } finally {
                                secondPageCancelled.complete(Unit)
                            }
                        }
                        respond(
                            content = modelsPage("m1", "m2"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }
        try {
            val client = OpenRouter(credential = credential, httpClient = http)
            val job =
                launch(Dispatchers.Default) {
                    client.models.getModelsItems(limit = 2).collect { }
                }
            delay(200) // let page 1 complete and the page-2 request reach the engine
            job.cancelAndJoin()
            withTimeout(5_000) { secondPageCancelled.await() }
        } finally {
            http.close()
        }
    }
}
