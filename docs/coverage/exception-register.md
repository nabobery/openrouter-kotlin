# Exception register

Every omitted or degraded capability in the generated/curated surface, one row each, per
`docs/product-requirements.md` §6.3 (owner, reason, user impact, workaround, expiry, 1.0 disposition).
Regenerate the companion coverage dashboard with `python3 scripts/coverage-dashboard.py`
(`docs/coverage/operation-coverage.md`).

## Omitted operations

### `deleteScimGroupMapping` — omitted (accepted waiver)

| Field | Value |
| --- | --- |
| Owner | openrouter-kotlin-maintainers |
| Reason | kotlin-sdkgen 0.3.0 cannot represent the non-scalar query parameter `keep_members` (schema is an `anyOf` of a string enum and a boolean). Accepted waiver `openrouter-scim-group-mappings-delete-keep-members` in `spec/sdkgen.yaml` (`disposition: omit`). |
| User impact | Cannot delete a SCIM group-to-workspace mapping through the generated SDK (`OpenRouter.scim` has no `deleteScimGroupMapping`). 100 of 101 operations remain callable. |
| Workaround | Issue the raw call directly: `DELETE /scim/group-mappings/{id}?keep_members={true\|false}` with the management-key `Authorization` header, via a hand-built Ktor/HTTP request. `keep_members` is required (omitting it returns `400`). |
| Expiry | Next kotlin-sdkgen release that represents object/union-typed parameters. |
| 1.0 disposition | Upstream generator support for object/union-typed form/query parameters, or an upstream spec correction narrowing `keep_members` to a scalar boolean. |

## Degraded capabilities

### `listFiles` — generated pagination flows dropped (union response envelope)

| Field | Value |
| --- | --- |
| Owner | openrouter-kotlin-maintainers |
| Reason | As of the 2026-08-29 re-pin `FileListResponse` is a `_shape`-discriminated `oneOf` (`OpenRouterFileList \| OpenAIFileList \| AnthropicFileList`); kotlin-sdkgen 0.3.0 cannot address the `/data` items path on a union envelope. The `/files` `x-sdkgen-pagination` overlay block was removed. |
| User impact | `FilesClient.listFiles(...)` returns the union, but the generated `listFilesPages()`/`listFilesItems()` flows are absent for this one operation. In practice `listFiles` is **further blocked** by the explicit-null matcher defect below: OpenRouter sends `cursor: null` on the terminal page, which fails to decode — so even a single-page listing throws on the last page. |
| Workaround | None shippable at the SDK layer. A curated `listAllFiles(...)` walk was prototyped and **removed**: it cannot decode the terminal `cursor: null` page (see the explicit-null matcher row), so it would drop the last page and throw. `fromRaw(JsonElement)` can accept the raw JSON if a caller issues the request themselves. |
| Expiry | Next kotlin-sdkgen release that (a) paginates over discriminated response envelopes and (b) accepts explicit-null nullable branch fields. |
| 1.0 disposition | Upstream generator support for pagination over discriminated (`oneOf`) response envelopes, plus the explicit-null matcher fix below. |

### Unknown union discriminators throw at decode (forward-compat gap)

| Field | Value |
| --- | --- |
| Owner | openrouter-kotlin-maintainers |
| Reason | kotlin-sdkgen 0.3.0 makes affected unions strict: a payload whose discriminator value matches no known variant throws the union's `NoMatchException` at decode. FR-API-007 asks for preservation of unknown variants (as open enums already do via `SdkUnknown`). |
| User impact | A server that introduces a new discriminated union variant (e.g. a new `FileResponse._shape` or `StreamEvents.type`) causes an SDK decode failure rather than a preserved unknown value. Open enums are unaffected (they round-trip unknown values). The exact exception type is pinned by `OpenEnumAndUnionTest`. |
| Workaround | Issue the request through a lower-level HTTP client and pass the retained `JsonElement` to the union's `fromRaw(JsonElement)` escape hatch (US-009). The generated `NoMatchException` does not retain the rejected payload. |
| Expiry | kotlin-sdkgen release adding a raw-preserving unknown branch for discriminated unions. |
| 1.0 disposition | Upstream: raw-preserving `SdkUnknown` branch for discriminated unions (`docs/upstream/2026-08-29-kotlin-sdkgen-unknown-union-variant-proposal.md`). |

### Multipart uploads cannot set filename or content-type

| Field | Value |
| --- | --- |
| Owner | openrouter-kotlin-maintainers |
| Reason | The generated `uploadFile`/`createAudioTranscriptionsMultipart` codecs hardcode the part name (`"file"`), a fixed `application/octet-stream` content type, and no `Content-Disposition` filename (`multipart.binary(name = "file", stream = …, mediaType = "application/octet-stream", headers = listOf())`). |
| User impact | The curated `FilesClient.upload(...)` / `SttClient.transcribe(...)` cannot surface a `filename` or a per-upload content type — setting them would be faking wire fields the codec ignores. |
| Workaround | None at the SDK layer; the server accepts the octet-stream part. |
| Expiry | kotlin-sdkgen release that derives multipart part filename/content-type from the schema. |
| 1.0 disposition | Upstream: multipart codec support for per-part filename and content type. |

### Discriminated-union matcher rejects explicit-null nullable fields

