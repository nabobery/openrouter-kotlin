# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is `0.x`, breaking changes may land in
any release; each is called out under **Breaking** and described in [`docs/migration/`](docs/migration/README.md).

## [Unreleased]

### Added

- Curated inference facade over the generated clients: `chat.send` / `chat.stream`, the `messages { }` DSL and
  `userMessage`/`systemMessage` helpers, `responses`, and Anthropic `messages` — all binary-additive extensions.
- Incremental SSE streaming as a cold `Flow<ChatStreamEvent>` with `contentDeltas()` text projection; streams are
  never retried (stricter than the buffered path).
- Layered client policy carried into every generated call: `RetryPolicy` (429-only default, ADR 0004),
  `RequestDeadlines` (opt-in `total`/`attempt`/`streamIdle`), `Attribution`, one shared `RetryBudget`, and a
  `User-Agent` product token `openrouter-kotlin/<version>`.
- `PaginationLimits` (`maxPages`/`maxItems`/`maxElapsed`) and cold-`Flow` `xxxPages()`/`xxxItems()` idioms.
- `FilesClient.listAllFiles(...)` — a bounded curated cursor walk over the file-list union (`@OpenRouterExperimentalApi`),
  following the provider-specific continuation and failing closed on a repeated continuation token.
- Bounded byte-stream helpers (`readAllBytes(maxBytes)`, `downloadBytes`, 64 MiB default download bound) and a
  redacting `Secret` credential abstraction with trusted-host credential attachment.
- Multiplatform target family: JVM, Android, Apple (macOS/iOS incl. simulator), Linux (x64/arm64), Windows
  (mingw), and JS (Node + browser), with JVM/klib ABI baselines validated by binary-compatibility-validator.
- Operational tooling: a daily digest-based drift pull-request pipeline (unprivileged regenerate → data-only patch
  → allowlisted apply), a layered compatibility report (OpenAPI → semantic → source → ABI → wire → behaviour →
  targets) that fails on any unclassified change, an official TypeScript/Python/Go parity matrix, a STRIDE threat
  model with a CI-checked secret-isolation report, dependency/security scanning workflows, and a KDoc completeness gate.
- Compile-checked tutorials and how-to guides ([`docs/guides/`](docs/guides/README.md)) whose examples are injected
  from a compiled module and freshness-gated.

### Changed

- Client defaults now reach every call without `options`: a call made with no `options` retries, honours the client
  deadlines, and notifies client observers (ADR 0022). `OpenRouter.options { … }` is now **per-call overrides
  only** — it no longer re-emits client defaults.

### Breaking (pre-1.0)

- The generated root client is named `OpenRouterClient` (wrapped by the curated `OpenRouter` facade).
- Trusted-origin configuration is `OpenRouterBuilder.trustOrigin(origin)` (renamed from the earlier `trustHost`).
- At the 2026-08-29 contract re-pin (`b901d462…` → `b2a4948a…`), Responses and Analytics reached GA and their
  `beta.*` surfaces were removed: `BetaResponsesClient` → `ResponsesClient` (`client.betaResponses` →
  `client.responses`), and `BetaAnalyticsClient` folded into `AnalyticsClient` (`betaAnalytics` removed).
- At the 2026-08-30 contract re-pin (`b2a4948a…` → `e88b0cec…`), the additive upstream `cosine` provider fields
  changed several generated JVM all-arguments constructor signatures. Builder-based construction and
  deserialization remain additive; direct callers of those generated constructors must supply the new field.
- `options()` is no longer required to obtain client defaults (see **Changed**); code that passed
  `options = client.options()` solely to get client behaviour can drop it.

See [`docs/migration/0.x-generated-renames.md`](docs/migration/0.x-generated-renames.md) for the before/after symbol
table.

[Unreleased]: https://github.com/nabobery/openrouter-kotlin/commits/main
