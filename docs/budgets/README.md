# Budgets

Measured baselines for what a consumer downloads and how the SDK builds, each a JSON file gated in CI. Budgets are
**ceilings against regression, not performance targets**: a gate fails when a measured value exceeds its baseline by
more than the file's tolerance, when a new artifact appears that the baseline never accepted, or when a baseline
artifact is **missing** from the measurement (so an artifact can never silently disappear past the gate — an
intentional drop is named in `--allow-removed` or the baseline is re-recorded). A deliberate change is landed by
re-recording the baseline *in the same commit that explains why the number moved* — the same discipline as the
coverage dashboard. The checker and its tests are `scripts/budgets.py` / `scripts/budgets_test.py`.

| File | What it measures | Tolerance | How | Gate |
| --- | --- | --- | --- | --- |
| `artifact-sizes.json` | Per-target published artifact sizes (jar / klib / aar / metadata), version-stripped | +10% | `scripts/measure-artifacts.sh` (publishToMavenLocal into a throwaway repo) | **gated** on `build-apple` (full set, Android forced on) |
| `compile-times.json` | JVM Kotlin compile-task duration (ms) | +50% (noisy) | `scripts/measure-compile.sh` (Kotlin build reports) | **gated** on `build-linux`; re-record from CI when its hardware baseline is established |
| `warnings.json` | Kotlin compiler-warning count (JVM generated compile) | 0 (may only shrink) | `scripts/measure-compile.sh` | **gated** on `build-linux` (the hard Task-13 gate) |
| `runtime.json` | First-event latency, 200-event decode time, stream allocation/event, other allocation/op | +100% (noisy hosts; allocation is host-stable) | `benchmarks/` (kotlinx-benchmark) + JMH `-prof gc`, folded via `scripts/bench-to-runtime.py` | **gated** on `perf.yml` (nightly) |

## Artifact sizes

The current sizes are large — the JVM jar is ~27 MB, each native klib ~17 MB, the android aar ~24 MB — because the
generated surface is ~1,850 files (100 of 101 operations, every request/response/model type). **This baseline is the
budget; it is recorded, not judged.** Reductions are an upstream concern (generation-time dead-code pruning of
unreferenced envelope models and smaller union carriers). Future work may reduce them. Until then the gate only
catches an *unintended* size jump (e.g. a generator change that
doubles output).

The macOS job measures and gates the full set: it publishes every host-buildable target, the Apple klibs, **and**
the Android aar (the gate runs `measure-artifacts.sh -Popenrouter.androidTarget=true`, since macos-15 ships an
Android SDK). Because the checker now fails on a missing baseline artifact, the measured set must be complete — a
partial run (e.g. one that omitted the aar) fails the gate instead of silently passing. `--merge` remains available
to fold a separately-measured set (e.g. a Linux run) into the measurement when a single host cannot build them all.

> **2026-09-02 — added the `openrouter-kotlin-testing-*` keys** for the new companion test-kit module (its target
> matrix mirrors the SDK). The artifacts are small (JVM jar ~8 KB, each klib ~13 KB, aar ~8 KB) — it is a thin
> handwritten facade over `kotlin-sdkgen-testing`, which the consumer resolves separately. The SDK keys were
> re-measured in the same run and unchanged.
>
> **2026-09-02 — keys renamed to the ADR 0006 artifactIds** (`sdk-*` → `openrouter-kotlin-*`). Values were
> re-recorded from a fresh macOS-host measurement in the same change. The rename is a publication-time artifactId
> rewrite and cannot alter compiled artifact contents (the `-metadata.jar` values are byte-identical across the
> rename, and two consecutive measurements are byte-identical — the build is deterministic); the small deltas from
> the prior baseline are host/packaging variance predating this change, not a content regression.
