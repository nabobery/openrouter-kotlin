# Threat model

A STRIDE threat model for the openrouter-kotlin SDK and its build/CI supply chain. Each threat names the actor,
the vector, the current control, the evidence, the residual risk, and a disposition
(`implemented | planned | gap`). Future publication controls are listed as **planned**.

## System summary

openrouter-kotlin is a Kotlin Multiplatform client **generated** from a digest-pinned OpenRouter OpenAPI
document by kotlin-sdkgen, plus a small hand-written curated facade. At runtime it constructs HTTPS requests to
OpenRouter, carrying an API key, and parses responses (including SSE streams and paginated lists). The consumer
supplies the Ktor `HttpClient` (engine) and owns its lifecycle. At build time the spec is fetched, pinned by
sha256, normalized with digest-pinned overlays, and regenerated deterministically; CI automation opens drift PRs
through a privilege split.

## Assets

- The consumer's **OpenRouter API key** (and any per-request credential override).
- **Request integrity** (base URL, headers, host trust) and **response integrity** (typed decoding, bounded
  buffers).
- The **pinned contract** (`spec/`), the **generated surface**, and the **published artifacts** (planned).
- **CI credentials** (`OPENROUTER_API_KEY`, optional drift App key, `GITHUB_TOKEN`).

## Trust boundaries

- **B1** consumer app ↔ SDK inputs (credentials, base URL, headers, observers, engine).
- **B2** SDK ↔ OpenRouter over HTTPS (responses, redirects, pagination URLs, SSE bytes).
- **B3** build-time spec/overlay acquisition and generation.
- **B4** CI automation (drift/parity/docs jobs, tokens, artifacts).
- **B5** publication.

---

## B1 — consumer ↔ SDK inputs

### Spoofing / tampering — a per-request credential or header override reaches an untrusted host
- **Vector:** a caller overrides the credential or sets a reserved header for a request whose URL points at a
  foreign host (or an absolute pagination/redirect link does).
- **Control:** credentials are only attached to hosts in the trusted set (`TrustedHosts.of(baseUrl,
  extraTrustedOrigins)`; opt in additional hosts with `trustOrigin(...)`); reserved/attribution headers are
  centralized and validated rather than free-form.
- **Evidence:** `OpenRouterRoot.kt` (`trustOrigin`, `TrustedHosts.of`), `ReservedHeaders.kt`, `AttributionTest`.
- **Residual:** the trusted-host set is only as tight as the caller configures it. **Status: implemented.**

### Information disclosure — the credential appears in an SDK-owned representation
- **Vector:** a log, `toString()`, lifecycle event, or exception echoes the key.
- **Control:** the credential type never renders its secret; lifecycle observers receive redacted request
  representations; the root/builder `toString()` carry no key.
- **Evidence:** `OpenRouterCredentialsTest.secretNeverAppearsInToString`, `LifecycleContractTest` redaction rows,
  and `SecretIsolationTest`. **Status: implemented.**

### Denial of service — an unbounded body/stream exhausts memory
- **Control:** downloads and previews are bounded (`DEFAULT_MAX_DOWNLOAD_BYTES` = 64 MiB;
  `readAllBytes(maxBytes)`), and stream idle/attempt/pagination budgets exist (`TimeoutPhase`).
- **Evidence:** `io/ByteStreams.kt`, `ByteStreamsTest`. **Status: implemented.**

### Elevation — advanced hooks bypass the safe facade
- **Control:** middleware/request hooks are **intentionally not surfaced** by the curated facade and documented as
  advanced/dangerous.
- **Evidence:** `OpenRouterRoot.kt` (advanced hooks note), `docs/public-api-design.md`. **Status: implemented.**

## B2 — SDK ↔ OpenRouter (HTTPS)

### Tampering / spoofing — an absolute pagination or redirect link points at another host
- **Vector:** a hostile or compromised response returns an absolute `next` link on a foreign host; following it
  with the credential would leak it.
- **Control:** host trust is enforced for credential attachment (`TrustedHosts`); a paginated walk to a foreign
  host either stops or carries no `Authorization`.
- **Evidence:** `SecretIsolationTest` foreign-next-link row, which pins the runtime's actual behaviour.
  **Status: implemented.**

### Information disclosure (accepted residual) — a server echoes the key into a body preview
- **Vector:** an untrusted/compromised server includes the API key in an error body; the SDK's bounded preview
  then contains a prefix of it.
