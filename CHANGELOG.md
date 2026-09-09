# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is `0.x`, breaking changes may land in
any release; each is called out under **Breaking** and described in [`docs/migration/`](docs/migration/README.md).

## [Unreleased]

### Added

- **`RetryPolicy.Resilient`** — an opt-in preset that retries `408`/`409`/`429` and the `5xx` gateway codes
  (`500`/`502`/`503`/`504`) on top of safe connection failures, for idempotent workloads. The default stays
  `429`-only (billing-safety; see ADR 0008); `Resilient` is one line to opt into aggressive retries.
- **`SdkResponseResult.generationId()`** — reads OpenRouter's `X-Generation-Id` header (case-insensitive) from any
  `…WithResponse` result, or `null` if absent.
- **`Throwable.openRouterErrorType()`** — reads OpenRouter's stable `error_type` off a caught inference exception,
  uniformly across the chat, Anthropic-messages, and responses skins, returning the open `ApiErrorType` enum (or
  `null` when the value is absent or unreadable, or the throwable is not one of the three inference exceptions).
  Unknown wire values are preserved as `ApiErrorType.SdkUnknown`; the reader never throws and uses no reflection.

### Changed

- **Contract re-pinned to the current OpenRouter OpenAPI (`d49dda78…`, 105 operations)**, up from `e88b0cec…`
  (101). Four operations now generate: OAuth JWKS retrieval (`listOauthJwks`, `GET /oauth/jwks`) and token
  exchange (`createOauthToken`, `POST /oauth/token`), and SCIM sync jobs (`createScimSyncJob`,
  `POST /scim/sync-jobs`; `getScimSyncJob`, `GET /scim/sync-jobs/{id}`). Coverage stays 104 generated + 1 owned
  waiver (`deleteScimGroupMapping`) = 105. The re-pin also carries upstream's additive schema growth (new
  Anthropic message features and per-workload endpoint-performance statistics). The re-pin required no generator
  change and no new waiver — only an audited refresh of the `allOf`-resolution overlay digests.

### Breaking

The `d49dda78` re-pin is classified **breaking** by the layered compatibility report
([`docs/compat/2026-09-09-e88b0cec-to-d49dda78.md`](docs/compat/2026-09-09-e88b0cec-to-d49dda78.md)). The removed
JVM/klib ABI lines are of two kinds: a handful of **source-visible type changes** (below) and a large number of
**mechanical generated all-args constructor moves** — additive upstream fields reshaped many generated models'
primary constructors. Builder-based construction (`xxx { … }`) and deserialization remain additive and unaffected;
only direct callers of a changed generated all-args constructor must supply the new argument. Migration guidance
for the source-visible changes is in [`docs/migration/0.x-generated-renames.md`](docs/migration/0.x-generated-renames.md).

- **`usage.serverToolUse` changed type to the OpenRouter-specific `OrAnthropicServerToolUsage`** on the Anthropic
  messages result (`MessagesResult`) and the streaming message-delta events, replacing the generic
  `AnthropicServerToolUsage`. The new type carries `webFetchRequests`, `webSearchRequests`, `toolCallsExecuted`,
  and `toolCallsRequested`. Upstream added a refined `server_tool_use` branch to the Anthropic `usage` schema;
  an audited **nested** `allOf`-resolution overlay elects it (the OpenRouter intent).
- **`McpCallItem.error` / `McpCallItemView.error` changed type from `String?` to the structured `McpToolCallError?`**
  — upstream promoted the MCP tool-call error from a bare string to a typed object. Consumers reading `.error` on
  an MCP call item must adapt to the new type.

## [0.1.0-rc.1] - 2026-09-02

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
- **Maven Central publication.** Both modules stage under ADR 0006 coordinates
  (`io.github.nabobery:openrouter-kotlin` and `…:openrouter-kotlin-testing`) with POM metadata, sources, a
  lightweight `-javadoc.jar` (the full Dokka reference site is published to GitHub Pages), in-memory PGP signatures,
  a CycloneDX 1.6 SBOM, and SLSA build provenance. Upload is a stdlib Publisher API v1 client (`USER_MANAGED`), and a
  privilege-split release workflow validates → verifies → signs/stages/attests/uploads → releases. Every stage is
  proven credential-free by `scripts/release-rehearsal.sh` and an isolated consumer matrix that resolves the
  published coordinates (JVM, Android, Apple, Native, JS).
- **`openrouter-kotlin-testing`** companion artifact: `OpenRouter.fake`, a capability-matched fake transport, and
  contract-proven chat/stream/error fixtures for consumer tests — no network, no secrets.

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

[Unreleased]: https://github.com/nabobery/openrouter-kotlin/compare/v0.1.0-rc.1...HEAD
[0.1.0-rc.1]: https://github.com/nabobery/openrouter-kotlin/releases/tag/v0.1.0-rc.1
