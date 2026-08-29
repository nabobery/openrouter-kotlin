@file:OptIn(OpenRouterExperimentalApi::class)

package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkConfigurationException
import com.nabobery.sdkgen.testing.FakeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PaginationLimitsTest {
    private val cred = OpenRouterCredentials.static("sk-or-page")

    @Test
    fun clientDefaultBoundsReachCallOptions() {
        val client = OpenRouter {
            credential = cred
            transport = FakeTransport()
            paginationLimits = PaginationLimits(maxPages = 3)
        }
        assertEquals(3, client.options().pagination?.maxPages)
    }

    @Test
    fun perCallBoundsReplaceClientDefault() {
        val client = OpenRouter {
            credential = cred
            transport = FakeTransport()
            paginationLimits = PaginationLimits(maxPages = 3)
        }
        val options = client.options { pagination(PaginationLimits(maxItems = 10)) }
        assertEquals(10, options.pagination?.maxItems)
        assertNull(options.pagination?.maxPages)
    }

    @Test
    fun noBoundsByDefault() {
        assertNull(OpenRouter(cred, FakeTransport()).options().pagination)
    }

    @Test
    fun invalidBoundsFailEagerly() {
        assertFailsWith<SdkConfigurationException> { PaginationLimits(maxPages = 0) }
        assertFailsWith<SdkConfigurationException> { PaginationLimits(maxItems = -1) }
        assertFailsWith<SdkConfigurationException> { PaginationLimits(maxElapsed = 0.milliseconds) }
    }

    @Test
    fun maxElapsedConvertsToMillis() {
        assertEquals(1500, PaginationLimits(maxElapsed = 1.5.seconds).toBounds().maxElapsedMillis)
    }
}
