# Android lifecycle

On Android, collect streams on a lifecycle-aware scope so they are cancelled automatically, and hold the injected
Ktor `HttpClient` as a field you close in `onDestroy()`. Use the OkHttp engine.

## Construct once, hold the client

<!-- snippet: samples/android/src/androidMain/kotlin/samples/android/MainActivity.kt#client -->
```kotlin
val http = HttpClient(OkHttp).also { this.http = it }
val client = OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = http)
```
<!-- /snippet -->

## Collect on `lifecycleScope`

<!-- snippet: samples/android/src/androidMain/kotlin/samples/android/MainActivity.kt#lifecycle-stream -->
```kotlin
lifecycleScope.launch {
    client.chat
        .stream(model = "openrouter/free", messages = listOf(userMessage("Say hello in one sentence.")))
        .contentDeltas()
        .collect { delta -> textView.append(delta) }
}
```
<!-- /snippet -->

Because the stream is collected on `lifecycleScope`, it is cancelled when the Activity is destroyed; the SDK stops
consuming immediately. A real app would scope a single `HttpClient` to the Application (or a DI graph) rather than
to one Activity.

> The Android sample consumes the SDK's **android** variant over OkHttp and compiles in CI. See
> [`samples/android`](../../../samples/android/) for the full module.
