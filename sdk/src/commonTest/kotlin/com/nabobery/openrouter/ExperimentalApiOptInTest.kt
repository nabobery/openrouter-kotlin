@file:OptIn(OpenRouterExperimentalApi::class)

package com.nabobery.openrouter

import com.nabobery.openrouter.io.byteStreamOf
import com.nabobery.openrouter.io.readAllBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pre-1.0 curated helpers are gated behind [OpenRouterExperimentalApi]. This file opting in with
 * `@file:OptIn(OpenRouterExperimentalApi::class)` and compiling is itself the proof that the opt-in marker is in
 * place and usable; the marker is `WARNING`-level, so it guides rather than blocks. (There is no `client.beta`
 * namespace: the 2026-08-29 contract GA'd both Responses and Analytics, so the generator emits no beta resources.)
 */
class ExperimentalApiOptInTest {
    @Test
    fun experimentalHelpersAreUsableUnderOptIn() = runTest {
        val bytes = byteStreamOf("hello".encodeToByteArray()).readAllBytes()
        assertEquals("hello", bytes.decodeToString())
    }
}
