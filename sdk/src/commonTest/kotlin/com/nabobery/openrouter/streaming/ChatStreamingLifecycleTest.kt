package com.nabobery.openrouter.streaming

import com.nabobery.openrouter.Attribution
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.RequestDeadlines
import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.openrouter.chat.ChatStreamEvent
import com.nabobery.openrouter.chat.stream
import com.nabobery.sdkgen.runtime.SdkTimeoutException
import com.nabobery.sdkgen.runtime.SdkTransportException
import com.nabobery.sdkgen.runtime.TimeoutPhase
import com.nabobery.sdkgen.testing.ChunkGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The stream lifecycle contract matrix on the fake transport: incremental first event, cancellation ownership,
 * backpressure, the stream-idle deadline, and the spend-safety guarantee that nothing retries after an event has
 * been emitted. Fake-transport tests use `runTest` virtual time; the gated stream ([GatedSseStream]) controls
 * when each chunk is produced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStreamingLifecycleTest {
    @Test
    fun firstEventIsEmittedBeforeTheResponseCompletes() = runTest {
        val gate = ChunkGate()
        val server = SseFakeServer()
        val stream = server.sse(
            SseWireFixtures.chatChunk(content = "a"),
            SseWireFixtures.chatChunk(content = "b"),
            SseWireFixtures.DONE,
            gate = gate,
        )
        val client = openRouterOver(server)

        val received = Channel<ChatStreamEvent>(Channel.UNLIMITED)
        val job = launch { client.chat.stream(SseWireFixtures.userChatRequest()).collect { received.send(it) } }

        // The first event is available while the stream is still suspended awaiting the next chunk's release.
        assertIs<ChatStreamEvent.Chunk>(received.receive())
        assertFalse(stream.closed)
        gate.release(0)
        gate.release(1)
        gate.release(2)
        job.join()
    }

    @Test
    fun cancellationBeforeHeadersClosesTheExchange() = runTest {
        val server = SseFakeServer()
        server.transport.enqueueExchange { awaitCancellation() }
        val client = openRouterOver(server)

        val received = Channel<ChatStreamEvent>(Channel.UNLIMITED)
        val job = launch { client.chat.stream(SseWireFixtures.userChatRequest()).collect { received.send(it) } }
        runCurrent()
        job.cancelAndJoin()

        assertNull(received.tryReceive().getOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellationAfterFirstEventClosesBodyWithCancellationCause() = runTest {
        val gate = ChunkGate()
        val server = SseFakeServer()
        val stream = server.sse(
            SseWireFixtures.chatChunk(content = "a"),
            SseWireFixtures.chatChunk(content = "b"),
            SseWireFixtures.DONE,
            gate = gate,
        )
        val client = openRouterOver(server)

        val received = Channel<ChatStreamEvent>(Channel.UNLIMITED)
        val job = launch { client.chat.stream(SseWireFixtures.userChatRequest()).collect { received.send(it) } }
        received.receive()
        job.cancelAndJoin()

        assertTrue(stream.closed)
        assertIs<CancellationException>(stream.closeCause)
    }

    @Test
    fun takeOneClosesUpstream() = runTest {
        val server = SseFakeServer()
        val stream = server.sse(
            SseWireFixtures.chatChunk(content = "a"),
            SseWireFixtures.chatChunk(content = "b"),
            SseWireFixtures.DONE,
        )
        val client = openRouterOver(server)

        val first = client.chat.stream(SseWireFixtures.userChatRequest()).first()

        assertIs<ChatStreamEvent.Chunk>(first)
        assertTrue(stream.closed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun downstreamFailureClosesUpstreamWithSameCause() = runTest {
        val server = SseFakeServer()
        val stream = server.sse(
            SseWireFixtures.chatChunk(content = "a"),
            SseWireFixtures.chatChunk(content = "b"),
            SseWireFixtures.DONE,
        )
        val client = openRouterOver(server)
        val boom = IllegalStateException("boom")

        val thrown =
            assertFailsWith<IllegalStateException> {
                client.chat.stream(SseWireFixtures.userChatRequest()).collect { throw boom }
            }

        assertSame(boom, thrown)
        assertTrue(stream.closed)
        assertSame(boom, stream.closeCause)
    }

    @Test
    fun slowCollectorAppliesBackpressure() = runTest {
        val gate = ChunkGate()
        val server = SseFakeServer()
        server.sse(
            SseWireFixtures.chatChunk(content = "a"),
            SseWireFixtures.chatChunk(content = "b"),
            SseWireFixtures.DONE,
            gate = gate,
        )
        val client = openRouterOver(server)

        val received = Channel<ChatStreamEvent>(Channel.UNLIMITED)
        val job = launch { client.chat.stream(SseWireFixtures.userChatRequest()).collect { received.send(it) } }

        assertIs<ChatStreamEvent.Chunk>(received.receive()) // "a"
        advanceUntilIdle()
        assertNull(received.tryReceive().getOrNull(), "producer ran ahead of the released chunk")
        gate.release(0)
        assertIs<ChatStreamEvent.Chunk>(received.receive()) // "b"
        gate.release(1)
        gate.release(2)
        job.join()
    }

    @Test
    fun streamIdleDeadlineFailsWithStreamIdlePhase() = runTest {
        val gate = ChunkGate()
        val server = SseFakeServer()
        val stream = server.sse(
            SseWireFixtures.chatChunk(content = "a"),
            SseWireFixtures.chatChunk(content = "b"),
            SseWireFixtures.DONE,
            gate = gate,
        )
        val client = openRouterOver(server, deadlines = RequestDeadlines(streamIdle = 2.seconds))

        val ex =
            assertFailsWith<SdkTimeoutException> {
                // gate never released after chunk 0 -> the next read stalls past the idle deadline
                client.chat.stream(SseWireFixtures.userChatRequest(), options = client.options()).collect { }
            }

        assertEquals(TimeoutPhase.STREAM_IDLE, ex.phase)
        assertTrue(stream.closed)
    }

    // The client's streamIdle deadline reaches a streaming call through SdkClientConfig even with no
    // options(): the runtime folds client deadlines into a call that supplies none. This inverts the former
    // `noStreamIdleDeadlineWithoutOptions` pin, which held only while defaults lived in options().
    @Test
    fun streamIdleDeadlineAppliesWithoutOptions() = runTest {
        val gate = ChunkGate()
        val server = SseFakeServer()
        val stream = server.sse(
            SseWireFixtures.chatChunk(content = "a"),
            SseWireFixtures.chatChunk(content = "b"),
            SseWireFixtures.DONE,
            gate = gate,
        )
        val client = openRouterOver(server, deadlines = RequestDeadlines(streamIdle = 2.seconds))

        val ex =
            assertFailsWith<SdkTimeoutException> {
                // gate never released after chunk 0 -> the next read stalls past the client idle deadline
                client.chat.stream(SseWireFixtures.userChatRequest()).collect { }
            }

        assertEquals(TimeoutPhase.STREAM_IDLE, ex.phase)
        assertTrue(stream.closed)
    }

    @Test
    fun streamingIsNeverRetriedEvenBeforeTheFirstByte() = runTest {
        // DEVIATION from the plan's `retryBeforeFirstByteIsAllowed`: kotlin-sdkgen 0.3.0 disables retry
        // for STREAMING response mode entirely (SdkExecutor.kt: retry `.takeUnless { responseMode ==
        // STREAMING }`), so a pre-stream 429 surfaces immediately rather than being retried. This is
        // stricter than the buffered path (which retries an allowlisted 429) and is safe — an opened
        // stream cannot be transparently restarted.
        val server = SseFakeServer()
        server.status(429, "{\"error\":{\"code\":429,\"message\":\"rate limited\"}}")
        server.sse(SseWireFixtures.chatChunk(content = "a"), SseWireFixtures.DONE) // never consumed
        val client = openRouterOver(server)

        val ex =
            assertFailsWith<ChatClient.SendChatCompletionRequestApiException> {
                client.chat.stream(SseWireFixtures.userChatRequest(), options = client.options()).toList()
            }

        assertEquals(429, ex.statusCode)
        assertEquals(1, server.requestCount) // not retried
    }

    @Test
    fun noRetryAfterFirstEmittedEvent() = runTest {
        val server = SseFakeServer()
        server.sse(
            SseWireFixtures.chatChunk(content = "a"),
            failureAfterChunk = 1,
            failure = SdkTransportException("reset", requestMayHaveReachedServer = false),
        )
        server.sse(SseWireFixtures.chatChunk(content = "b"), SseWireFixtures.DONE) // pristine, must NOT be consumed
        val client = openRouterOver(server)

        val received = mutableListOf<ChatStreamEvent>()
        assertFailsWith<SdkTransportException> {
            client.chat.stream(SseWireFixtures.userChatRequest(), options = client.options()).collect {
                received +=
                    it
            }
        }

        assertEquals(1, received.size)
        assertEquals(1, server.requestCount) // no retry after emission (ADR 0004 spend-safety)
    }

    @Test
    fun twoCollectionsStartTwoRequests() = runTest {
        val server = SseFakeServer()
        server.sse(SseWireFixtures.chatChunk(content = "a"), SseWireFixtures.DONE)
        server.sse(SseWireFixtures.chatChunk(content = "a"), SseWireFixtures.DONE)
        val client = openRouterOver(server)

        val flow = client.chat.stream(SseWireFixtures.userChatRequest())
        flow.toList()
        flow.toList()

        assertEquals(2, server.requestCount)
    }

    @Test
    fun attributionAndOptionsHeadersReachStreamRequests() = runTest {
        val server = SseFakeServer()
        server.sse(SseWireFixtures.chatChunk(content = "a"), SseWireFixtures.DONE)
        val client =
            OpenRouter(
                credential = OpenRouterCredentials.static("sk-or-attr"),
                transport = server.transport,
                attribution = Attribution(referer = "https://example.com", title = "Example"),
            )

        client.chat.stream(
            SseWireFixtures.userChatRequest(),
            options = client.options {
                header("X-Correlation-ID", "c1")
            },
        ).toList()

        val headers = server.transport.capturedRequests.single().headers
        assertTrue(headers.any { it.name.equals("X-OpenRouter-Title", true) && it.value == "Example" })
        assertTrue(headers.any { it.name.equals("HTTP-Referer", true) && it.value == "https://example.com" })
        assertTrue(headers.any { it.name.equals("X-Correlation-ID", true) && it.value == "c1" })
    }

    @Test
    fun secretNeverAppearsInStreamFailures() = runTest {
        val secret = "sk-or-supersecret-9f2ec5"
        val server = SseFakeServer()
        server.sse(
            SseWireFixtures.chatChunk(content = "a"),
            failureAfterChunk = 1,
            failure = SdkTransportException("mid-stream reset"),
        )
        val client = OpenRouter(credential = OpenRouterCredentials.static(secret), transport = server.transport)

        val ex =
            assertFailsWith<SdkTransportException> {
                client.chat.stream(SseWireFixtures.userChatRequest()).collect { }
            }

        assertFalse(ex.toString().contains(secret))
        assertFalse((ex.message ?: "").contains(secret))
    }

    // A large (10,000-event) stream decodes to completion event-by-event: the decoder pulls one event at a time and
    // terminates cleanly at scale, never getting stuck or dropping events. This pins "streaming decodes incrementally
    // to completion" on EVERY lane — JVM, Native, iOS, and JS — since it lives in commonTest. (It does not by itself
    // prove a bounded heap: the fixture pre-builds all chunks. The bounded-diagnostics guarantee is pinned by
    // ChatStreamingFramingTest.malformedEventAfterValidEventsFailsWithBoundedDiagnostics.)
    @Test
    fun largeEventStreamDecodesIncrementallyToCompletion() = runTest {
        val server = SseFakeServer()
        val chunks = Array(10_000) { SseWireFixtures.chatChunk(content = "t$it ") } + SseWireFixtures.DONE
        server.sse(*chunks)
        val client = openRouterOver(server)

        val decoded = client.chat.stream(SseWireFixtures.userChatRequest()).count()

        assertEquals(10_000, decoded)
    }
}
