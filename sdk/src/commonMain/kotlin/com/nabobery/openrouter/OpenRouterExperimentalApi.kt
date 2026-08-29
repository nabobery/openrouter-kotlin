package com.nabobery.openrouter

/**
 * Marks curated openrouter-kotlin APIs that may change incompatibly before 1.0 — the byte-stream helpers
 * (`com.nabobery.openrouter.io`), the curated file/STT media overloads, and the automatic-pagination bounds.
 *
 * The 2026-08-29 contract GA'd both Responses and Analytics, so there are currently **no** generated beta
 * resources to group under a `client.beta` namespace (see `docs/coverage/exception-register.md`); this marker
 * therefore annotates the pre-1.0 curated helpers rather than a beta-resource namespace. Opt in with
 * `@OptIn(OpenRouterExperimentalApi::class)`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Experimental openrouter-kotlin API: may change before 1.0. " +
        "Opt in with @OptIn(OpenRouterExperimentalApi::class).",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class OpenRouterExperimentalApi
