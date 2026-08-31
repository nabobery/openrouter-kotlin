package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkHeader

/**
 * Typed app-attribution defaults, mapped to the pinned spec's HTTP-Referer / X-OpenRouter-Title /
 * X-OpenRouter-Categories headers. Absent fields are omitted, so partial attribution is valid.
 */
public class Attribution(
    public val referer: String? = null,
    public val title: String? = null,
    categories: List<String> = emptyList(),
) {
    /** App category tags sent as `X-OpenRouter-Categories`; a defensive immutable copy of the constructor argument. */
    public val categories: List<String> = categories.toList()

    internal fun toHeaders(): List<SdkHeader> = buildList {
        referer?.let { add(SdkHeader("HTTP-Referer", it)) }
        title?.let { add(SdkHeader("X-OpenRouter-Title", it)) }
        if (categories.isNotEmpty()) add(SdkHeader("X-OpenRouter-Categories", categories.joinToString(",")))
    }
}
