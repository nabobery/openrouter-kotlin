package samples

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

// Linux (linuxX64 + linuxArm64) uses the CIO engine — pure-Kotlin, no native dependency. Curl is the alternative
// (`libs.ktor.client.curl`); it needs libcurl dev headers, so it is documented in samples/README.md, not defaulted.
actual fun httpEngine(): HttpClientEngineFactory<*> = CIO

@OptIn(ExperimentalForeignApi::class)
actual fun readEnv(name: String): String? = getenv(name)?.toKString()
