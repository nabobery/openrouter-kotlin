# ADR 0006: Publish one primary artifact and an optional testing artifact

## Status

Accepted. Amended 2026-08-17: Maven group changed to `io.github.nabobery`.

## Decision

- Maven group: `io.github.nabobery`.
- Primary coordinate: `io.github.nabobery:openrouter-kotlin`.
- Optional testing coordinate: `io.github.nabobery:openrouter-kotlin-testing`.
- Kotlin package namespace remains `com.nabobery.openrouter.*`; Maven Central verifies group ownership, not
  package names.
- Resource clients are packages within the primary SDK, not separate consumer artifacts.
- Kotlin Multiplatform root metadata and all supported target publications share one version.
- Releases include sources, documentation, POM metadata, signatures, checksums, SBOM, and provenance.
- Publication is explicitly triggered after release gates; schema drift cannot publish.

## Consequences

Consumers get simple dependency management and avoid cross-module version skew. The primary artifact may be larger than
resource-split alternatives, but the One-Version Rule and predictable setup outweigh speculative modularity.

## Amendment (2026-08-17): Maven group

The original `com.nabobery` group is not verifiable on the Maven Central Portal because the `nabobery.com` domain is
not owned; `io.github.nabobery` auto-verifies through GitHub ownership. `kotlin-sdkgen` made the same migration for
its 0.1.0 release (its ADR-0008 amendment, 2026-08-03), and this project follows it before any code exists, so nothing
is churned. As with `kotlin-sdkgen`, the Kotlin package namespace keeps the shorter `com.nabobery.openrouter.*` form.

