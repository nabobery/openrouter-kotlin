package com.nabobery.openrouter

import com.nabobery.openrouter.RequestDeadlines
import com.nabobery.openrouter.chat.ChatStreamEvent
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.streaming.SseWireFixtures
import com.nabobery.sdkgen.runtime.SdkTimeoutException
import com.nabobery.sdkgen.runtime.TimeoutPhase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByteArray
import io.ktor.utils.io.writer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The real-engine SSE lane: the curated `client.chat.stream(...)` driven through a real Ktor pipeline
 * (`MockEngine`) whose response body is a concurrently written `ByteChannel`, proving incremental delivery,
 * cancellation ownership, in-band errors, and the stream-idle deadline on an actual engine. Shared by the JVM
 * and macosArm64 test tasks via the `engineTest` source set; runs under `runRealTime` (real time). Timeouts are
 * bounded (≤ 10 s) with generous per-call operation deadlines so a scheduling hiccup under full-suite CPU load
 * cannot trip a spurious transport timeout, while a genuine hang still fails.
 */
class KtorStreamingEngineTest {
    private val credential = OpenRouterCredentials.static("sk-or-engine")

    private class SseLane(val client: OpenRouter, val http: HttpClient, val bodyClosed: CompletableDeferred<Unit>)

    private fun sseLane(body: suspend (ByteWriteChannel) -> Unit): SseLane {
        val bodyClosed = CompletableDeferred<Unit>()
        val http =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        // `writer { }` produces a lazily-streamed ByteReadChannel: the reader pulls, the writer
                        // produces on demand, so events reach the client incrementally (a plain ByteChannel is
                        // buffered whole by MockEngine before the response is delivered). The writer's channel is
                        // closed when the block completes or the reader cancels.
                        // Unconfined: the writer produces eagerly inline as the reader pulls, so delivery never
                        // waits on a shared Dispatchers.Default thread (which can be starved under full-suite load).
                        val response =
                            CoroutineScope(Dispatchers.Unconfined).writer(autoFlush = true) {
                                try {
                                    body(channel)
                                } finally {
                                    bodyClosed.complete(Unit)
                                }
                            }
                        respond(
                            content = response.channel,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    }
                }
            }
        return SseLane(OpenRouter(credential = credential, httpClient = http), http, bodyClosed)
    }

    private suspend fun ByteWriteChannel.send(fragment: String) {
        writeByteArray(fragment.encodeToByteArray())
        flush()
    }

    // Replace the operation's tight baked-in attempt deadline with generous headroom so a rare scheduling hiccup
    // under full-suite CPU load cannot trip a spurious transport timeout on these real-time tests. (Per-call
    // deadlines replace the metadata default — SdkExecutor.resolveDeadlines.)
    private fun OpenRouter.generous() = options {
        deadlines(RequestDeadlines(total = 30.seconds, attempt = 30.seconds))
    }

    @Test
    fun multipleEventsAndDoneDecodeThroughKtor() = runRealTime {
        // MockEngine delivers a streaming response body once its producer completes (it does not expose a
        // half-open duplex body), so this proves multi-event SSE decoding end-to-end through a real Ktor
        // pipeline. Incremental "first event before the body completes" and mid-stream cancellation are proven
        // on the fake transport (ChatStreamingLifecycleTest) and by the upstream transport-ktor conformance
        // suite (SdkTransportContractKit), which the Ktor adapter passes.
        val lane =
            sseLane { channel ->
                channel.send(SseWireFixtures.chatChunk(content = "Hel"))
                channel.send(SseWireFixtures.chatChunk(content = "lo", finishReason = "stop"))
                channel.send(SseWireFixtures.DONE)
            }
        try {
            val events =
                withTimeout(10_000) {
                    lane.client.chat.stream(
                        SseWireFixtures.userChatRequest(),
                        options = lane.client.generous(),
                    ).toList()
                }
            assertEquals(
                listOf("Hel", "lo"),
                events.filterIsInstance<ChatStreamEvent.Chunk>().mapNotNull {
                    it.chunk.choices.single().delta.content
                },
            )
            withTimeout(10_000) { lane.bodyClosed.await() }
        } finally {
            lane.http.close()
        }
    }

    @Test
    fun cancellationClosesTheEngineResponse() = runRealTime {
        val lane =
            sseLane { channel ->
                channel.send(SseWireFixtures.chatChunk(content = "Hel"))
                while (!channel.isClosedForWrite) delay(25) // hold the body open until the reader cancels
            }
        try {
            val job =
                launch(Dispatchers.Default) {
                    lane.client.chat.stream(
                        SseWireFixtures.userChatRequest(),
                        options = lane.client.generous(),
                    ).collect {
                    }
                }
            delay(200) // let the request reach the engine
            job.cancelAndJoin()
            // Cancelling the collection cancels the engine call, which closes the response body; the writer
            // observes the cancelled channel and completes.
            withTimeout(10_000) { lane.bodyClosed.await() }
        } finally {
            lane.http.close()
        }
    }

    @Test
    fun midStreamErrorChunkIsAValueThroughKtor() = runRealTime {
        val lane =
            sseLane { channel ->
                channel.send(SseWireFixtures.chatChunk(content = "Hel"))
                channel.send(
                    SseWireFixtures.chatChunk(
                        content = "",
                        finishReason = "error",
                        error = SseWireFixtures.MID_STREAM_ERROR_JSON,
                    ),
                )
            }
        try {
            val events =
                withTimeout(10_000) {
                    lane.client.chat.stream(
                        SseWireFixtures.userChatRequest(),
                        options = lane.client.generous(),
                    ).toList()
                }
            assertIs<ChatStreamEvent.Chunk>(events[0])
            assertIs<ChatStreamEvent.Error>(events[1])
            Unit
        } finally {
            lane.http.close()
        }
    }

    @Test
    fun commentsAndDoneThroughKtor() = runRealTime {
        val lane =
            sseLane { channel ->
                channel.send(SseWireFixtures.COMMENT)
                channel.send(SseWireFixtures.chatChunk(content = "Hi"))
                channel.send(SseWireFixtures.DONE)
            }
        try {
            val events =
                withTimeout(10_000) {
                    lane.client.chat.stream(
                        SseWireFixtures.userChatRequest(),
                        options = lane.client.generous(),
                    ).toList()
                }
            assertEquals(1, events.size)
        } finally {
            lane.http.close()
        }
    }

    @Test
    fun streamIdleDeadlineFiresThroughKtor() = runRealTime {
        val lane =
            sseLane { channel ->
                channel.send(SseWireFixtures.chatChunk(content = "Hel"))
                while (!channel.isClosedForWrite) delay(25) // stall — no further events
            }
        try {
            val ex =
                assertFailsWith<SdkTimeoutException> {
                    withTimeout(5000) {
                        lane.client.chat
                            .stream(
                                SseWireFixtures.userChatRequest(),
                                options = lane.client.options {
                                    deadlines(RequestDeadlines(streamIdle = 500.milliseconds))
                                },
                            ).collect { }
                    }
                }
            assertEquals(TimeoutPhase.STREAM_IDLE, ex.phase)
        } finally {
            lane.http.close()
        }
    }
}
