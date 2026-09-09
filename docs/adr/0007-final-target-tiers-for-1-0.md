# ADR 0007: Final target tiers for 1.0

## Status

Accepted. Concretises [ADR 0002](./0002-kmp-target-family-policy.md)'s tiered policy with the specific 1.0 target
assignments and their promotion/retirement triggers. Evidence for each row lives in
[target support](../target-support.md); this ADR is the decision, not the evidence.

## Context

Every declared target needs evidence rather than a compile-only claim. kotlin-sdkgen 0.4.0 unblocked the Android
Tier 1 target, the `runRealTime` harness made the real-Ktor engine lane run on every available host (JS included), and
native runtime lanes run in CI where Kotlin/Native provides a matching host (Linux x64 and Windows on PRs; Intel macOS
nightly). Linux arm64 remains compile + klib ABI coverage because Kotlin/Native has no Linux arm64 host. Every
published target must satisfy its documented tier, and unavailable host tests must be explicitly disclosed.

## Decision

| Tier | Targets | PR evidence | Nightly | Consumer sample | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | `jvm`, `android`, `macosArm64`, `iosSimulatorArm64`, `iosArm64` | compile, JVM + klib ABI, common + real-engine (`engineTest`) suites on the host lanes (`iosArm64` compiles only — device runtime not executed) | — | jvm, android, apple, ios (Swift/XCFramework) | Android **device** tests: explicit limitation (host lane `testAndroidHostTest` only) |
| 2 | `linuxX64`, `linuxArm64`, `mingwX64`, `js` (Node + browser) | compile + klib ABI for all; common + real-engine suites on available native hosts / Node / headless Chrome | — | native-desktop (Linux CIO), native-desktop (Windows WinHttp), js (Node), browser (Js) | Linux arm64 runtime is not executed because no Kotlin/Native Linux arm64 host exists; cross-compiled + ABI-checked on Linux x64 |
| 2 (deprecated upstream) | `macosX64`, `iosX64` | compile, klib ABI | runtime lanes on `macos-15-intel` | — | Deprecated since Kotlin 2.3.20 (still compile); retire when Kotlin removes them; compatibility-policy "target retirement" applies |
| 3 (declared, not published) | `wasmJs` | `scripts/wasm-probe.sh` (expected to fail until the runtime ships wasmJs) | — | — | Blocked upstream: the kotlin-sdkgen runtime publishes no wasmJs variant |
| Not supported | watchOS, tvOS, `androidNative*`, `linuxArm32Hfp`, wasmWasi | — | — | — | No runtime artifacts / no HTTP-client value |

### Promotion / retirement triggers (specific)

- **Windows (`mingwX64`) promotes to Tier 1** when a WinHttp streaming sample runs in CI for three consecutive
  releases.
- **`wasmJs` promotes to Tier 3-tested** the release after the kotlin-sdkgen runtime publishes a `wasmJs` variant of
  `runtime-core` / `testing` / `transport-ktor` (Ktor `CIO` already supports WasmJs): add the target, run
  `wasmJsNodeTest`, wire `scripts/wasm-probe.sh` into the gate.
- **`macosX64` / `iosX64` retire** when Kotlin removes the deprecated Intel Apple targets (the retirement trigger is
  the upstream removal, not a project decision).
- **Android device tests** are added when an emulator lane is justified; until then the JVM-hosted `testAndroidHostTest`
  lane is the disclosed Android runtime evidence.

## Consequences

The published tier of every target is now backed by a CI lane or an explicit "not executed" disclosure, not by
wording. The nightly Intel-macOS and performance lanes keep the deprecated and expensive targets covered without
paying for them on every PR. `docs/target-support.md` mirrors this table with the per-target evidence column.

## Tier truthfulness amendment (2026-09-09): `mingwX64` and `linuxArm64`

The 1.0 review aligns this SDK's Tier 2 wording with **JetBrains' own Kotlin/Native support tiers**: Apple Silicon
is JetBrains Tier 1; `linuxX64` is Tier 2; **`mingwX64` is JetBrains Tier 3** (JetBrains makes no CI or
binary-compatibility promise for it); and **`linuxArm64` has no Kotlin/Native host at all**
([KT-36871](https://youtrack.jetbrains.com/projects/KT/issues/KT-36871)). No target is added or removed. Both
`mingwX64` and `linuxArm64` **remain Tier 2 for this SDK**, but are relabelled **"compile-verified every CI run;
runtime best-effort"** — `mingwX64` runs its runtime lane on Windows CI (above JetBrains' Tier 3 baseline) but
carries no upstream binary-compat guarantee, and `linuxArm64` is compile + klib-ABI only (cross-compiled from
Linux x64). The `mingwX64` → Tier 1 promotion trigger above is unchanged. `wasmJs` stays declared-not-published.
Mirrored in `docs/target-support.md` and the README targets table.
