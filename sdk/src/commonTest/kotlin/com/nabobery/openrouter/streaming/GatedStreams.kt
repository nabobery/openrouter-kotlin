package com.nabobery.openrouter.streaming

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.RequestDeadlines
import com.nabobery.openrouter.RetryPolicy
import com.nabobery.sdkgen.runtime.SdkByteStream
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkResponse
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.testing.ChunkGate
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport

internal val sseHeaders: List<SdkHeader> = listOf(SdkHeader("Content-Type", "text/event-stream"))
internal val jsonHeaders: List<SdkHeader> = listOf(SdkHeader("Content-Type", "application/json"))

/**
 * A test-controlled SSE response body. Chunks are delivered in order; with a [ChunkGate] the stream suspends
 * after each chunk until the test releases it (incremental delivery / backpressure); [failureAfterChunk] injects
 * a mid-stream failure; the close cause is recorded by identity. Mirrors the runtime testing kit's scripted
 * response stream, but is driven through a plain [FakeTransport] so request verification never gets in the way.
 */
internal class GatedSseStream(
    chunks: List<ByteArray>,
    private val gate: ChunkGate?,
    private val failureAfterChunk: Int?,
    private val failure: Throwable?,
) : SdkByteStream {
    private val chunks: MutableList<ByteArray> = chunks.map(ByteArray::copyOf).toMutableList()
    private var completedChunks: Int = 0
    private var currentOffset: Int = 0

    var closed: Boolean = false
        private set

    var closeCause: Throwable? = null
        private set

    init {
        if (chunks.isNotEmpty()) gate?.markProduced(0)
    }

    override suspend fun readChunk(maxBytes: Int): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (currentOffset == 0 && completedChunks > 0) {
            gate?.awaitRelease(completedChunks - 1)
            if (chunks.isNotEmpty()) gate?.markProduced(completedChunks)
        }
        if (failureAfterChunk == completedChunks && currentOffset == 0) throw requireNotNull(failure)
        val chunk = chunks.firstOrNull() ?: return null
        val end = minOf(currentOffset + maxBytes, chunk.size)
        val result = chunk.copyOfRange(currentOffset, end)
        currentOffset = end
        if (currentOffset == chunk.size) {
            chunks.removeAt(0)
            gate?.markProduced(completedChunks)
            completedChunks += 1
            currentOffset = 0
        }
        return result
    }

    override fun close(cause: Throwable?) {
        if (!closed) {
            closed = true
            closeCause = cause
        }
    }
}

/** A plain [FakeTransport] with helpers for scripting SSE and pre-stream status exchanges. */
internal class SseFakeServer(capabilities: TransportCapabilities = TransportCapabilities(supportsStreaming = true)) {
    val transport: FakeTransport = FakeTransport(capabilities)

    /** Scripts one 200 SSE exchange, returning the response stream so the test can assert its close cause. */
    fun sse(
        vararg chunks: String,
        gate: ChunkGate? = null,
        failureAfterChunk: Int? = null,
        failure: Throwable? = null,
    ): GatedSseStream {
        val stream = GatedSseStream(chunks.map { it.encodeToByteArray() }, gate, failureAfterChunk, failure)
        transport.enqueueExchange { SdkResponse(200, sseHeaders, stream) }
        return stream
    }

    /** Scripts one pre-stream JSON status exchange (e.g. a retryable 429). */
    fun status(status: Int, bodyJson: String) {
        transport.enqueueExchange {
            SdkResponse(status, jsonHeaders, FakeByteStream(listOf(bodyJson.encodeToByteArray())))
        }
    }

    /** The number of physical requests the transport received. */
    val requestCount: Int get() = transport.capturedRequests.size
}

internal fun openRouterOver(
    server: SseFakeServer,
    retry: RetryPolicy = RetryPolicy.Default,
    deadlines: RequestDeadlines? = null,
): OpenRouter = OpenRouter(
    credential = OpenRouterCredentials.static("sk-or-gated"),
    transport = server.transport,
    retryPolicy = retry,
    deadlines = deadlines,
)
