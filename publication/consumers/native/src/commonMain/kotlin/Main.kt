import consumers.smoke
import kotlinx.coroutines.runBlocking

// Root-package `main`: Kotlin/Native's default executable entry point is `/main` (the root package). The shared
// smoke lives in package `consumers` (imported above).
fun main() = runBlocking {
    println(smoke())
}
