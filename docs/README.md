# OpenRouter Kotlin documentation

This directory contains the product and engineering contract for **OpenRouter Kotlin**, an independent
Kotlin Multiplatform client SDK for OpenRouter.

## Document map

| Document | Purpose | Primary audience |
| --- | --- | --- |
| [Product requirements](./product-requirements.md) | Defines the problem, product scope, users, requirements, and success measures | Product, engineering, maintainers |
| [System design](./system-design.md) | Explains the architecture, modules, request lifecycle, and operational qualities | SDK and platform engineers |
| [Public API design](./public-api-design.md) | Defines the generated and curated Kotlin interfaces | SDK consumers and maintainers |
| [Target support](./target-support.md) | Defines the KMP target-family tiers and engine policy | KMP consumers and release engineers |
| [Testing strategy](./testing-strategy.md) | Defines verification layers, matrices, and release evidence | Contributors and release engineers |
| [Specification sync and release](./spec-sync-and-release.md) | Defines OpenAPI drift, generation, compatibility review, and publication | Maintainers |
| [Security and privacy](./security-and-privacy.md) | Defines credential, diagnostics, transport, and supply-chain controls | Security reviewers and maintainers |
| [Compatibility policy](./compatibility-policy.md) | Defines stability, SemVer, deprecation, experimental APIs, and target lifecycle | Consumers and maintainers |
| [Architecture decisions](./adr/README.md) | Records the decisions that should not be re-litigated without new evidence | Maintainers |

## Source-of-truth order

When sources disagree, use this order:

1. Observed OpenRouter server behavior, captured by a reproducible test.
2. The pinned OpenRouter OpenAPI document.
3. OpenRouter's official TypeScript, Python, and Go SDK conventions.
4. OpenRouter's published documentation.
5. OpenRouter Kotlin's curated API policy.

Any intentional deviation must be documented, tested, and included in release notes.

## Documentation model

The documents here follow the [Diátaxis](https://diataxis.fr/) model: **reference** (engineering references, the
module/package overview, and source KDoc), **explanation** (architecture and design), **tutorials** (learning-oriented), and
**how-to guides** (goal-oriented). Product-requirement and architectural-explanation documents live here directly;
the tutorials and how-tos are under [`guides/`](guides/README.md).

Every code example in the guides is **real, compiled Kotlin**: examples are `// region` blocks in the
`:samples:docs` module (and the Android/iOS sample modules), injected into Markdown by `scripts/docs-snippets.py`
(`kt → md`) and compiled by `samplesCheck` / CI, with a `docs-snippets.py check` freshness gate. We chose this
direction over a `md → kt` tool (such as kotlinx-knit): the examples then refactor with the IDE and add no plugin
to the build classpath. This keeps examples from documenting speculative syntax — a guide can only show a compiling
API.

| Kind | Where |
| --- | --- |
| Tutorials | [`guides/tutorials/`](guides/tutorials/) |
| How-to guides | [`guides/how-to/`](guides/how-to/) |
| Reference (design) | this directory (e.g. `public-api-design.md`, `system-design.md`) |
| Reference (API) | [`api/module.md`](api/module.md) and KDoc on public source declarations |
| Explanation | `adr/`, `system-design.md` |

## Status language

- **Proposed**: agreed direction that is not yet implemented.
- **Experimental**: implemented but may change incompatibly.
- **Beta**: feature-complete enough for external validation, with limited compatibility guarantees.
- **Stable**: covered by the compatibility policy and release gates.

## Updating these documents

Update the affected requirement, design, API reference, test plan, and ADR together. A change is incomplete if code and
documentation describe different contracts. Generated source is not edited by hand.
