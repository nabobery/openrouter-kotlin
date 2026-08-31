# Streaming with Flow

**Outcome:** you will stream a chat completion and print the assistant text as it arrives.

Assumes you have completed [your first chat request](first-chat-request.md).

## 1. Open a cold stream

`chat.stream(model, messages)` returns a **cold** `Flow<ChatStreamEvent>` — nothing is sent until you collect it,
and each collection starts a fresh request:

<!-- snippet: samples/docs/src/main/kotlin/guides/StreamingWithFlow.kt#stream -->
```kotlin
val events = chat.stream(
    model = "openrouter/free",
    messages = listOf(userMessage("Stream a haiku about Kotlin.")),
)
```
<!-- /snippet -->

## 2. Project and collect the text deltas

`contentDeltas()` projects just the assistant text fragments. Cancelling the collector stops consumption
immediately (structured concurrency), and the SDK never records a full prompt or response by default:

<!-- snippet: samples/docs/src/main/kotlin/guides/StreamingWithFlow.kt#deltas -->
```kotlin
// `contentDeltas()` projects just the assistant text deltas; collection sends the request and cancellation
// stops consumption immediately. The stream is cold: each `collect` starts a fresh request.
events.contentDeltas().collect { delta -> print(delta) }
```
<!-- /snippet -->

## Notes

- Streaming requests are **never** retried (stricter than the buffered path) — a partially consumed stream could
  otherwise double-bill.
- Bound a stalled stream with `RequestDeadlines(streamIdle = …)` (see
  [retries and deadlines](../how-to/configure-retries-and-deadlines.md)).
