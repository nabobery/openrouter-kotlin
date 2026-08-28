package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

class RequestDeadlinesTest {
    @Test
    fun mapsDurationsToMillisPreservingNulls() {
        val deadlines = RequestDeadlines(total = 30.seconds, attempt = 5.seconds).toSdkDeadlines()
        assertEquals(30_000L, deadlines.totalMillis)
        assertEquals(5_000L, deadlines.attemptMillis)
        assertNull(deadlines.idleMillis)
    }

    @Test
    fun allNullWhenUnset() {
        val deadlines = RequestDeadlines().toSdkDeadlines()
        assertNull(deadlines.totalMillis)
        assertNull(deadlines.attemptMillis)
        assertNull(deadlines.idleMillis)
    }

    @Test
    fun rejectsNonPositiveDurations() {
        assertFailsWith<SdkConfigurationException> { RequestDeadlines(total = 0.seconds) }
        assertFailsWith<SdkConfigurationException> { RequestDeadlines(attempt = (-1).seconds) }
    }

    @Test
    fun rejectsSubMillisecondDurations() {
        // 500us is > Duration.ZERO but truncates to 0ms, which SdkDeadlines rejects — reject eagerly instead.
        assertFailsWith<SdkConfigurationException> { RequestDeadlines(total = 500.microseconds) }
        assertFailsWith<SdkConfigurationException> { RequestDeadlines(streamIdle = 999.microseconds) }
    }
}
