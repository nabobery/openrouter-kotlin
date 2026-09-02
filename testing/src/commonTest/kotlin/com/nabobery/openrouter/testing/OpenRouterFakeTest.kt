package com.nabobery.openrouter.testing

import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.RetryPolicy
import com.nabobery.openrouter.chat.ChatClient
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenRouterFakeTest {
    @Test
    fun fakeTransportSupportsStreaming() = runTest {
        val transport = openRouterFakeTransport()
        val client = OpenRouter.fake(transport)
        transport.enqueueChatStream("a")
        // A streaming call over the fake transport does NOT throw SdkCapabilityException (streaming is on).
        val deltas = client.chat.stream("openrouter/test", listOf(userMessage("hi"))).contentDeltas().toList()
        assertEquals(listOf("a"), deltas)
    }

    @Test
    fun fakeUsesStaticTestCredentialThatNeverLeaks() = runTest {
        val transport = openRouterFakeTransport()
        val client = OpenRouter.fake(transport)
        transport.enqueueChatCompletion("hi")
        client.chat.send("openrouter/test", listOf(userMessage("hi")))

        val auth = transport.capturedRequests.single().headers.single { it.name.equals("Authorization", true) }
        assertTrue(auth.value.startsWith("Bearer sk-or-test-"), "expected a test bearer token, got '${auth.value}'")
        // The secret must never surface through the transport's own string representation.
        assertFalse(transport.toString().contains(TEST_API_KEY))
    }

    @Test
    fun enqueueChatCompletionDecodesToContent() = runTest {
        val transport = openRouterFakeTransport()
        val client = OpenRouter.fake(transport)
        transport.enqueueChatCompletion(content = "hello", model = "openrouter/test")

        val result = client.chat.send("openrouter/test", listOf(userMessage("hi")))

        assertEquals("hello", result.choices.first().message.content?.branch1)
        assertEquals("openrouter/test", result.model)
        assertNotNull(result.usage)
    }

    @Test
    fun enqueueChatStreamYieldsDeltasThenCompletes() = runTest {
        val transport = openRouterFakeTransport()
        val client = OpenRouter.fake(transport)
        transport.enqueueChatStream("hel", "lo")

        val deltas = client.chat.stream("openrouter/test", listOf(userMessage("hi"))).contentDeltas().toList()

        assertEquals(listOf("hel", "lo"), deltas)
    }

    @Test
    fun enqueueErrorThrowsTypedApiExceptionWithoutRetry() = runTest {
        val transport = openRouterFakeTransport()
        val client = OpenRouter.fake(transport) { retryPolicy = RetryPolicy.None }
        transport.enqueueError(429, "slow down")

        val ex =
            assertFailsWith<ChatClient.SendChatCompletionRequestApiException> {
                client.chat.send("openrouter/test", listOf(userMessage("hi")))
            }
        assertEquals(429, ex.statusCode)
        val error = ex.error
        assertTrue(error is ChatClient.SendChatCompletionRequestResponse.Http429Json)
        // The typed 429 body carries the message; the exception message deliberately does not (never assert on it).
        assertEquals("slow down", error.json.error.message)
        // RetryPolicy.None => exactly one physical attempt.
        assertEquals(1, transport.capturedRequests.size)
    }

    @Test
    fun enqueueJsonDecodesAModelsPage() = runTest {
        val transport = openRouterFakeTransport()
        val client = OpenRouter.fake(transport)
        transport.enqueueJson(200, OpenRouterFixtures.modelsPageJson())

        val page = client.models.getModels()

        assertTrue(page.items.isEmpty())
    }

    @Test
    fun builderOverridesReachTheFake() = runTest {
        val transport = openRouterFakeTransport()
        val client = OpenRouter.fake(transport) { attribution(title = "t") }
        transport.enqueueChatCompletion("hi")
        client.chat.send("openrouter/test", listOf(userMessage("hi")))

        val title = transport.capturedRequests.single().headers.single { it.name.equals("X-OpenRouter-Title", true) }
        assertEquals("t", title.value)
    }
}
