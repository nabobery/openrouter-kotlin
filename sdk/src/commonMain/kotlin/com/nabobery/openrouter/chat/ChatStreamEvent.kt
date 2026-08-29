package com.nabobery.openrouter.chat

import com.nabobery.openrouter.ChatStreamChunk
import com.nabobery.openrouter.InlineChatStreamChunkErrorXd280afd4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * One curated chat stream event.
 *
 * A [Chunk] carries a wire chunk unchanged. An [Error] carries a mid-stream failure: OpenRouter has already
 * sent HTTP 200, so it reports the failure in-band as a chunk whose `error` object is populated (typically
 * with `finish_reason: "error"`); the stream then completes normally. Flow completion is the terminal signal —
 * the `[DONE]` sentinel is consumed by the runtime and never emitted, and there is deliberately no `Done` event
 * (it would also fire on a plain EOF and misreport the sentinel).
 */
public sealed interface ChatStreamEvent {
    /** The underlying wire chunk this event wraps. */
    public val chunk: ChatStreamChunk

    /** An ordinary streamed chunk. */
    public class Chunk(override val chunk: ChatStreamChunk) : ChatStreamEvent

    /** A mid-stream error delivered in-band as a value; the stream completes after it. */
    public class Error(
        override val chunk: ChatStreamChunk,
        /** The populated in-band error object from [chunk]. */
        public val error: InlineChatStreamChunkErrorXd280afd4,
    ) : ChatStreamEvent
}

/**
 * Classifies a wire chunk. A chunk is an [ChatStreamEvent.Error] exactly when it carries an in-band `error`
 * object; a `finish_reason: "error"` without an accompanying `error` object stays a [ChatStreamEvent.Chunk]
 * (callers can still read the finish reason).
 */
internal fun ChatStreamChunk.toEvent(): ChatStreamEvent {
    val inBand = error
    return if (inBand != null) ChatStreamEvent.Error(this, inBand) else ChatStreamEvent.Chunk(this)
}

/** Text deltas only (`choices[*].delta.content`), in arrival order; error events are dropped, not thrown. */
public fun Flow<ChatStreamEvent>.contentDeltas(): Flow<String> = transform { event ->
    if (event is ChatStreamEvent.Chunk) {
        event.chunk.choices.forEach { choice -> choice.delta.content?.let { emit(it) } }
    }
}
