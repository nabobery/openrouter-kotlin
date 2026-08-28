package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkConfigurationException
import com.nabobery.sdkgen.runtime.SdkDeadlines
import kotlin.time.Duration

/**
 * Layered deadlines: whole logical call, one physical attempt, and gap between stream progress signals.
 *
 * Each deadline is validated eagerly against its *converted* millisecond representation (the unit the runtime's
 * [SdkDeadlines] enforces), so a positive-but-sub-millisecond duration — which truncates to `0` and would be
 * rejected late by [SdkDeadlines] — throws [SdkConfigurationException] at construction instead.
 */
public class RequestDeadlines(
    public val total: Duration? = null,
    public val attempt: Duration? = null,
    public val streamIdle: Duration? = null,
) {
    init {
        listOf("total" to total, "attempt" to attempt, "streamIdle" to streamIdle).forEach { (name, value) ->
            if (value != null && value.inWholeMilliseconds < 1) {
                throw SdkConfigurationException("$name deadline must be at least 1 millisecond when set, got $value.")
            }
        }
    }

    internal fun toSdkDeadlines(): SdkDeadlines = SdkDeadlines(
        totalMillis = total?.inWholeMilliseconds,
        attemptMillis = attempt?.inWholeMilliseconds,
        idleMillis = streamIdle?.inWholeMilliseconds,
    )
}
