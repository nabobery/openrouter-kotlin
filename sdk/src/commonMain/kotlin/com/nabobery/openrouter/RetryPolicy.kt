package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.BackoffHints
import com.nabobery.sdkgen.runtime.PolicyOverride
import com.nabobery.sdkgen.runtime.ResponseSelector
import com.nabobery.sdkgen.runtime.RetryDescriptor
import com.nabobery.sdkgen.runtime.SdkConfigurationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Client-level retry defaults, mapped onto the runtime's replay-aware retry engine.
 *
 * The default status allowlist contains only 429 (ADR 0004): rate-limited requests are safe to retry, while
 * service/provider failures remain an explicit caller decision:
 *
 * - The runtime honours OpenRouter's `Retry-After` guidance when computing backoff.
 * - The runtime never restarts a stream once it has emitted an event (ADR 0004), so partial billable
 *   output is never re-requested.
 * - An ambiguous mid-flight connection failure — one where the request may already have reached the server —
 *   is never replayed for a non-idempotent operation without an idempotency key, even with
 *   [retryConnectionFailures] enabled. Only failures that provably never reached the server are replayed.
 *
 * Broader status sets (408/500/502/503/524/529) are an explicit caller opt-in because delivery, provider attempts,
 * and BYOK billing may be ambiguous for them.
 *
 * All numeric fields are validated eagerly against the runtime's own backoff contract, so an invalid policy
 * throws [SdkConfigurationException] at construction rather than a late `IllegalArgumentException` when the
 * policy is first materialised onto a call.
 */
public class RetryPolicy(
    public val maxAttempts: Int = 3,
    public val initialDelay: Duration = 500.milliseconds,
    public val maxDelay: Duration = 60.seconds,
    public val multiplier: Double = 2.0,
    public val retryConnectionFailures: Boolean = true,
    retryableStatusCodes: Set<Int> = DEFAULT_RETRYABLE_STATUS_CODES,
) {
    /** The HTTP status codes eligible for retry; a defensive immutable copy of the constructor argument. */
    public val retryableStatusCodes: Set<Int> = retryableStatusCodes.toSet()

    init {
        if (maxAttempts < 1) throw SdkConfigurationException("maxAttempts must be at least 1.")
        // Validate the *converted* millisecond representation, matching the runtime's BackoffHints contract
        // (baseDelayMillis > 0, maxDelayMillis >= baseDelayMillis, finite multiplier >= 1.0). A sub-millisecond
        // duration truncates to 0ms and would otherwise be rejected late by BackoffHints.
        val initialMillis = initialDelay.inWholeMilliseconds
        val maxMillis = maxDelay.inWholeMilliseconds
        if (initialMillis < 1) {
            throw SdkConfigurationException("initialDelay must be at least 1 millisecond, got $initialDelay.")
        }
        if (maxMillis < initialMillis) {
            throw SdkConfigurationException("maxDelay ($maxDelay) must be >= initialDelay ($initialDelay).")
        }
        if (!multiplier.isFinite() || multiplier < 1.0) {
            throw SdkConfigurationException("multiplier must be a finite value >= 1.0, got $multiplier.")
        }
    }

    internal fun toOverride(): PolicyOverride<RetryDescriptor> = if (maxAttempts == 1) {
        PolicyOverride.Disabled
    } else {
        PolicyOverride.Replace(
            RetryDescriptor(
                retryableStatusCodes = retryableStatusCodes.map { ResponseSelector.ExactStatus(it) },
                retryConnectionErrors = retryConnectionFailures,
                maxAttempts = maxAttempts,
                backoff =
                BackoffHints(
                    initialDelay.inWholeMilliseconds,
                    multiplier,
                    maxDelay.inWholeMilliseconds,
                ),
            ),
        )
    }

    public companion object {
        /** The default retryable status allowlist: only `429` (rate limiting), which is always safe to replay. */
        public val DEFAULT_RETRYABLE_STATUS_CODES: Set<Int> = setOf(429)

        /** The default policy: 3 attempts, 500 ms → 60 s exponential backoff (×2.0), retry on 429 and safe connection failures. */
        public val Default: RetryPolicy = RetryPolicy()

        /** A policy that never retries (a single attempt). */
        public val None: RetryPolicy = RetryPolicy(maxAttempts = 1)
    }
}
