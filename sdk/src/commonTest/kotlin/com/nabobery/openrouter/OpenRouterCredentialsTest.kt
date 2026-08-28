package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkConfigurationException
import com.nabobery.sdkgen.runtime.auth.Credential
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class OpenRouterCredentialsTest {
    @Test
    fun staticProducesBearerCredential() = runTest {
        val credential = OpenRouterCredentials.static("sk-or-test-123").credentials()
        val bearer = assertIs<Credential.BearerCredential>(credential)
        assertEquals("sk-or-test-123", bearer.token.reveal())
    }

    @Test
    fun staticRejectsBlankKeyBeforeAnyIo() {
        assertFailsWith<SdkConfigurationException> { OpenRouterCredentials.static("   ") }
    }

    @Test
    fun dynamicResolvesOnEveryInvocation() = runTest {
        var calls = 0
        val provider = OpenRouterCredentials.dynamic {
            calls += 1
            "key-$calls"
        }
        provider.credentials()
        provider.credentials()
        assertEquals(2, calls)
    }

    @Test
    fun dynamicRejectsBlankResolvedKey() = runTest {
        val provider = OpenRouterCredentials.dynamic { "" }
        assertFailsWith<SdkConfigurationException> { provider.credentials() }
    }

    @Test
    fun secretNeverAppearsInToString() = runTest {
        val provider = OpenRouterCredentials.static("sk-or-super-secret")
        val bearer = provider.credentials() as Credential.BearerCredential
        assertFalse(bearer.token.toString().contains("sk-or-super-secret"))
    }
}
