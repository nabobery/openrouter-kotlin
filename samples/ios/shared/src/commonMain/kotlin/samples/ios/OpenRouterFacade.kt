package samples.ios

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * A tiny, Swift-friendly facade over `:sdk` for the iOS Swift consumer. It owns the Darwin [HttpClient] (the
 * expensive resource) and builds a cheap [OpenRouter] per call from the caller's key. Swift sees `suspend` as
 * `async`; the `Flow<String>` of deltas is exposed through [collectDeltas], a callback helper, so no SKIE / Swift
 * flow-export experiment is needed. Call [close] to cancel any in-flight collection and release the HTTP client.
 */
class OpenRouterFacade {
    private val http: HttpClient = HttpClient(Darwin)
    // The supervisor job is retained so [close] can cancel every in-flight collection before the client is shut
    // down; a child failure (SupervisorJob) does not tear the scope down or cancel its siblings.
    private val job: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default)

    private fun client(apiKey: String): OpenRouter =
        OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = http)

    /** A one-shot, non-streaming completion. Swift consumes this as `async`. */
    suspend fun hello(apiKey: String, model: String = "openrouter/free"): String {
        val result =
            client(apiKey).chat.send(
                model = model,
                messages = listOf(userMessage("Say hello in one sentence.")),
            ) { maxTokens = 32 }
        return result.choices.firstOrNull()?.message?.content?.raw?.toString() ?: ""
    }

    /** The raw delta [Flow]; usable from Kotlin. Swift consumes it via [collectDeltas] instead. */
    fun stream(apiKey: String, prompt: String, model: String = "openrouter/free"): Flow<String> =
        client(apiKey).chat.stream(model = model, messages = listOf(userMessage(prompt))).contentDeltas()

    /**
     * Swift-friendly stream consumer: invokes [onDelta] for each text delta and [onComplete] once with `null` on
     * success or the error message on failure. Returns a [Cancellable] the caller can use to stop early.
     */
    fun collectDeltas(
        apiKey: String,
        prompt: String,
        onDelta: (String) -> Unit,
        onComplete: (String?) -> Unit,
    ): Cancellable {
        val collectionJob: Job =
            scope.launch {
                try {
                    stream(apiKey, prompt).collect { onDelta(it) }
                    onComplete(null)
                } catch (ce: CancellationException) {
                    // Cancellation is control flow, not a stream error: never surface it through [onComplete], and
                    // rethrow so the coroutine settles as cancelled.
                    throw ce
                } catch (t: Throwable) {
                    onComplete(t.message ?: "stream failed")
                }
            }
        return Cancellable(collectionJob)
    }

    /**
     * Cancels any in-flight collection, then releases the Darwin HTTP client. The consumer's `HttpClient` and the
     * facade's coroutine scope are both owned here, so the sample tears them down together — order matters: cancel
     * the collectors first so they stop touching the client before it closes.
     */
    fun close() {
        job.cancel()
        http.close()
    }

    /** A cancellation handle over a running stream collection. */
    class Cancellable internal constructor(private val job: Job) {
        fun cancel() = job.cancel()
    }
}
