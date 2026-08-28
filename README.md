# OpenRouter Kotlin

A Kotlin Multiplatform SDK for the [OpenRouter](https://openrouter.ai) API.

The client surface (89 operations) is **generated** by the
[`kotlin-sdkgen`](https://github.com/nabobery/kotlin-sdkgen) `0.3.0` Gradle plugin from the
OpenAPI spec pinned in [`spec/`](spec/) — it is not hand-written. Generation is deterministic
and reproducible from a clean clone.

> **Status: development scaffold.** The generated surface compiles across the declared
> non-Android targets below and is callable end-to-end through a fake transport on JVM and
> macOS. Android — a Tier 1 target per [`docs/target-support.md`](docs/target-support.md) — is
> **deferred** because of a plugin/AGP incompatibility. The project is **not yet
> published** — no Maven coordinates exist. A curated ergonomic facade, a real HTTP transport
> wiring, and publication remain future work.

## How it works

The published `kotlin-sdkgen` plugin generates a content-addressed source snapshot under
`sdk/build/generated/` and wires it into `commonMain` automatically. Generated sources are
**not** checked in; instead the spec is pinned by digest ([`spec/pin.json`](spec/pin.json)),
the generation config is committed ([`spec/sdkgen.yaml`](spec/sdkgen.yaml)), and the expected
output is pinned by content-address ([`spec/generated.lock.json`](spec/generated.lock.json)).
Reproducibility is enforced by the drift gate below.

## Requirements

- **JDK 25** to build (Gradle toolchain). The SDK itself is compiled to **JVM 17 bytecode**,
  so JVM consumers only need JDK 17+.
- Gradle is provided via the wrapper (`./gradlew`, Gradle 9.6.1).

## Common tasks

```bash
# Regenerate the SDK from the pinned spec
./gradlew :sdk:generateOpenrouterSdk

# Verify regeneration reproduces the committed baseline (drift gate)
./scripts/check-drift.sh

# Complete verification gate (host-safe): compile every declared target, run the JVM + macOS
# test lanes, and check the public API baseline. This is what CI enforces; run it on
# a macOS host (it drives the Apple compile/test lanes).
./gradlew :sdk:verificationCheck

# Individual lanes, if you want them piecemeal:
./gradlew :sdk:jvmTest          # JVM fake-transport smoke test
./gradlew :sdk:macosArm64Test   # macOS native smoke test
./gradlew :sdk:apiCheck         # binary-compatibility (public API) check — JVM ABI
```

> `./gradlew build` is **not** the portable verification gate: it additionally enrolls host-dependent
> lanes (the iOS simulator test, the JS browser test) that need a simulator/browser the
> build host may not have. The iOS and non-host macOS targets are configured compile-only,
> so `verificationCheck` is the portable completion command.

## Target support

Full policy in [`docs/target-support.md`](docs/target-support.md). The scaffold currently
exercises:

| Target family | Current status |
| --- | --- |
| JVM | Compiles; smoke test runs and passes (CI-verified) |
| macOS (`macosArm64`) | Compiles; smoke test runs and passes (CI-verified) |
| iOS (`iosArm64`, `iosSimulatorArm64`, `iosX64`) | Compiles, compile-only (CI-verified via `verificationCheck`) |
| macOS (`macosX64`) | Compiles, compile-only (CI-verified via `verificationCheck`); deprecated in Kotlin 2.3.20 (not yet removed), tracked as a follow-up |
| JS (Node.js + browser) | Compiles (CI-verified) |
| Android | Deferred — blocked by a plugin/AGP incompatibility |
| Tier 2 (`linuxX64`, `linuxArm64`, `mingwX64`) | Deferred until CI covers them |

Only lanes CI runs are called "verified" — the Apple compiles are enforced by the
`verificationCheck` gate on the macOS CI job.

## Layout

```
spec/        Pinned OpenAPI spec, overlays, generation config, and provenance/drift pins
sdk/         The :sdk Kotlin Multiplatform module (generated sources land under build/)
scripts/     check-drift.sh — the generation drift gate
docs/        Design documentation and ADRs
```

## License

See [LICENSE](LICENSE).
