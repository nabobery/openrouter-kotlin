@file:OptIn(OpenRouterExperimentalApi::class)

package com.nabobery.openrouter.media

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.SpeechRequest
import com.nabobery.openrouter.io.readAllBytes
import com.nabobery.openrouter.stt.transcribe
import com.nabobery.openrouter.tts.TtsClient
import com.nabobery.sdkgen.runtime.SdkByteStream
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkRequest
import com.nabobery.sdkgen.runtime.SdkRequestBody
import com.nabobery.sdkgen.runtime.SdkResponse
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MediaContractTest {
    private val credential = OpenRouterCredentials.static("sk-or-media")
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

    private fun body(s: String) = FakeByteStream(listOf(s.encodeToByteArray()))

    @Test
    fun transcribeMultipartSendsFileAndModelParts() = runTest {
        var wire = ""
        var ct = ""
        val transport = FakeTransport().enqueueExchange { req ->
            ct = req.body?.contentType ?: ""
            wire = consume(req.body).decodeToString()
            SdkResponse(200, json, body("""{"text":"hello world"}"""))
        }
        val result = client(transport).stt.transcribe("audio-bytes".encodeToByteArray(), model = "whisper-1")
        assertEquals("hello world", result.text)
        assertTrue(ct.startsWith("multipart/form-data"), "content type was '$ct'")
        assertTrue(wire.contains("name=\"file\""), wire)
        assertTrue(wire.contains("name=\"model\""), wire)
        assertTrue(wire.contains("whisper-1"))
    }

    @Test
    fun transcribeConfigureBlockSetsExtraFields() = runTest {
        var wire = ""
        val transport = FakeTransport().enqueueExchange { req ->
            wire = consume(req.body).decodeToString()
            SdkResponse(200, json, body("""{"text":"hi"}"""))
        }
        client(transport).stt.transcribe("a".encodeToByteArray(), model = "whisper-1") { language = "en" }
        assertTrue(wire.contains("name=\"language\""), wire)
        assertTrue(wire.contains("en"))
    }

    @Test
    fun speechReturnsBinaryStreamThatReadsBack() = runTest {
        val audio = FakeByteStream(listOf("MP3".encodeToByteArray(), "DATA".encodeToByteArray()))
        val transport = FakeTransport().enqueueResponse(200, listOf(SdkHeader("Content-Type", "audio/mpeg")), audio)
        val stream = client(transport).tts.createAudioSpeech(
            SpeechRequest.build {
                input = "hello"
                model = "tts-1"
            },
        )
        assertEquals("MP3DATA", stream.readAllBytes().decodeToString())
        assertTrue(audio.closed)
    }

    @Test
    fun speechNonSuccessIsTyped() = runTest {
        val transport = FakeTransport().enqueueResponse(
            402,
            json,
            body("""{"error":{"code":402,"message":"insufficient credits"}}"""),
        )
        val error = assertFailsWith<TtsClient.CreateAudioSpeechApiException> {
            client(transport).tts.createAudioSpeech(
                SpeechRequest.build {
                    input = "hi"
                    model = "tts-1"
                },
            )
        }
        assertEquals(402, error.statusCode)
    }
}
