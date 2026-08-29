package com.nabobery.openrouter.files

import com.nabobery.openrouter.FileProvider
import com.nabobery.openrouter.FileResponse
import com.nabobery.openrouter.InlineFilesPostRequestMultipartX7e99eef0
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.io.DEFAULT_MAX_DOWNLOAD_BYTES
import com.nabobery.openrouter.io.byteStreamOf
import com.nabobery.openrouter.io.readAllBytes
import com.nabobery.sdkgen.runtime.CallOptions
import com.nabobery.sdkgen.runtime.SdkByteStream

// Curated media overloads on the generated `FilesClient`. See docs/coverage/exception-register.md: the generated
// multipart codec hardcodes the part name ("file"), a fixed `application/octet-stream` content type, and no
// filename, so `filename`/`contentType` are intentionally NOT surfaced (setting them would be faking wire fields).
//
// NOTE: there is deliberately no curated `listAllFiles(...)` walk. The generated `FileListResponse` union rejects
// an explicit `cursor: null`, which OpenRouter sends on the terminal page (see the exception register), so any
// automatic walk would throw while decoding a normal last page. Until the generator accepts explicit-null nullable
// branch fields, a file-list walk cannot be shipped as a working helper.

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
