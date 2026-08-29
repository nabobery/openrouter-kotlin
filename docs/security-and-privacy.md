# Security and privacy

## Assets and threats

| Asset | Threat |
| --- | --- |
| API credentials | Logging, exception leakage, forwarding to an untrusted host |
| Prompts and outputs | Accidental telemetry, unbounded error capture, test artifacts |
| Attribution metadata | Incorrect inheritance or unexpected disclosure |
| Build inputs | Compromised spec, dependency, plugin, or generated source |
| Published artifacts | Tampering, wrong coordinates, unsigned or unreproducible release |

## Trust model

```mermaid
flowchart LR
    Spec["External OpenAPI"] --> Validate["Digest + parser + overlay validation"]
    App["Consumer inputs"] --> Boundary["Local validation"]
    Boundary --> Request["Immutable request"]
    Request --> Host{"Trusted host?"}
    Host -- Yes --> Auth["Attach credential"]
    Host -- No --> Deny["Reject or omit auth"]
    Auth --> API["OpenRouter"]
    API --> Decode["Bounded typed decoding"]
    Decode --> Redact["Redact observations"]
```

## Credential requirements

- Store credential values in a redacting `Secret` abstraction.
- Resolve dynamic credentials once per physical attempt.
- Never include secret values in equality diagnostics, `toString`, exception messages, test snapshots, or observers.
- Apply credentials only after trusted-host validation.
- Clear references as soon as practical; do not claim guaranteed memory erasure on managed runtimes.
- Environment lookup is explicit and restricted to appropriate targets.
- Per-request credentials inherit all host and redaction protections.

## Header safety

Generic header injection is an escape hatch. Protect, case-insensitively:

- `Authorization`.
- `Host`.
- `Content-Length`.
- SDK-controlled content type/accept headers.
- SDK user-agent/version headers where overriding would break diagnostics.

Typed attribution is separate from generic headers and supports inherit, replace, and clear.

## Redirects and pagination

Redirects and absolute next-page URLs are untrusted input. Default behavior must never forward authentication outside
the configured trusted-host set. Scheme downgrade is rejected. Host comparison uses parsed normalized URLs, not string
prefixes.

## Logging and observability

There is no logging or telemetry by default. Opt-in observations:

- Exclude bodies and credentials by default.
- Use allowlisted metadata rather than denylisting known secrets.
- Bound string, header, and body previews.
- Distinguish logical calls from physical attempts.
- Do not let observer failure change request results.
- The curated `TransferObserver` (per-call `options { transferObserver(...) }`) reports **byte counts only**
  (`direction`, `bytesTransferred`, `totalBytes`) — never the transferred bytes themselves — for upload/download
  progress on the multipart and binary media operations.

## Binary bodies

- `readAllBytes(maxBytes)` and the curated `downloadBytes(...)` are **bounded** — a payload exceeding `maxBytes`
  throws `SdkBufferLimitExceededException` and the stream is closed, so a hostile or mis-sized response cannot
  exhaust memory. The default bound is 64 MiB (`DEFAULT_MAX_DOWNLOAD_BYTES`); use `downloadFileContent(...).asFlow()`
  to stream large files without buffering. Byte streams are one-shot and always closed with their close-cause.

Debug facilities must carry explicit warnings and remain safe enough that enabling them does not reveal API keys.

## Response handling

Treat all remote data as untrusted:

- Select response alternatives by status and normalized content type.
- Bound unknown-body previews.
- Validate pagination URLs.
- Preserve raw unknown JSON without executing or interpreting it.
- Do not place server error text into logs without redaction and bounds.
- Close bodies exactly once.

## Streaming privacy

Streaming diagnostics retain only a bounded trailing window of already processed data. No facility records a complete
prompt or response by default. Cancellation immediately stops further consumption.

## Supply-chain controls

- Pin the OpenRouter spec by SHA-256.
- Pin generator and build plugin versions.
- Use Gradle dependency verification and lock files where appropriate.
- Review overlays and generated diffs.
- Run secret scanning and dependency vulnerability analysis.
- Generate an SBOM.
- Sign Maven publications.
- Produce build provenance/attestation.
- Publish only from protected workflows and immutable tags.
- Separate drift PR permissions from release permissions.

## CI permissions

Default workflow permission is `contents: read`. Drift creation receives narrow pull-request/content write permission
through a GitHub App or fine-grained token. Release credentials are available only to the protected release environment.
Pull requests from forks never receive secrets.

## Data handling statement

The SDK is a local client library:

- It does not operate a backend.
- It does not persist prompts, responses, or credentials.
- It sends caller-provided data to the configured OpenRouter endpoint.
- Observability callbacks execute inside the consumer application.

Documentation must not imply that selecting ZDR or provider data policies guarantees behavior beyond OpenRouter's
documented server contract.

## Incident response

1. Privately report vulnerabilities through the repository security policy.
2. Assess credential, artifact, and behavioral impact.
3. Revoke affected release or automation credentials.
4. Patch supported release lines.
5. Publish an advisory and migration instructions.
6. Rotate signing/release material if compromise is possible.
7. Record the corrective control and regression test.

