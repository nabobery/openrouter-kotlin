# ADR 0002: Support actively maintained Kotlin Multiplatform target families

## Status

Accepted.

## Context

The SDK is valuable precisely where JVM-only Retrofit clients are insufficient. Claiming identical test depth for every
Kotlin target is impractical because targets differ in usage, host availability, and engine maturity.

## Decision

Publish for the actively maintained Kotlin target families that are meaningful for an HTTP client from the first public
release. Use tiered guarantees rather than calling every target equally stable.

- Tier 1 receives compile, contract, transport, and consumer-project validation.
- Tier 2 receives compile, serialization, API, and selected transport validation.
- Tier 3 is experimental and may be compile-only until runtime evidence exists.
- Target promotion and retirement follow documented, evidence-based criteria.
- Common public APIs contain no JVM, Android, Foundation, Node, or browser types.

The current matrix is defined in [target support](../target-support.md).

## Consequences

Release automation is larger and some Native targets require matrix builds. Consumers receive precise guarantees rather
than an ambiguous “KMP supported” claim.

