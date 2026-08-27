# ADR 0005: Generate from a pinned OpenAPI contract with reviewed overlays

## Status

Accepted.

## Decision

- `kotlin-sdkgen` produces the generated API and runtime integration from a pinned OpenRouter OpenAPI document.
- The repository records the canonical source URL, digest, retrieval time, and generator version.
- Narrow, versioned overlays may resolve Kotlin naming, discriminator, pagination, streaming, optionality, or known
  upstream-contract limitations.
- Overlays require a rationale, tests, and unused-overlay detection.
- A scheduled workflow checks daily for upstream drift, regenerates deterministically, runs gates, and opens a reviewable
  pull request.
- Drift pull requests never auto-merge or publish.
- Compatibility reports distinguish wire, semantic, Kotlin source, binary, behavior, and target changes.

## Consequences

API coverage can track OpenRouter quickly without hiding generation workarounds. Maintainers must review generated
changes and keep overlays small.

