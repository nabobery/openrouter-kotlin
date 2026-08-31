package com.nabobery.openrouter.files

import com.nabobery.openrouter.AnthropicFile
import com.nabobery.openrouter.FileListResponse
import com.nabobery.openrouter.FileProvider
import com.nabobery.openrouter.FileResponse
import com.nabobery.openrouter.InlineFilesPostRequestMultipartX7e99eef0
import com.nabobery.openrouter.OpenAiFile
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.OpenRouterFile
import com.nabobery.openrouter.PaginationLimits
import com.nabobery.openrouter.io.DEFAULT_MAX_DOWNLOAD_BYTES
import com.nabobery.openrouter.io.byteStreamOf
import com.nabobery.openrouter.io.readAllBytes
import com.nabobery.sdkgen.runtime.CallOptions
import com.nabobery.sdkgen.runtime.SdkByteStream
import com.nabobery.sdkgen.runtime.SdkPaginationException
import com.nabobery.sdkgen.runtime.SdkTimeoutException
import com.nabobery.sdkgen.runtime.TimeoutPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.TimeSource

// Curated media overloads on the generated `FilesClient`. See docs/coverage/exception-register.md: the generated
// multipart codec hardcodes the part name ("file"), a fixed `application/octet-stream` content type, and no
// filename, so `filename`/`contentType` are intentionally NOT surfaced (setting them would be faking wire fields).

/** Uploads in-memory [bytes] as the multipart `file` part. Max 100 MB per OpenRouter; empty files are rejected server-side. */
@OpenRouterExperimentalApi
public suspend fun FilesClient.upload(
    bytes: ByteArray,
    provider: FileProvider? = null,
    workspaceId: String? = null,
    options: CallOptions = CallOptions(),
): FileResponse = upload(byteStreamOf(bytes), provider, workspaceId, options)

/** Streams [file] as the multipart `file` part; the stream is consumed once by the SDK. */
@OpenRouterExperimentalApi
public suspend fun FilesClient.upload(
    file: SdkByteStream,
    provider: FileProvider? = null,
    workspaceId: String? = null,
    options: CallOptions = CallOptions(),
): FileResponse = uploadFile(
    InlineFilesPostRequestMultipartX7e99eef0(file = file),
    provider = provider,
    workspaceId = workspaceId,
    options = options,
)

/** Downloads the whole file into memory (bounded by [maxBytes]); for large files use `downloadFileContent(...).asFlow()`. */
@OpenRouterExperimentalApi
public suspend fun FilesClient.downloadBytes(
    fileId: String,
    maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    options: CallOptions = CallOptions(),
): ByteArray = downloadFileContent(fileId, options = options).readAllBytes(maxBytes)

/**
 * One file entry from a [FilesClient.listAllFiles] walk. The three provider shapes ([FileListResponse]'s
 * `OpenRouterFileList` / `OpenAiFileList` / `AnthropicFileList` branches) each carry a distinct typed file record,
 * so they are surfaced as distinct branches here rather than flattened into one lossy model — inspect the concrete
 * type (or the wrapped `file`) for the provider-specific fields.
 */
@OpenRouterExperimentalApi
public sealed interface FileListEntry {
    /** An OpenRouter-storage file record. */
    public class OpenRouter(public val file: OpenRouterFile) : FileListEntry

    /** An OpenAI-storage file record (returned when files are stored on the caller's OpenAI key). */
    public class OpenAi(public val file: OpenAiFile) : FileListEntry

    /** An Anthropic-storage file record (returned when files are stored on the caller's Anthropic key). */
    public class Anthropic(public val file: AnthropicFile) : FileListEntry
}

@OptIn(OpenRouterExperimentalApi::class)
private fun FileListResponse.entries(): List<FileListEntry> = when (this) {
    is FileListResponse.OpenRouterFileList -> `data`.map { FileListEntry.OpenRouter(it) }
    is FileListResponse.OpenAiFileList -> `data`.map { FileListEntry.OpenAi(it) }
    is FileListResponse.AnthropicFileList -> `data`.map { FileListEntry.Anthropic(it) }
}

private fun FileListResponse.hasMore(): Boolean = when (this) {
    is FileListResponse.OpenRouterFileList -> hasMore
    is FileListResponse.OpenAiFileList -> hasMore
    is FileListResponse.AnthropicFileList -> hasMore
}

