# ADR 0001: Build a complete client SDK with generated and curated public APIs

## Status

Accepted.

## Context

OpenRouter's official client SDKs are thin, type-safe clients generated from its OpenAPI contract. Generic
OpenAI-compatible Kotlin clients do not provide complete OpenRouter typing or endpoint coverage. Raw generated Kotlin
alone would provide coverage but expose schema-shaped naming and complex presence types to routine callers.

## Decision

- Version 1.0 targets full parity with the pinned OpenRouter client API.
- Agent loops, automatic tool execution, and managed conversation state are outside the client SDK.
- The generated API is complete, public, and SemVer-governed.
- A curated Kotlin API provides idiomatic overloads, DSLs, `Flow`, and simplified common cases.
- Both layers use the same immutable request and response types.
- Generated resources and curated overloads live on one concrete `OpenRouter` root. There is no `.raw` namespace.
- Curated APIs receive stronger deprecation guarantees; generated APIs may change when an upstream breaking change
  requires it, following the compatibility policy.

## Consequences

Consumers can choose exact contract parity or Kotlin ergonomics without maintaining two clients. The project must test
both layers and explicitly classify upstream-generated breaks.

