@file:OptIn(OpenRouterExperimentalApi::class)

package com.nabobery.openrouter.pagination

import com.nabobery.openrouter.Attribution
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.PaginationLimits
import com.nabobery.openrouter.pagination.PaginationFixtures.credential
import com.nabobery.openrouter.pagination.PaginationFixtures.jsonHeaders
import com.nabobery.openrouter.pagination.PaginationFixtures.modelsPage
import com.nabobery.openrouter.pagination.PaginationFixtures.transportOf
import com.nabobery.sdkgen.runtime.SdkApiException
import com.nabobery.sdkgen.runtime.UnknownApiException
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Offset-pagination contract matrix, driven through the generated `ModelsClient.getModels` /
 * `getModelsPages` / `getModelsItems` flows (offset descriptor: `offset`/`limit`, items at `/data`).
 *
 * Runtime behaviours pinned here (verified against PaginationEngine.kt before writing): `maxPages` and
 * `maxItems` stop the walk **gracefully, without error** — and for `maxItems` the page that crosses the
 * bound is emitted **in full** (a generous, non-truncating bound). `maxElapsed` is a *failure*
 * (`SdkTimeoutException(PAGINATION_BUDGET)`) but the generated `getModelsPages` does not inject a
 * controllable clock, so it cannot be exercised under `runTest` virtual time through the generated path;
 * it is covered by kotlin-sdkgen's own `PaginationEngine` tests.
 */
class OffsetPaginationContractTest {
    private fun client(transport: FakeTransport) = OpenRouter(credential, transport)

    @Test
    fun firstPageIsReturnedDirectlyWithHasNext() = runTest {
        val client = client(transportOf(modelsPage("m1", "m2")))
        val page = client.models.getModels(limit = 2)
        assertEquals(2, page.items.size)
        assertTrue(page.hasNext)
        // Runtime behaviour: the standalone first-page helper (`firstPage`) reports a 1-based pageIndex,
        // whereas the `pages()` flow is 0-based (deviation from the plan's assumed 0 — pinned here).
        assertEquals(1, page.pageIndex)
    }

    @Test
    fun pagesWalkOffsetAndStopOnShortPage() = runTest {
        val transport = transportOf(modelsPage("m1", "m2"), modelsPage("m3"))
        val client = client(transport)
        val pages = client.models.getModelsPages(limit = 2).toList()
        assertEquals(2, pages.size)
        assertEquals(listOf(true, false), pages.map { it.hasNext })
        assertTrue(transport.capturedRequests[0].uri.contains("limit=2"))
        assertFalse(transport.capturedRequests[0].uri.contains("offset=2"))
        assertTrue(transport.capturedRequests[1].uri.contains("offset=2"))
    }

    @Test
    fun itemsFlattenPages() = runTest {
        val transport = transportOf(modelsPage("m1", "m2"), modelsPage("m3"))
        val ids = client(transport).models.getModelsItems(limit = 2).toList().map { it.id }
        assertEquals(listOf("m1", "m2", "m3"), ids)
        assertEquals(2, transport.capturedRequests.size)
    }

    @Test
    fun emptyFirstPageCompletesWithOnePage() = runTest {
        val transport = transportOf(modelsPage())
        val client = client(transport)
        val pages = client.models.getModelsPages(limit = 2).toList()
        assertEquals(1, pages.size)
        assertFalse(pages.single().hasNext)
        assertEquals(emptyList(), client(transportOf(modelsPage())).models.getModelsItems(limit = 2).toList())
    }

    @Test
    fun takeStopsFetching() = runTest {
        val transport = transportOf(modelsPage("m1", "m2"), modelsPage("m3", "m4"), modelsPage("m5", "m6"))
        val ids = client(transport).models.getModelsItems(limit = 2).take(3).toList().map { it.id }
        assertEquals(listOf("m1", "m2", "m3"), ids)
        assertEquals(2, transport.capturedRequests.size)
    }

    @Test
    fun maxPagesBoundStopsTheWalk() = runTest {
        // PaginationEngine: maxPages stops gracefully (no error) after emitting the bound number of pages.
        val transport = transportOf(modelsPage("m1", "m2"), modelsPage("m3", "m4"), modelsPage("m5", "m6"))
        val client = client(transport)
        val pages = client.models.getModelsPages(
            limit = 2,
            options = client.options { pagination(PaginationLimits(maxPages = 2)) },
        ).toList()
        assertEquals(2, pages.size)
        assertEquals(2, transport.capturedRequests.size)
    }

