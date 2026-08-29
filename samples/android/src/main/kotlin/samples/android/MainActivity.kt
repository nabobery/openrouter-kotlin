package samples.android

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.launch

/**
 * Lifecycle-safe streaming: the chat stream is collected on [lifecycleScope], so it is cancelled automatically
 * when the Activity is destroyed. The injected Ktor [HttpClient] owns a connection pool and coroutine scope, so
 * it is held as a field and closed in [onDestroy] (it survives configuration changes only within this Activity
 * instance; a real app would scope one client to the Application). Consumes the JVM variant of `:sdk` over OkHttp.
 */
class MainActivity : ComponentActivity() {
    private var http: HttpClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView =
            TextView(this).apply {
                textSize = 16f
                setPadding(48, 48, 48, 48)
            }
        setContentView(textView)

        // For local runs, export OPENROUTER_API_KEY, or paste it here (never commit a real key).
        val apiKey = System.getenv("OPENROUTER_API_KEY").orEmpty() // paste for local runs; never commit
        if (apiKey.isBlank()) {
            textView.text = "Set OPENROUTER_API_KEY to run the sample."
            return
        }

        val http = HttpClient(OkHttp).also { this.http = it }
        val client = OpenRouter(credential = OpenRouterCredentials.static(apiKey), httpClient = http)

        lifecycleScope.launch {
            client.chat
                .stream(model = "openrouter/free", messages = listOf(userMessage("Say hello in one sentence.")))
                .contentDeltas()
                .collect { delta -> textView.append(delta) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        http?.close()
        http = null
    }
}
