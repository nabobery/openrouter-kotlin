package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AttributionTest {
    @Test
    fun mapsPresentFieldsToPinnedHeaderNames() {
        val headers =
            Attribution(
                referer = "https://example.com",
                title = "Example App",
                categories = listOf("programming", "research"),
            ).toHeaders()

        val byName = headers.associate { it.name to it.value }
        assertEquals("https://example.com", byName["HTTP-Referer"])
        assertEquals("Example App", byName["X-OpenRouter-Title"])
        assertEquals("programming,research", byName["X-OpenRouter-Categories"])
        assertEquals(3, headers.size)
    }

    @Test
    fun omitsAbsentFields() {
        val headers = Attribution(title = "Only Title").toHeaders()
        assertEquals(listOf("X-OpenRouter-Title"), headers.map { it.name })
    }

    @Test
    fun emptyAttributionProducesNoHeaders() {
        assertTrue(Attribution().toHeaders().isEmpty())
    }

    @Test
    fun categoriesAreDefensivelyCopied() {
        val source = mutableListOf("a", "b")
        val attribution = Attribution(categories = source)
        source.add("c")
        assertEquals(listOf("a", "b"), attribution.categories)
    }

    @Test
    fun reservedHeadersAreRejectedCaseInsensitively() {
        for (name in listOf("authorization", "Host", "content-length", "Content-Type", "ACCEPT", "user-agent")) {
            assertFailsWith<SdkConfigurationException>("expected '$name' to be reserved") {
                requireNotReserved(name)
            }
        }
    }

    @Test
    fun nonReservedHeadersPass() {
        requireNotReserved("X-Correlation-ID")
        requireNotReserved("HTTP-Referer")
    }
}
