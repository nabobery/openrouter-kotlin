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


## Amendment (2026-09-02): Release-candidate publication mechanics

The publication is now implemented against the Maven Central Portal (OSSRH ended 2025-06-30). Concrete decisions that
refine — but do not change — the coordinates and One-Version Rule above:

- **Version grammar.** `MAJOR.MINOR.PATCH[-rc.N|-SNAPSHOT]`, single-sourced in `gradle.properties`
  (`openrouter.version`) and kept in lockstep with the `SDK_VERSION` constant. The first candidate is `0.1.0-rc.1`.
- **Upload path.** The Publisher API v1 with `USER_MANAGED` publishing by default: a release is uploaded, validated,
  verified against the isolated consumer matrix, and only then published (or dropped) by a human. Published
  components are immutable; a bad release is superseded by a patch, never edited.
- **Documentation jars.** Every publication carries a `-javadoc.jar`, but it holds a lightweight overview, not the
  full Dokka site: the generated surface makes the complete HTML site ~100 MB, and embedding it in all 24
  publications would push the Central upload bundle past 2 GB (Central's soft limit is ~78 MB/month). The complete
  Dokka reference is published to GitHub Pages instead, and the overview links to it.
- **SBOM and provenance.** The CycloneDX SBOM and SLSA build provenance are GitHub Release assets and attestations,
  **not** Central files — keeping them out of the metered Central bundle.
- **Testing artifact shipped.** `io.github.nabobery:openrouter-kotlin-testing` is a real module publishing the same
  target matrix, re-exporting `kotlin-sdkgen-testing` plus OpenRouter fixtures and `OpenRouter.fake`.
- **Klib naming.** The testing module's Gradle project is named `:openrouter-kotlin-testing` (its directory stays
  `testing/`) so its klib `unique_name` does not collide with `kotlin-sdkgen-testing`'s own `io.github.nabobery:testing`
  klib — a clash the JS/Native klib resolvers reject.