    @Test
    fun maxItemsBoundTruncatesTheItemFlow() = runTest {
        // PaginationEngine contract: maxItems on items() is item-granular — it truncates to exactly maxItems
        // (the page granularity "emit the crossing page in full" applies to pages(), not items()). Two pages
        // are fetched to reach item 3, so 2 requests, but only 3 items are emitted.
        val transport = transportOf(modelsPage("m1", "m2"), modelsPage("m3", "m4"), modelsPage("m5", "m6"))
        val client = client(transport)
        val ids = client.models.getModelsItems(
            limit = 2,
            options = client.options { pagination(PaginationLimits(maxItems = 3)) },
        ).toList().map { it.id }
        assertEquals(listOf("m1", "m2", "m3"), ids)
        assertEquals(2, transport.capturedRequests.size)
    }

    @Test
    fun maxItemsBoundOnPagesEmitsTheCrossingPageInFull() = runTest {
        // The page-granular side of the same bound: pages() emits the page that crosses maxItems in full.
        val transport = transportOf(modelsPage("m1", "m2"), modelsPage("m3", "m4"), modelsPage("m5", "m6"))
        val client = client(transport)
        val pages = client.models.getModelsPages(
            limit = 2,
            options = client.options { pagination(PaginationLimits(maxItems = 3)) },
        ).toList()
        assertEquals(2, pages.size)
        assertEquals(listOf("m1", "m2", "m3", "m4"), pages.flatMap { it.items }.map { it.id })
    }

    @Test
    fun failureAfterEarlierPagesSurfacesAfterThem() = runTest {
        val transport = FakeTransport()
            .enqueueResponse(200, jsonHeaders, FakeByteStream(listOf(modelsPage("m1", "m2").encodeToByteArray())))
            .enqueueResponse(
                500,
                jsonHeaders,
                FakeByteStream(listOf("{\"error\":{\"code\":500,\"message\":\"boom\"}}".encodeToByteArray())),
            )
        val client = client(transport)
        val seen = mutableListOf<String>()
        assertFailsWith<SdkApiException> {
            client.models.getModelsItems(limit = 2).collect { seen += it.id }
        }
        assertEquals(listOf("m1", "m2"), seen)
        assertEquals(2, transport.capturedRequests.size)
    }

    @Test
    fun unmappedRetryableStatusOnPageFetchIsTerminal() = runTest {
        // Retry applies per-page through the same executor path as a single call, but only to *mapped* retryable
        // statuses. getModels declares no 429 alternative, so a 429 on a page fetch decodes to a terminal
        // UnknownApiException (no retry) surfacing after the earlier pages. Retry-per-page for a mapped retryable
        // status mirrors the single-call retry proven by LifecycleContractTest.retriesOn429ThenSucceeds.
        val transport = FakeTransport()
            .enqueueResponse(200, jsonHeaders, FakeByteStream(listOf(modelsPage("m1", "m2").encodeToByteArray())))
            .enqueueResponse(
                429,
                jsonHeaders,
                FakeByteStream(listOf("{\"error\":{\"code\":429,\"message\":\"slow down\"}}".encodeToByteArray())),
            )
        val client = client(transport)
        val seen = mutableListOf<String>()
        assertFailsWith<UnknownApiException> {
            client.models.getModelsItems(limit = 2, options = client.options()).collect { seen += it.id }
        }
        assertEquals(listOf("m1", "m2"), seen)
        assertEquals(2, transport.capturedRequests.size)
    }

    @Test
    fun attributionHeadersReachEveryPage() = runTest {
        val transport = transportOf(modelsPage("m1", "m2"), modelsPage("m3"))
        val client = OpenRouter(credential, transport, attribution = Attribution(title = "t"))
        client.models.getModelsPages(limit = 2).toList()
        assertTrue(transport.capturedRequests.isNotEmpty())
        transport.capturedRequests.forEach { req ->
            assertTrue(req.headers.any { it.name.equals("X-OpenRouter-Title", ignoreCase = true) && it.value == "t" })
        }
    }

    @Test
    fun withoutOptionsPaginationIsUnbounded() = runTest {
        val transport = transportOf(modelsPage("m1", "m2"), modelsPage("m3", "m4"), modelsPage("m5"))
        client(transport).models.getModelsPages(limit = 2).toList()
        assertEquals(3, transport.capturedRequests.size)
    }

    @Test
    fun limitParameterIsPreservedAcrossPages() = runTest {
        // Page 1 must be full (7 items) so the walk advances to page 2, proving limit is preserved across both.
        val fullPage = modelsPage(*(1..7).map { "m$it" }.toTypedArray())
        val transport = transportOf(fullPage, modelsPage("m8"))
        client(transport).models.getModelsPages(limit = 7).toList()
        assertEquals(2, transport.capturedRequests.size)
        transport.capturedRequests.forEach { assertTrue(it.uri.contains("limit=7")) }
    }
}
