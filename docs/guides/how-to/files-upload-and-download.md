# Upload and download files

## Upload

The curated `files.upload(...)` sends bytes (or a stream) as the multipart `file` part. The generated multipart
codec fixes the part name and content type, so `filename`/`contentType` are intentionally not surfaced:

<!-- snippet: samples/docs/src/main/kotlin/guides/FilesUploadDownload.kt#upload -->
```kotlin
// The curated `upload` sends the bytes as the multipart `file` part (the codec fixes part name/content type).
client.files.upload("hello, world".encodeToByteArray())
```
<!-- /snippet -->

## Download

`files.downloadBytes(fileId)` buffers the whole file into memory, bounded by `maxBytes` (default 64 MiB). For large
files, stream instead with `downloadFileContent(...).asFlow()`:

<!-- snippet: samples/docs/src/main/kotlin/guides/FilesUploadDownload.kt#download -->
```kotlin
// `downloadBytes` buffers the whole file, bounded by maxBytes (default 64 MiB); stream large files instead.
val bytes = client.files.downloadBytes(fileId = "file_abc123")
println("downloaded ${bytes.size} bytes")
```
<!-- /snippet -->

## List every file

`files.listAllFiles(...)` walks every page as a cold `Flow<FileListEntry>`, following the provider-specific
continuation (`cursor` for OpenRouter; `after`/`after_id` for OpenAI/Anthropic). Bound it with `PaginationLimits`
on a large workspace; a server that repeats a continuation token fails the walk instead of looping forever:

<!-- snippet: samples/docs/src/main/kotlin/guides/FilesUploadDownload.kt#list-all -->
```kotlin
// `listAllFiles` walks every page as a cold Flow, following the provider-specific continuation; bound it with
// PaginationLimits on a large workspace.
client.files
    .listAllFiles(limits = PaginationLimits(maxPages = 5))
    .collect { entry -> println(entry) }
```
<!-- /snippet -->

`upload`, `downloadBytes`, and `listAllFiles` are `@OpenRouterExperimentalApi` (opt-in) pre-1.0.
