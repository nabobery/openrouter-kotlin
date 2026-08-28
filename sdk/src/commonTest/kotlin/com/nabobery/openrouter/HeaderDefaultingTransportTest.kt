package com.nabobery.openrouter

import com.nabobery.openrouter.internal.HeaderDefaultingTransport
import com.nabobery.sdkgen.runtime.SdkDeadlines
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkRequest
import com.nabobery.sdkgen.runtime.SdkResponseMode
import com.nabobery.sdkgen.runtime.TransportCapabilities
import com.nabobery.sdkgen.testing.FakeTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeaderDefaultingTransportTest {
    private fun request(headers: List<SdkHeader>): SdkRequest = SdkRequest(
        method = "GET",
        uri = "https://openrouter.test/v1/models",
        headers = headers,
        body = null,
        expectedResponseMode = SdkResponseMode.BUFFERED,
        deadlines = SdkDeadlines(null, null, null),
        operationId = "op",
    )

    @Test
    fun addsDefaultHeaderWhenAbsent() = runTest {
        val fake = FakeTransport().enqueueResponse(200)
        val decorator =
            HeaderDefaultingTransport(fake, listOf(SdkHeader("HTTP-Referer", "https://default.example")))

        decorator.execute(request(emptyList()))

        val captured = fake.capturedRequests.single()
        assertEquals("https://default.example", captured.headers.single { it.name == "HTTP-Referer" }.value)
    }

    @Test
    fun doesNotOverrideExistingHeaderCaseInsensitively() = runTest {
        val fake = FakeTransport().enqueueResponse(200)
        val decorator =
            HeaderDefaultingTransport(fake, listOf(SdkHeader("HTTP-Referer", "https://default.example")))

        decorator.execute(request(listOf(SdkHeader("http-referer", "https://explicit.example"))))

        val referers = fake.capturedRequests.single().headers.filter {
            it.name.equals("http-referer", ignoreCase = true)
        }
        assertEquals(1, referers.size)
        assertEquals("https://explicit.example", referers.single().value)
    }

    @Test
    fun leavesRequestUntouchedWhenNoDefaults() = runTest {
        val fake = FakeTransport().enqueueResponse(200)
        val decorator = HeaderDefaultingTransport(fake, emptyList())
        val original = request(listOf(SdkHeader("X-Custom", "v")))

        decorator.execute(original)

        assertEquals(original.headers, fake.capturedRequests.single().headers)
    }

    @Test
    fun capabilitiesPassThroughFromDelegate() {
        val caps = TransportCapabilities(supportsStreaming = true, supportsHttp2 = true, canSetUserAgent = true)
        val decorator = HeaderDefaultingTransport(FakeTransport(caps), emptyList())

        val reported = decorator.capabilities()

        assertTrue(reported.supportsStreaming)
        assertTrue(reported.supportsHttp2)
        assertTrue(reported.canSetUserAgent)
    }
}