- **Control:** body previews are **bounded** (so at most a bounded prefix is retained); no credential-aware
  redaction of *server-supplied* bytes exists or is planned.
- **Evidence:** `SecretIsolationTest` asserts the preview byte bound (not absence); `docs/security-and-privacy.md`
  documents the preview bound. **Status: accepted residual** (server-supplied bytes are out of scope; the bound
  is the mitigation).

### Denial of service — hostile SSE framing or malformed error bodies
- **Control:** SSE framing is validated against a framing matrix with byte budgets; malformed error bodies decode
  to a bounded `SdkSerializationException`.
- **Evidence:** streaming framing/lifecycle tests (`ChatStreamingLifecycleTest`, engine streaming tests),
  `ByteStreamsTest`. **Status: implemented.**

### Spoofing — TLS scheme downgrade
- **Control:** requests target the pinned `https://` base URL; host trust does not extend to `http`.
- **Status: implemented** (transport is HTTPS; the engine enforces TLS).

## B3 — build-time spec/overlay acquisition and generation

### Tampering — the upstream spec or an overlay is swapped
- **Control:** the source is pinned by sha256 (`spec/pin.json` + `spec/sdkgen.yaml`, enforced by the generator),
  acquisition is `offline: true` with an empty `allowedHosts`, overlays are digest-pinned with
  `zeroMatchPolicy`/`conflictPolicy: fail`, and generation is gated for byte-determinism.
- **Evidence:** `spec/sdkgen.yaml`, `scripts/check-drift.sh`, the re-pin log. **Status: implemented.**
- **Residual:** overlay content is human-reviewed; a re-derived audit digest is a spec-maintenance decision
  (recorded in the re-pin log). **Status: accepted residual.**

## B4 — CI automation

### Elevation / untrusted code execution — a drift PR runs attacker-influenced code with write access
- **Control:** a **privilege split** — `detect` is unprivileged (`contents: read`, no secrets) and emits only a
  data patch + report; `open-pr` is the only write-capable job and **executes nothing from the artifact** except
  the repo-owned patch validator, applying the patch to a *temporary index* with a `100644`-only check before
  checkout. A fail-closed workflow auditor enforces SHA pins, least privilege, the secret allowlist, and the
  write-job execution ban on every push.
- **Evidence:** `.github/workflows/drift.yml`, `scripts/validate-drift-patch.py`, `scripts/workflow-audit.py`
  (+ tests), `docs/security/workflow-policy.json`. **Status: implemented.**
- **Residual:** `git apply` can still create files inside allowlisted directories; the structural validator
  rejects binary/mode/rename/symlink changes, and PR CI re-validates. **Status: implemented (defence in depth).**

### Information disclosure — a fork PR obtains a secret
- **Control:** fork PRs run with a read-only `GITHUB_TOKEN` and no repository secrets; every checkout sets
  `persist-credentials: false`; secrets are allowlisted per job.
- **Evidence:** `docs/security/secret-isolation-report.md` (generated table), the auditor's fork-safe column.
  **Status: implemented.**

## B5 — publication

- Artifact signing (in-memory PGP), a CycloneDX 1.6 SBOM, and SLSA build provenance + SBOM attestation are
  **implemented** (`gradle/openrouter-publication.gradle.kts`, `publication/sbom/`, `.github/workflows/release.yml`).
- The release workflow is privilege-split into four jobs (`validate` → `verify` → `stage-and-publish` →
  `github-release`), secret-isolated from the drift/PR jobs: `GPG_SIGNING_KEY` / `GPG_SIGNING_PASSPHRASE` /
  `MAVEN_CENTRAL_*` are readable only by `stage-and-publish` (gated behind the `maven-central` environment with
  required reviewers), which holds no `contents: write`; the `github-release` job holds `contents: write` but runs
  only a single verbatim-allowlisted `gh release create` (enforced by `scripts/workflow-audit.py`, rule e). Upload is
  `USER_MANAGED` — a human validates and publishes. Provenance is verified with
  `gh attestation verify central-bundle.zip --repo nabobery/openrouter-kotlin`. **Status: implemented.**

## Out of scope

- Vulnerabilities in the OpenRouter service itself.
- Content an untrusted server chooses to echo into a bounded response-body preview (accepted residual above).
- Compromise of the developer's machine or of GitHub/Maven Central infrastructure.