/**
 * Walks every page of [FilesClient.listFiles] as a cold [Flow] of file entries, following the provider-specific
 * continuation (`cursor` for the OpenRouter shape; `after` = `last_id` for the OpenAI shape; `after_id` = `last_id`
 * for the Anthropic shape). Each collection starts a new walk; one request per page; items already emitted are never
 * replayed if a later page fails. Bound the walk with [limits] — a large workspace issues one request per page, so
 * set [PaginationLimits.maxPages], [PaginationLimits.maxItems], or [PaginationLimits.maxElapsed] whenever the
 * collection size is unknown. A server that repeats a continuation token fails the walk with [SdkPaginationException]
 * rather than looping forever.
 */
@OpenRouterExperimentalApi
public fun FilesClient.listAllFiles(
    provider: FileProvider? = null,
    workspaceId: String? = null,
    pageSize: Int? = null,
    limits: PaginationLimits = PaginationLimits(),
    options: CallOptions = CallOptions(),
): Flow<FileListEntry> = flow {
    // The budget is a monotonic deadline so it bounds the *whole* walk; each page fetch gets only the time that
    // remains. The timeout wraps the fetch alone — never `emit` — so a page whose request stalls is cancelled while
    // already-emitted items are never replayed, and the flow's single-coroutine emission invariant is preserved.
    val deadline = limits.maxElapsed?.let { TimeSource.Monotonic.markNow() + it }
    var cursor: String? = null
    var after: String? = null
    var afterId: String? = null
    var pages = 0
    var items = 0L
    while (true) {
        val page = fetchFilesPage(deadline, limits.maxElapsed) {
            listFiles(
                after = after,
                afterId = afterId,
                cursor = cursor,
                limit = pageSize,
                provider = provider,
                workspaceId = workspaceId,
                options = options,
            )
        }
        pages += 1
        for (entry in page.entries()) {
            if (limits.maxItems != null && items >= limits.maxItems) return@flow
            emit(entry)
            items += 1
        }
        if (!page.hasMore()) return@flow
        if (limits.maxPages != null && pages >= limits.maxPages) return@flow
        val previous = cursor ?: after ?: afterId
        val next = when (page) {
            is FileListResponse.OpenRouterFileList -> page.cursor.also { cursor = it }
            is FileListResponse.OpenAiFileList -> page.lastId.also { after = it }
            is FileListResponse.AnthropicFileList -> page.lastId.also { afterId = it }
        } ?: return@flow
        // A server that repeats a continuation token would otherwise loop forever (one billable-free but
        // unbounded request per iteration); fail closed with the runtime's pagination exception.
        if (next == previous) {
            throw SdkPaginationException(
                "listAllFiles: server repeated continuation token '$next' after page $pages.",
            )
        }
    }
}

/**
 * Fetches one page under the remaining [PaginationLimits.maxElapsed] budget. When [deadline] is set the fetch is
 * wrapped in `withTimeoutOrNull(remaining)`; an exhausted budget surfaces as `SdkTimeoutException(PAGINATION_BUDGET)`.
 *
 * `withTimeoutOrNull` (not `withTimeout` + catch) is deliberate: it maps only *its own* pagination deadline to
 * `null`, so a caller's enclosing `withTimeout` that expires during [fetch] propagates as the ordinary
 * `TimeoutCancellationException` rather than being misclassified as the SDK's pagination budget — preserving
 * cancellation identity for the collector.
 */
private suspend fun fetchFilesPage(
    deadline: TimeSource.Monotonic.ValueTimeMark?,
    budget: Duration?,
    fetch: suspend () -> FileListResponse,
): FileListResponse {
    if (deadline == null) return fetch()
    val remaining = -deadline.elapsedNow()
    if (remaining <= Duration.ZERO) {
        throw SdkTimeoutException(TimeoutPhase.PAGINATION_BUDGET, "listAllFiles exceeded maxElapsed ($budget).")
    }
    return withTimeoutOrNull(remaining) { fetch() }
        ?: throw SdkTimeoutException(TimeoutPhase.PAGINATION_BUDGET, "listAllFiles exceeded maxElapsed ($budget).")
}
