# Architecture decision records

Architecture decision records capture durable choices and their consequences rather than temporary execution details.

| ADR | Decision |
| --- | --- |
| [0001](./0001-product-scope-and-api-layers.md) | Full client-SDK parity with generated and curated API layers |
| [0002](./0002-kmp-target-family-policy.md) | All actively maintained KMP target families with tiered guarantees |
| [0003](./0003-ktor-and-transport-injection.md) | Ktor integration, consumer-selected engines, and neutral transport injection |
| [0004](./0004-streaming-failures-retries-and-deadlines.md) | Flow streaming, failure semantics, replay-aware retries, and deadlines |
| [0005](./0005-openapi-generation-and-drift.md) | Pinned OpenAPI generation with overlays and tested drift pull requests |
| [0006](./0006-artifacts-and-publication.md) | One primary SDK artifact plus testing support, published through Maven Central |
| [0007](./0007-final-target-tiers-for-1-0.md) | Final 1.0 target-tier assignments with evidence and promotion/retirement triggers |

An accepted ADR may be superseded by a later ADR. Do not silently edit its decision to describe a different design.
