@file:OptIn(OpenRouterExperimentalApi::class)

package com.nabobery.openrouter.files

import com.nabobery.openrouter.FileListResponse
import com.nabobery.openrouter.FileListResponseSerializer
import com.nabobery.openrouter.InlineFilesPostRequestMultipartX7e99eef0
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.SdkJson
import com.nabobery.openrouter.io.byteStreamOf
import com.nabobery.sdkgen.runtime.SdkByteStream
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkRequest
import com.nabobery.sdkgen.runtime.SdkRequestBody
import com.nabobery.sdkgen.runtime.SdkResponse
import com.nabobery.sdkgen.runtime.bodies.TransferDirection
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import com.nabobery.sdkgen.testing.RecordingTransferObserver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesContractTest {
    private val credential = OpenRouterCredentials.static("sk-or-files")
    private val json = listOf(SdkHeader("Content-Type", "application/json"))

    private fun client(transport: FakeTransport) = OpenRouter(credential, transport)

    private suspend fun consume(body: SdkRequestBody?): ByteArray = when (body) {
        null -> ByteArray(0)
        is SdkRequestBody.Bytes -> body.bytes
        is SdkRequestBody.OneShot -> readAll(body.stream)
        is SdkRequestBody.ReplayFactory -> consume(body.create())
    }

    private suspend fun readAll(stream: SdkByteStream): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        while (true) chunks += stream.readChunk() ?: break
        stream.close()
        return chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    }

    private fun contentType(request: SdkRequest): String = request.body?.contentType ?: ""

    private fun body(json: String) = FakeByteStream(listOf(json.encodeToByteArray()))

    private fun fileJson(id: String): String =
        """{"_shape":"openrouter","created_at":"2026-01-01","downloadable":true,"filename":"$id.pdf",""" +
            """"id":"$id","mime_type":"application/pdf","size_bytes":10,"type":"file"}"""

    private fun fileResponseBody() = body(fileJson("file_1"))

    // A real OpenRouter *terminal* file-list page: OpenRouter sends `cursor: null` when there is no next page. Under
    // kotlin-sdkgen 0.4.0 the FileListResponse union branch predicate accepts an explicit JSON `null` for the
    // nullable `cursor` property, so this (entirely normal) page decodes into the OpenRouter branch. Recorded in
    // docs/coverage/exception-register.md; a curated `listAllFiles` cursor walk is possible but not currently exposed.
    private fun openRouterTerminalPageWithNullCursor(): String =
        """{"_shape":"openrouter","cursor":null,"data":[${fileJson("f1")}],""" +
            """"first_id":"f1","has_more":false,"last_id":"f1"}"""

    @Test
    fun uploadSendsOneMultipartFilePartWithTheBytes() = runTest {
        val bytes = "hello pdf".encodeToByteArray()
        var wire = ""
        var ct = ""
        val transport = FakeTransport().enqueueExchange { req ->
            ct = contentType(req)
            wire = consume(req.body).decodeToString()
            SdkResponse(200, json, fileResponseBody())
        }
        client(transport).files.upload(bytes)
        assertTrue(ct.startsWith("multipart/form-data"), "content type was '$ct'")
        assertTrue(ct.contains("boundary="))
        assertTrue(wire.contains("name=\"file\""), "missing file part: $wire")
        assertTrue(wire.contains("hello pdf"))
    }

    @Test
    fun curatedUploadSerializesIdenticallyToExactCall() = runTest {
        val bytes = "payload".encodeToByteArray()
        val wires = mutableListOf<Pair<String, String>>()
        val transport = FakeTransport()
            .enqueueExchange { req ->
                wires += consume(req.body).decodeToString() to contentType(req)
                SdkResponse(200, json, fileResponseBody())
            }
            .enqueueExchange { req ->
                wires += consume(req.body).decodeToString() to contentType(req)
                SdkResponse(200, json, fileResponseBody())
            }
        val client = client(transport)
        client.files.upload(bytes)
        client.files.uploadFile(InlineFilesPostRequestMultipartX7e99eef0(file = byteStreamOf(bytes)))
        assertEquals(normalizeBoundary(wires[1]), normalizeBoundary(wires[0]))
    }

    private fun normalizeBoundary(wireAndType: Pair<String, String>): String {
        val boundary = wireAndType.second.substringAfter("boundary=", "").trim().ifEmpty { return wireAndType.first }
        return wireAndType.first.replace(boundary, "BOUNDARY")
    }

    @Test
    fun uploadStreamIsClosedAfterSend() = runTest {
        val file = FakeByteStream(listOf("data".encodeToByteArray()))
        val transport = FakeTransport().enqueueExchange { req ->
            consume(req.body)
            SdkResponse(200, json, fileResponseBody())
        }
        client(transport).files.upload(file)
        assertTrue(file.closed)
    }

    @Test
    fun downloadBytesPreservesBytesAndClosesStream() = runTest {
        val payload = FakeByteStream(listOf("chunk1".encodeToByteArray(), "chunk2".encodeToByteArray()))
        val transport = FakeTransport().enqueueResponse(
            200,
            listOf(SdkHeader("Content-Type", "application/octet-stream")),
            payload,
        )
        val bytes = client(transport).files.downloadBytes("file_1")
        assertEquals("chunk1chunk2", bytes.decodeToString())
        assertTrue(payload.closed)
    }

    @Test
    fun downloadNonSuccessIsTypedApiException() = runTest {
        val transport = FakeTransport().enqueueResponse(
            404,
            json,
            body("""{"error":{"code":404,"message":"no such file"}}"""),
        )
        val error = assertFailsWith<FilesClient.DownloadFileContentApiException> {
            client(transport).files.downloadBytes("missing")
        }
        assertEquals(404, error.statusCode)
    }

    @Test
    fun terminalFileListPageWithNullCursorDecodes() {
        // kotlin-sdkgen 0.4.0 fixed the FileListResponse union branch predicate to accept an explicit `cursor: null`
        // for the nullable branch field (docs/coverage/exception-register.md). The shape OpenRouter actually returns
        // for the last page — `cursor: null`, `has_more: false` — now decodes into the OpenRouter branch instead of
        // throwing FileListResponseNoMatchException, so a curated automatic file-list walk becomes possible.
        val decoded = SdkJson.decodeFromString(FileListResponseSerializer, openRouterTerminalPageWithNullCursor())
        val page = assertIs<FileListResponse.OpenRouterFileList>(decoded)
        assertNull(page.cursor)
        assertFalse(page.hasMore)
        assertEquals(1, page.`data`.size)
    }

    @Test
    fun transferObserverSeesUploadProgress() = runTest {
        val observer = RecordingTransferObserver()
        val transport = FakeTransport().enqueueExchange { req ->
            consume(req.body)
            SdkResponse(200, json, fileResponseBody())
        }
        val client = client(transport)
        client.files.upload("payload".encodeToByteArray(), options = client.options { transferObserver(observer) })
        assertTrue(observer.events.isNotEmpty(), "expected transfer callbacks")
        assertTrue(observer.events.any { it.event.direction == TransferDirection.UPLOAD })
    }
}
