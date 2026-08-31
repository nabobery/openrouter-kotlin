# Paginate

Paginated operations expose two cold-`Flow` idioms: `xxxPages()` (a Flow of pages) and `xxxItems()` (a Flow of
flattened items). Both start a fresh walk on each collection and issue one request per page.

<!-- snippet: samples/docs/src/main/kotlin/guides/Paginate.kt#pages -->
```kotlin
// `xxxPages()` is a cold Flow of pages; `xxxItems()` flattens to a Flow of items. Both start a fresh walk on
// each collection and issue one request per page.
val pages = client.models.getModelsPages(limit = 50).toList()
println("walked ${pages.size} page(s)")
```
<!-- /snippet -->

Use ordinary Flow operators to bound the walk — `take(n)` stops after n items and issues no further request:

<!-- snippet: samples/docs/src/main/kotlin/guides/Paginate.kt#items -->
```kotlin
// `take(n)` stops the walk after n items — no further request is issued once the collector cancels.
val firstThree = client.models.getModelsItems(limit = 50).take(3).toList()
println("first ${firstThree.size} models")
```
<!-- /snippet -->

For unbounded collections, set explicit `PaginationLimits` (per call via `options { pagination(...) }` or as a
client default). `maxPages`/`maxItems` stop the walk gracefully; `maxElapsed` covers the whole walk and fails with
`SdkTimeoutException(phase = PAGINATION_BUDGET)`:

<!-- snippet: samples/docs/src/main/kotlin/guides/Paginate.kt#bounds -->
```kotlin
// Bound an unbounded walk with PaginationLimits (per call via `options { pagination(...) }`); maxElapsed covers
// the whole walk and fails with SdkTimeoutException(PAGINATION_BUDGET).
client.models
    .getModelsItems(limit = 50, options = client.options { pagination(PaginationLimits(maxPages = 3)) })
    .toList()
```
<!-- /snippet -->

The file listing is a curated walk rather than a generated flow — see
[upload and download files](files-upload-and-download.md).
