package com.nabobery.openrouter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [body] against real time on every target. A real Ktor engine hops dispatchers, so it must not run under
 * `runTest`'s virtual clock (the executor's `withTimeout` deadlines would fire immediately). `runBlocking` is the
 * JVM/Native answer but does not exist on JS; `runTest { withContext(Dispatchers.Default) { … } }` is the
 * documented cross-platform equivalent — delays inside `Dispatchers.Default` are real. Returning the
 * [TestResult] is mandatory on JS (it is a Promise), so every converted test's body becomes `= runRealTime { … }`.
 *
 * [body] receives the real-time [CoroutineScope] (like `runBlocking`), so existing bodies that `launch` a writer
 * coroutine or call scope-bound helpers keep working unchanged.
 */
internal fun runRealTime(timeout: Duration = 30.seconds, body: suspend CoroutineScope.() -> Unit): TestResult =
    runTest(timeout = timeout) { withContext(Dispatchers.Default) { body() } }