| Field | Value |
| --- | --- |
| Owner | openrouter-kotlin-maintainers |
| Reason | The generated `oneOf` branch predicates (e.g. `FileListResponse`) require nullable string fields (`cursor`, `first_id`, `last_id`) to be **present and string-typed**; an explicit JSON `null` fails the predicate (`property is JsonPrimitive && property.isString` is false for `JsonNull`), so the whole envelope fails to match any branch and decode throws. |
| User impact | A real response that sends a nullable union field as `null` (e.g. `cursor: null` on the last file page — the shape OpenRouter documents for the terminal page) fails to decode and throws `FileListResponseNoMatchException`. This blocks any automatic file-list walk and even a single-page `listFiles` whose one page is terminal, which is why no curated `listAllFiles` ships. Pinned by `FilesContractTest.terminalFileListPageWithNullCursorFailsToDecode_knownGeneratorDefect`. |
| Workaround | None at the SDK layer for an affected server response; the `fromRaw(JsonElement)` escape hatch can accept the raw JSON. |
| Expiry | kotlin-sdkgen release that treats an explicit null as satisfying a nullable branch field. |
| 1.0 disposition | Upstream: union branch predicates accept explicit null for nullable fields (`docs/upstream/2026-08-29-kotlin-sdkgen-unknown-union-variant-proposal.md`). |

### Enum-typed path parameters render as the enum name, not the wire value

| Field | Value |
| --- | --- |
| Owner | openrouter-kotlin-maintainers |
| Reason | The generated code renders an enum-typed **path** parameter with the enum object's `toString()` (e.g. `Daily`) rather than its wire `value` (`daily`). Observed on `getWorkspaceBudget(id, interval)` → the sent path is `/workspaces/{id}/budgets/Daily`. |
| User impact | `getWorkspaceBudget`, `deleteWorkspaceBudget`, and `upsertWorkspaceBudget` send a wrong path segment (`/budgets/Daily` instead of `/budgets/daily`), so those three operations would 404/400 against the live API. The correct wire value is available on the model (`WorkspaceBudgetInterval.Daily.value == "daily"`). |
| Workaround | None routable through the generated op today (the generated function hardcodes `interval.toString()`; the private executor is not reachable from a curated overload to rebuild the path). The defect is pinned — not asserted as correct — by `ResourceConformanceTest.getWorkspaceBudgetEncodesEnumNameNotWireValue_knownGeneratorDefect`, with the intended behaviour captured in the `@Ignore`-d `getWorkspaceBudgetShouldEncodeLowercaseWireValue`. |
| Expiry | kotlin-sdkgen release that renders enum path parameters by their wire value; then un-ignore the intent test and delete the defect test. |
| 1.0 disposition | Upstream fix (filed in the enum-path section of `docs/upstream/2026-08-29-kotlin-sdkgen-unknown-union-variant-proposal.md`); add a curated string-wire-value overload only if a lower-level request-build seam becomes available. |

## Deferred curated surface

### `client.beta` namespace — no beta resources in the current contract

| Field | Value |
| --- | --- |
| Owner | openrouter-kotlin-maintainers |
| Reason | The 2026-08-29 contract GA'd both Responses and Analytics; the previously beta-tagged `getAnalyticsMeta`/`queryAnalytics` are now on the GA `AnalyticsClient`, and the generator emits **no** beta-tagged resources (no `beta*` package). A generated `client.beta` namespace therefore has no content to wrap. |
| User impact | No `client.beta.*` resource accessors (there is nothing beta to expose). The former beta operations are reachable at `client.analytics.getAnalyticsMeta` / `client.analytics.queryAnalytics`. |
| Workaround | Use `client.analytics` for the analytics-meta/query operations. The `@OpenRouterExperimentalApi` opt-in marker still ships and annotates the pre-1.0 curated helpers (byte streams, pagination bounds, files upload/download, STT). |
| Expiry | When upstream reintroduces a `beta`-tagged resource. |
| 1.0 disposition | Reintroduce `client.beta.*` accessors if/when the contract carries beta resources again; otherwise drop the namespace at 1.0. |

### `AutoCloseable` root — deferred

| Field | Value |
| --- | --- |
| Owner | openrouter-kotlin-maintainers |
| Reason | The curated `OpenRouter` root does not implement `AutoCloseable`; transport lifecycle is owned by the caller-supplied transport. |
| User impact | No `use { }` block over the root; callers close their own Ktor engine/transport. |
| Workaround | Manage the injected transport/engine lifecycle directly. |
| Expiry | Pre-1.0 lifecycle review. |
| 1.0 disposition | Decide root-owned vs. caller-owned transport lifecycle at the 1.0 API review. |

### `RetryPolicy.replayMode` — deferred

| Field | Value |
| --- | --- |
| Owner | openrouter-kotlin-maintainers |
| Reason | Retry replay-mode configuration (how request bodies are replayed on retry) is not surfaced on the curated `RetryPolicy`. |
| User impact | Retry uses the runtime default replay behaviour; no curated knob to change it. |
| Workaround | None needed for the default allowlist (`{429}` + connection failures); advanced replay control is unavailable. |
| Expiry | Pre-1.0 retry review. |
| 1.0 disposition | Surface `replayMode` on `RetryPolicy` if a concrete need appears; otherwise keep the runtime default. |
