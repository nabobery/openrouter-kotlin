# ADR 0008: 1.0 API freeze and stability guarantees

## Status

Accepted (2026-09-09). Concludes the long-deferred **1.0 API review**. Builds on
[ADR 0001](./0001-product-scope-and-api-layers.md) (curated vs generated layers),
[ADR 0003](./0003-ktor-and-transport-injection.md) (consumer-owned transport),
[ADR 0004](./0004-streaming-failures-retries-and-deadlines.md) (retry/deadline model), and
[ADR 0007](./0007-final-target-tiers-for-1-0.md) (target tiers). This ADR is the canonical citation for the
1.0 stability tiers, the retry default and its opt-in preset, and the `AutoCloseable` decision. The compatibility
mechanics live in [`docs/compatibility-policy.md`](../compatibility-policy.md); the per-symbol audit gate is
`scripts/api-surface-audit.py`.

## Context

`1.0.0` promises the consumer a stable surface. The SDK is two layers (ADR 0001): a thin **curated** layer under
`sdk/src/**` over the generated clients, and the **generated** layer produced by kotlin-sdkgen from the pinned
OpenAPI contract. The 1.0 review must say precisely what is frozen, what is best-effort, and what remains
explicitly provisional — and must be enforceable, not aspirational. The BCV dumps
(`sdk/api/sdk.api`, `sdk/api/jvm/sdk.api`, `sdk/api/sdk.klib.api`) describe every target's ABI and are the
authority the audit gate reads (a JVM reflection audit would miss the klib/JS surface).

## Decision

### Stability tiers

- **Stable.** The **curated public surface** not marked `@OpenRouterExperimentalApi`, plus the **generated
  operation signatures for the pinned contract** (`d49dda78…`, 105 operations). Concretely: the `OpenRouter` root
  and its construction, `OpenRouterCredentials`, `Attribution`, `ReservedHeaders`, `RetryPolicy`,
  `RequestDeadlines`, the `chat` / `responses` / Anthropic `messages` inference facades and their stream events,
  the error-type accessors, and the generated resource-client operation signatures. Source, binary, and behavioural
  compatibility are promised for this surface across the `1.x` line.
- **Best-effort.** The **klib ABI** (native/JS): both binary-compatibility-validator's klib support and KGP's
  `abiValidation` are Experimental, so the klib baseline is enforced best-effort, not as a hard binary guarantee.
  **Tier 2/3 targets** (`mingwX64`, `linuxArm64` — see ADR 0007) are compile-verified every CI run with
  runtime best-effort.
- **Experimental.** Declarations marked `@OpenRouterExperimentalApi` (a `@RequiresOptIn(level = WARNING)` marker).
  These may change or be removed in any `1.x` release without a major bump. See the enumerated list below.

### The `@OpenRouterExperimentalApi` surface at 1.0 (graduation decisions)

The experimental marker ships at 1.0 and annotates one coherent **transfers-&-pagination cluster**. Each stays
experimental for the reason given; none graduates at 1.0 (graduation is additive and safe to do in any `1.x`
minor — un-graduation is not, so we keep options open where the shape is still upstream-dependent):

| Declaration(s) | Keep-experimental reason |
| --- | --- |
| `PaginationLimits`, `OpenRouter.options { pagination(…) }`, `FilesClient.listAllFiles` | The multi-page file surface is a curated cursor walk standing in for a generated flow kotlin-sdkgen cannot yet emit over a `_shape`-discriminated union envelope (exception register). Its shape settles — and may defer to the generated flow — when that lands. |
| `FilesClient.upload(…)`, `SttClient.transcribe(…)` | The generated multipart codec cannot set a per-part filename or content type (exception register). These signatures are expected to **grow** those parameters when the codec supports them; freezing them at 1.0 would force an overload sprawl or a break. |
| `byteStreamOf`, `SdkByteStream.readAllBytes`/`asFlow`, `FilesClient.downloadBytes`, `OpenRouter.options { transferObserver(…) }` | The byte-stream + transfer-observer helpers are the I/O substrate for the transfer operations above; kept opt-in alongside them so the transfer ergonomics graduate as one settled unit rather than a fragmented half-stable surface. |

### Compatibility contract for the Stable surface

- **Published functions never gain parameters.** A new capability is a **new overload** (or a new function), never
  an added parameter (even a defaulted one) on an existing published signature — adding a parameter changes the
  binary signature. Explicit return types are mandatory and enforced by the compiler's strict explicit-API mode.
- **No `data class` in the public curated surface.** `data class` leaks `componentN()`/`copy()` into the ABI and
  couples the class to its constructor shape; curated types use plain classes with builders. The audit fails on any
  curated `data class` (there are none today — the check is a guard).
- **`@PublishedApi` allowlist.** Any `@PublishedApi` symbol in curated source must appear in the audit's allowlist,
  which this ADR governs. The allowlist is **empty** at 1.0 (there are no curated `@PublishedApi` symbols).
- **Deprecation ladder.** A stable symbol is retired only by: `@Deprecated(WARNING)` for **at least one minor or six
  months** → `@Deprecated(ERROR)` → removal **only at a major**. Nothing stable is removed within `1.x`.
- **Generated re-pins after 1.0.** A post-1.0 spec re-pin that removes or breaks a generated signature is a
  **major** change ([`docs/compatibility-policy.md`](../compatibility-policy.md) §"From 1.0"); additive re-pins are
  minors. Every re-pin ships a compatibility report classifying the change.

### Recorded 1.0 API-review decisions (canonical citations)

- **Retry default stays `{429}`.** `RetryPolicy.Default` retries only HTTP 429 (plus connection failures per its
  own flag). Rationale: billing safety — OpenRouter may already have attempted a provider, and BYOK billing sits
  outside credit insurance, so blanket 5xx retries can double-bill. A named **`RetryPolicy.Resilient`** preset
  (408/409/429/5xx + connection failures) lets callers opt into aggressive retries in one line. This ADR is the
  citation for both; the default allowlist is never widened.
- **No `AutoCloseable` on the root.** The `OpenRouter` root does not implement `AutoCloseable`. The SDK never owns,
  mutates, or closes the consumer-supplied transport/`HttpClient` (ADR 0003); a no-op `close()` would invite the
  misconception that the SDK manages a lifecycle it does not own.
- **No `client.beta` namespace.** The pinned contract carries no beta-tagged resources; the namespace is dropped
  (reintroduced only if a future contract carries beta resources).
- **`RetryPolicy.replayMode` not surfaced.** Runtime idempotency gating suffices; no curated knob ships.

## Consequences

The stable curated surface and the pinned generated operation signatures are frozen for `1.x`; the audit gate
(`scripts/api-surface-audit.py --check`, wired into CI) enforces the `data class` and `@PublishedApi` invariants on
every change, the strict `explicitApi()` compiler gate requires explicit public return types, and the BCV
`apiCheck` enforces the ABI baselines. The experimental
transfers/pagination cluster remains opt-in and can graduate additively as upstream limitations close. Consumers
who want aggressive retries opt into `RetryPolicy.Resilient`; the safe default never changes under them. The
`@OpenRouterExperimentalApi` enumeration in this ADR is the source of truth for what may still move at 1.0.
