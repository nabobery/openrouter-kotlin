# ADR 0003: Use Ktor integration with consumer-selected engines and transport injection

## Status

Accepted.

## Context

Ktor supports the required KMP targets, streaming, cancellation, retries, and testing. Bundling target engines would
impose transitive dependencies and engine opinions. Some advanced consumers also need a transport seam that is not tied
to Ktor.

## Decision

- Ktor is the only built-in HTTP integration family.
- `openrouter-kotlin` does not bundle opinionated target engines.
- Consumers supply a configured Ktor `HttpClient` with an engine for their target.
- The SDK never mutates or closes a consumer-owned `HttpClient`.
- A neutral `SdkTransport` can be injected for specialized runtimes and testing.
- Generated/public common APIs do not expose Ktor request or response types.
- Transport capabilities are explicit; unsupported features fail before I/O.

## Consequences

Consumers control TLS, proxies, logging, and engine versions. Setup requires adding a target engine dependency. The
neutral seam earns its depth through Ktor, fake/testing, and specialized adapters rather than being a hypothetical seam.

