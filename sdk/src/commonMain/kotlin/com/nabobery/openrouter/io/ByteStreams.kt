package com.nabobery.openrouter.io

import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.sdkgen.runtime.SdkByteStream
import com.nabobery.sdkgen.runtime.bodies.binaryDownload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Default in-memory download bound for [readAllBytes]: 64 MiB. */
@OpenRouterExperimentalApi
public const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = 64L * 1024 * 1024

/** An in-memory [SdkByteStream] over [bytes] (defensively copied). One-shot, like every [SdkByteStream]. */
@OpenRouterExperimentalApi
public fun byteStreamOf(bytes: ByteArray): SdkByteStream = ByteArrayByteStream(bytes.copyOf())

/**
 * Reads the whole stream (at most [maxBytes]) into memory and closes it — for small binary results such as TTS
 * audio or a downloaded file. Exceeding [maxBytes] throws `SdkBufferLimitExceededException` (from the runtime's
 * [binaryDownload]); the stream is closed with whatever cause ended the read.
 */
@OpenRouterExperimentalApi
public suspend fun SdkByteStream.readAllBytes(maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES): ByteArray {
    var cause: Throwable? = null
    try {
        return binaryDownload(this, maxBytes)
    } catch (t: Throwable) {
        cause = t
        throw t
    } finally {
        close(cause)
    }
}

/**
 * Cold chunk flow over a one-shot stream: collecting reads until EOF; completion, downstream failure, and
 * cancellation all close the stream with the corresponding cause. A stream can be collected once — a second
 * collection reads nothing from an already-consumed stream (and a closed [byteStreamOf] stream throws).
 */
@OpenRouterExperimentalApi
public fun SdkByteStream.asFlow(chunkSize: Int = SdkByteStream.DEFAULT_READ_SIZE): Flow<ByteArray> = flow {
    var cause: Throwable? = null
    try {
        while (true) emit(readChunk(chunkSize) ?: break)
    } catch (t: Throwable) {
        cause = t
        throw t
    } finally {
        close(cause)
    }
}

private class ByteArrayByteStream(private val bytes: ByteArray) : SdkByteStream {
    private var position = 0
    private var closed = false

    override suspend fun readChunk(maxBytes: Int): ByteArray? {
        check(!closed) { "stream is closed" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (position >= bytes.size) return null
        // Compute the length first (never `position + maxBytes`): with maxBytes near Int.MAX_VALUE the sum would
        // overflow to a negative index. `bytes.size - position` is always a safe non-negative Int here.
        val length = minOf(maxBytes, bytes.size - position)
        val end = position + length
        return bytes.copyOfRange(position, end).also { position = end }
    }

    override fun close(cause: Throwable?) {
        closed = true
    }
}
