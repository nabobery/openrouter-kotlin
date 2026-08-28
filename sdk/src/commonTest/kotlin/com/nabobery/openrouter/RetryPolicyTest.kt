package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.PolicyOverride
import com.nabobery.sdkgen.runtime.ResponseSelector
import com.nabobery.sdkgen.runtime.RetryDescriptor
import com.nabobery.sdkgen.runtime.SdkConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {
    @Test
    fun defaultRetriesOnlyRateLimits() {
        val override = RetryPolicy.Default.toOverride()
        val descriptor = assertIs<PolicyOverride.Replace<*>>(override).value as RetryDescriptor

        val codes = descriptor.retryableStatusCodes.map { (it as ResponseSelector.ExactStatus).code }.toSet()
        assertEquals(setOf(429), codes)
        assertEquals(3, descriptor.maxAttempts)
        assertTrue(descriptor.retryConnectionErrors)
        val backoff = assertNotNull(descriptor.backoff)
        assertEquals(500L, backoff.baseDelayMillis)
        assertEquals(60_000L, backoff.maxDelayMillis)
        assertEquals(2.0, backoff.multiplier)
    }

    @Test
    fun customStatusesAndDelaysMapThrough() {
        val override =
            RetryPolicy(
                maxAttempts = 5,
                initialDelay = 250.milliseconds,
                maxDelay = 10.seconds,
                multiplier = 1.5,
                retryConnectionFailures = false,
                retryableStatusCodes = setOf(500, 502),
            ).toOverride()
        val descriptor = (override as PolicyOverride.Replace<*>).value as RetryDescriptor

        assertEquals(
            setOf(500, 502),
            descriptor.retryableStatusCodes.map {
                (it as ResponseSelector.ExactStatus).code
            }.toSet(),
        )
        assertEquals(5, descriptor.maxAttempts)
        assertEquals(false, descriptor.retryConnectionErrors)
        val backoff = assertNotNull(descriptor.backoff)
        assertEquals(250L, backoff.baseDelayMillis)
        assertEquals(10_000L, backoff.maxDelayMillis)
        assertEquals(1.5, backoff.multiplier)
    }

    @Test
    fun noneMapsToDisabled() {
        assertEquals(PolicyOverride.Disabled, RetryPolicy.None.toOverride())
    }

    @Test
    fun rejectsMaxAttemptsBelowOne() {
        assertFailsWith<SdkConfigurationException> { RetryPolicy(maxAttempts = 0) }
    }

    @Test
    fun rejectsNegativeDelays() {
        assertFailsWith<SdkConfigurationException> { RetryPolicy(initialDelay = (-1).milliseconds) }
    }

    @Test
    fun rejectsZeroInitialDelay() {
        assertFailsWith<SdkConfigurationException> { RetryPolicy(initialDelay = 0.milliseconds) }
    }

    @Test
    fun rejectsSubMillisecondInitialDelay() {
        // 500us truncates to 0ms, which the runtime's BackoffHints rejects — we must reject eagerly.
        assertFailsWith<SdkConfigurationException> { RetryPolicy(initialDelay = 500.microseconds) }
    }

    @Test
    fun rejectsMaxDelayBelowInitialDelay() {
        assertFailsWith<SdkConfigurationException> {
            RetryPolicy(initialDelay = 5.seconds, maxDelay = 1.seconds)
        }
    }

    @Test
    fun rejectsMultiplierBelowOne() {
        assertFailsWith<SdkConfigurationException> { RetryPolicy(multiplier = 0.5) }
    }

    @Test
    fun rejectsNonFiniteMultiplier() {
        assertFailsWith<SdkConfigurationException> { RetryPolicy(multiplier = Double.NaN) }
        assertFailsWith<SdkConfigurationException> { RetryPolicy(multiplier = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun statusCodesAreDefensivelyCopied() {
        val source = mutableSetOf(429)
        val policy = RetryPolicy(retryableStatusCodes = source)
        source.add(500)
        assertEquals(setOf(429), policy.retryableStatusCodes)
    }
}
