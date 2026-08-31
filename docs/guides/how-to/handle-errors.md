# Handle errors

## Typed errors and timeouts

Every non-success response declared by an operation surfaces as a **typed** exception carrying the status code; a
tripped deadline surfaces as `SdkTimeoutException`, whose `phase` names the deadline that fired:

<!-- snippet: samples/docs/src/main/kotlin/guides/HandleErrors.kt#typed-error -->
```kotlin
// Non-success responses declared by the operation surface as a typed exception carrying the status code.
try {
    chat.send(model = "openrouter/free", messages = messages)
} catch (e: ChatClient.SendChatCompletionRequestApiException) {
    System.err.println("chat failed with HTTP ${e.statusCode}")
} catch (e: SdkTimeoutException) {
    // `phase` tells you which deadline fired (ATTEMPT, TOTAL, STREAM_IDLE, PAGINATION_BUDGET).
    System.err.println("timed out in phase ${e.phase}")
}
```
<!-- /snippet -->

The API key never appears in an exception's message, `toString()`, or the request diagnostics reachable from it.

## Response metadata

When you need the status, headers, or request id, use the `*WithResponse` variants — they return the full
`SdkResponseResult` union without giving up typed decoding:

<!-- snippet: samples/docs/src/main/kotlin/guides/HandleErrors.kt#with-response -->
```kotlin
// `sendWithResponse` exposes status, headers, and the request id without giving up typed decoding.
when (val result = chat.sendWithResponse(buildChatRequest(messages))) {
    is SdkResponseResult.Matched -> println("ok ${result.statusCode}, request ${result.requestId}")
    else -> println("unmatched response alternative")
}
```
<!-- /snippet -->

An undeclared status surfaces as `UnknownApiException`, whose body preview is byte-bounded so a hostile server can
leak at most a bounded prefix of whatever it echoed.
