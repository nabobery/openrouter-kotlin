package samples

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.winhttp.WinHttp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

// Windows (mingwX64) uses the WinHttp engine — the OS-native HTTP stack, no extra native dependency.
actual fun httpEngine(): HttpClientEngineFactory<*> = WinHttp

@OptIn(ExperimentalForeignApi::class)
actual fun readEnv(name: String): String? = getenv(name)?.toKString()
