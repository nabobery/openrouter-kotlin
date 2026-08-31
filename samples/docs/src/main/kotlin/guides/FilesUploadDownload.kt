@file:OptIn(OpenRouterExperimentalApi::class)

package guides

// region imports
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.PaginationLimits
import com.nabobery.openrouter.files.downloadBytes
import com.nabobery.openrouter.files.listAllFiles
import com.nabobery.openrouter.files.upload
import kotlinx.coroutines.flow.collect
// endregion

/** How-to: upload, download, and walk files. Injected into files-upload-and-download.md. */
suspend fun filesUploadAndDownload(client: OpenRouter) {
    // region upload
    // The curated `upload` sends the bytes as the multipart `file` part (the codec fixes part name/content type).
    client.files.upload("hello, world".encodeToByteArray())
    // endregion

    // region download
    // `downloadBytes` buffers the whole file, bounded by maxBytes (default 64 MiB); stream large files instead.
    val bytes = client.files.downloadBytes(fileId = "file_abc123")
    println("downloaded ${bytes.size} bytes")
    // endregion

    // region list-all
    // `listAllFiles` walks every page as a cold Flow, following the provider-specific continuation; bound it with
    // PaginationLimits on a large workspace.
    client.files
        .listAllFiles(limits = PaginationLimits(maxPages = 5))
        .collect { entry -> println(entry) }
    // endregion
}
