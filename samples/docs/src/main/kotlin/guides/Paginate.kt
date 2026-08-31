@file:OptIn(OpenRouterExperimentalApi::class)

package guides

// region imports
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.PaginationLimits
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
// endregion

/** How-to: paginate with bounds and Flow idioms. Injected into paginate.md. */
suspend fun paginate(client: OpenRouter) {
    // region pages
    // `xxxPages()` is a cold Flow of pages; `xxxItems()` flattens to a Flow of items. Both start a fresh walk on
    // each collection and issue one request per page.
    val pages = client.models.getModelsPages(limit = 50).toList()
    println("walked ${pages.size} page(s)")
    // endregion

    // region items
    // `take(n)` stops the walk after n items — no further request is issued once the collector cancels.
    val firstThree = client.models.getModelsItems(limit = 50).take(3).toList()
    println("first ${firstThree.size} models")
    // endregion

    // region bounds
    // Bound an unbounded walk with PaginationLimits (per call via `options { pagination(...) }`); maxElapsed covers
    // the whole walk and fails with SdkTimeoutException(PAGINATION_BUDGET).
    client.models
        .getModelsItems(limit = 50, options = client.options { pagination(PaginationLimits(maxPages = 3)) })
        .toList()
    // endregion
}
