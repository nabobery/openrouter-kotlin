# Compatibility and versioning policy

## Scope

This policy covers public Kotlin source, JVM binary API, KLIB API, serialization, runtime behavior, target availability,
and Maven coordinates.

## Stability levels

| Level | Guarantee |
| --- | --- |
| Internal | No compatibility guarantee |
| Experimental | Opt-in required; may change in minor releases with notes |
| Beta/generated pre-1.0 | May change; changes are classified and documented |
| Stable curated | SemVer and deprecation policy apply |
| Stable generated | SemVer applies, subject to explicitly classified upstream contract breaks |

## Versioning

- `0.x`: active design and external validation; breaking changes are allowed but documented.
- `1.0`: complete pinned API parity, stable curated surface, target matrix, drift automation, and Central release gates.
- Major: removal or incompatible stable source/binary/behavior change.
- Minor: additive endpoints, models, overloads, target promotions, and deprecations.
- Patch: compatible fixes, documentation, and safe spec corrections.

All target variants and the KMP root use one version.

## Two-layer policy

The curated API receives the strongest guarantees: deprecate before removal and provide migration guidance. Generated
APIs are also public and checked, but an upstream OpenRouter breaking change may force a generated break. Such a change
must be:

1. Detected in semantic and Kotlin API diffs.
2. Isolated from unrelated curated changes.
3. Described in release notes.
4. Bridged with aliases/overloads when feasible.
5. Released as a major version after 1.0 unless retaining compatibility is impossible for security or correctness.

## Behavioral contracts

Observable behavior includes serialization, header precedence, retry defaults, ordering, pagination request count,
exception types, cancellation, and resource ownership. Error-message prose, debug formatting, and internal generated
filenames are not stable contracts unless documented otherwise.

## Deprecation

- Add a replacement before deprecating where possible.
- Use Kotlin `@Deprecated` with `ReplaceWith` when mechanically safe.
- Keep a stable deprecated symbol for at least one minor release and normally six months.
- Do not silently repurpose a field or method.
- Document wire deprecations separately from Kotlin API deprecations.

## Open enums and unknown fields

Adding a server enum value must not break decoding. Open enums preserve unknown raw values. Unknown JSON fields are
ignored or preserved according to the model contract. Turning an open enum into a closed enum is breaking.

## Target changes

Target promotion is additive. Target retirement follows [target support](./target-support.md) and is a breaking platform
change even if common source API is unchanged.

## Compatibility report

Every generated update and release classifies:

- OpenAPI source diff.
- Normalized semantic diff.
- Generated Kotlin source diff.
- JVM and KLIB API/ABI diff.
- Serialization/wire diff.
- Runtime behavior/default diff.
- Target/publication diff.

Unclassified changes fail the release.

