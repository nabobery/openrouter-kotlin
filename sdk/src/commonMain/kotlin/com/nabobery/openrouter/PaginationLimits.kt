package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.PaginationBounds
import com.nabobery.sdkgen.runtime.SdkConfigurationException
import kotlin.time.Duration

/**
 * Bounds for automatic pagination (`xxxPages()` / `xxxItems()` flows). Unbounded by default — a walk over a large
 * collection issues one request per page, so set [maxPages] or [maxItems] whenever the collection size is not known.
 *
 * [maxElapsed] covers the whole walk and fails with `SdkTimeoutException(phase = PAGINATION_BUDGET)`; it is validated
 * against its converted millisecond representation (the unit the runtime enforces), so a positive-but-sub-millisecond
 * duration — which truncates to `0` — throws [SdkConfigurationException] at construction rather than being silently
 * dropped. This mirrors [RequestDeadlines].
 */
@OpenRouterExperimentalApi
public class PaginationLimits(
    public val maxPages: Int? = null,
    public val maxItems: Long? = null,
    public val maxElapsed: Duration? = null,
) {
    init {
        maxPages?.let {
            if (it < 1) throw SdkConfigurationException("maxPages must be at least 1 when set, got $it.")
        }
        maxItems?.let {
            if (it < 1) throw SdkConfigurationException("maxItems must be at least 1 when set, got $it.")
        }
        maxElapsed?.let {
            if (it.inWholeMilliseconds < 1) {
                throw SdkConfigurationException("maxElapsed must be at least 1 millisecond when set, got $it.")
            }
        }
    }

    internal fun toBounds(): PaginationBounds = PaginationBounds(maxPages, maxItems, maxElapsed?.inWholeMilliseconds)
}
