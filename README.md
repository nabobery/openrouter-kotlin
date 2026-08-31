# OpenRouter Kotlin

A Kotlin Multiplatform SDK for the [OpenRouter](https://openrouter.ai) API.

The client surface (100 of 101 operations of the 2026-08-30 contract; one accepted waiver) is
**generated** by the [`kotlin-sdkgen`](https://github.com/nabobery/kotlin-sdkgen) `0.4.0` Gradle
plugin from the OpenAPI spec pinned in [`spec/`](spec/) — it is not hand-written. Generation is
deterministic and reproducible from a clean clone. Responses are GA (`client.responses`); the
new `containers`, `scim`, `datasets.getSessionCost`, and `workspaces.getWorkspaceBudget`
operations are covered. See [`docs/coverage/`](docs/coverage/) for the coverage dashboard and
exception register, and the compile-checked [guides](docs/guides/README.md) (tutorials and how-tos) to get started.

> **Status: release-candidate preparation.** On top of the generated surface, a curated inference facade
> (chat / responses / messages) plus incremental SSE streaming is now callable end-to-end. It
> is exercised through a fake transport and the real Ktor `MockEngine` SSE lane on **every host test lane**
> (`engineTest` via the `runRealTime` harness): JVM, JS (Node + headless Chrome), macOS arm64, and the iOS
> simulator (`iosSimulatorArm64`) all run the common **and** real-engine suites in CI. Android — a Tier 1 target per
> [`docs/target-support.md`](docs/target-support.md) — is now a live `:sdk` target
> (`com.android.kotlin.multiplatform.library`) whose common suites run on the JVM-hosted
> `testAndroidHostTest` lane (its sample builds only when an Android SDK is present). The full generated
> surface — including the exact `/messages` and `/responses` operations — is present and callable
> (kotlin-sdkgen 0.4.0). The project is **not yet published**: no Maven coordinates exist, and
> publication remains future work.

## How it works

The published `kotlin-sdkgen` plugin generates a content-addressed source snapshot under
`sdk/build/generated/` and wires it into `commonMain` automatically. Generated sources are
**not** checked in; instead the spec is pinned by digest ([`spec/pin.json`](spec/pin.json)),
the generation config is committed ([`spec/sdkgen.yaml`](spec/sdkgen.yaml)), and the expected
output is pinned by content-address ([`spec/generated.lock.json`](spec/generated.lock.json)).
Reproducibility is enforced by the drift gate below.

Two spec overlays repair generation metadata (see
[`docs/spec-sync-and-release.md`](docs/spec-sync-and-release.md)). Each `text/event-stream`
response declares `x-sdkgen-streaming.payloadProperty: data` in
[`spec/overlays/full-spec-compat.yaml`](spec/overlays/full-spec-compat.yaml), so kotlin-sdkgen
0.4.0 projects each `data:` field to the envelope's payload type natively — OpenRouter sends the
payload directly rather than wrapped in `{ "data": … }`. This retired the earlier
`sse-payload.yaml` schema-rewrite overlay.

## Requirements

- **JDK 25** to build (Gradle toolchain). The SDK itself is compiled to **JVM 17 bytecode**,
  so JVM consumers only need JDK 17+.
- Gradle is provided via the wrapper (`./gradlew`, Gradle 9.6.1).

## Quickstart

The SDK never bundles an HTTP engine — you inject a Ktor `HttpClient` (here CIO on the JVM).
The curated inference calls live in the `com.nabobery.openrouter.chat` package as extensions on
the generated `client.chat`:

```kotlin
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.chat.contentDeltas
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.stream
import com.nabobery.openrouter.chat.userMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    HttpClient(CIO).use { http ->
        val client = OpenRouter(
            credential = OpenRouterCredentials.static(System.getenv("OPENROUTER_API_KEY")),
            httpClient = http,
        )

        // Non-streaming completion. Client defaults (retry, deadlines, attribution) apply automatically;
        // `options { ... }` is only needed for per-call overrides or pagination bounds.
        val result = client.chat.send(
            model = "openrouter/free",
            messages = listOf(userMessage("Say hello in one sentence.")),
        )
        println(result.choices.firstOrNull()?.message?.content?.raw)

        // Streaming: cold Flow of text deltas.
        client.chat
            .stream(
                model = "openrouter/free",
                messages = listOf(userMessage("Explain structured concurrency in three sentences.")),
            )
            .contentDeltas()
            .collect { delta -> print(delta) }
        println()
    }
}
```

`stream(...)` returns a `Flow<ChatStreamEvent>` (variants `Chunk` and `Error` — no `Done`; Flow
completion is the terminal signal). `contentDeltas()` projects the assistant text deltas.
Streaming is **never retried** (an opened stream cannot be transparently restarted), so a
pre-first-byte 429 surfaces immediately as a typed `ApiException`.

## Samples

The JVM, JS, and Apple samples run a non-streaming `send`, then consume a streaming completion with
`contentDeltas()`. The JVM sample additionally demonstrates a stream-idle deadline and an early cancel;
the Android sample focuses on lifecycle-aware streaming into a `TextView`. See
[`samples/README.md`](samples/README.md).

| Sample | Engine | Run task |
| --- | --- | --- |
| `samples/jvm` | CIO | `./gradlew :samples:jvm:run` |
| `samples/js` | Node.js/Js | `./gradlew :samples:js:jsNodeDevelopmentRun` |
| `samples/apple` | Darwin (macOS-native) | `./gradlew :samples:apple:runDebugExecutableMacosArm64` |
| `samples/android` | OkHttp (streams into a `TextView`; consumes the android variant) | `./gradlew :samples:android:compileAndroidMain` |

JVM, JS, and Apple are always built and compile in CI; `samples/android` is included only when
an Android SDK is present. `./gradlew samplesCheck` compiles them (macOS host).

## Live tests

An opt-in live chat smoke test hits the real OpenRouter API. It is gated on two environment
variables and runs nightly ([`.github/workflows/live.yml`](.github/workflows/live.yml)):

```bash
OPENROUTER_LIVE_TESTS=1 OPENROUTER_API_KEY=… ./gradlew :sdk:jvmTest
```

Without both variables the live test is skipped; ordinary CI never needs network access.

## Common tasks

```bash
# Regenerate the SDK from the pinned spec
./gradlew :sdk:generateOpenrouterSdk

# Verify regeneration reproduces the committed baseline (drift gate)
./scripts/check-drift.sh

# Refresh the operation coverage dashboard (CI gates its freshness)
python3 scripts/coverage-dashboard.py

# Refresh the pinned spec from live upstream (future drift automation can reuse this)
bash scripts/fetch-upstream-spec.sh

# Re-pin end-to-end from live upstream (fetch → re-pin → regenerate twice → re-baseline)
bash scripts/drift-refresh.sh

# Layered compatibility report of the current branch vs a base ref (classifies patch/minor/breaking)
bash scripts/compat-snapshot.sh --base-ref origin/main --report build/compat/report.md

# Refresh the compiled-guide snippets after editing an example (kt -> md), then check freshness
python3 scripts/docs-snippets.py update && python3 scripts/docs-snippets.py check

# Complete verification gate (host-safe): compile every declared target, run the JVM + macOS
# test lanes, and check the public API baseline. This is what CI enforces; run it on
# a macOS host (it drives the Apple compile/test lanes).
./gradlew :sdk:verificationCheck

# Individual lanes, if you want them piecemeal:
./gradlew :sdk:jvmTest          # JVM fake-transport smoke test
./gradlew :sdk:macosArm64Test   # macOS native smoke test
./gradlew :sdk:apiCheck         # binary-compatibility (public API) check — JVM ABI
```

> `verificationCheck` is the portable completion command: it is **host-aware**, running only the runtime lanes the
> current host can execute (e.g. `macosArm64Test` + `iosSimulatorArm64Test` on an arm Mac, `mingwX64Test` on Windows)
> while compiling every host-buildable target. The JS browser lane needs a browser and the iOS simulator lane needs
> an installed simulator runtime, so those are enrolled in CI on runners that have them.

## Target support

Full policy in [`docs/target-support.md`](docs/target-support.md). The scaffold currently
exercises:

Final 1.0 tiers are decided in [ADR 0007](docs/adr/0007-final-target-tiers-for-1-0.md); the evidence matrix (with a
per-target column) is [`docs/target-support.md`](docs/target-support.md).

| Tier | Targets | Runtime evidence |
| --- | --- | --- |
| 1 | `jvm`, `android`, `macosArm64`, `iosSimulatorArm64`, `iosArm64` | Common + real-engine suites on JVM / android host / macOS / iOS simulator (PR CI); `iosArm64` device and Android device tests not executed (disclosed) |
| 2 | `linuxX64`, `linuxArm64`, `mingwX64`, `js` (Node + browser) | Common + real-engine suites on Linux x64/arm64, Windows, Node.js, and headless Chrome (PR CI) |
| 2 (deprecated) | `macosX64`, `iosX64` | Compile + klib ABI on PRs; runtime lanes nightly on `macos-15-intel` (deprecated upstream since Kotlin 2.3.20) |
| 3 | `wasmJs` | Declared, not published — blocked until the kotlin-sdkgen runtime ships a wasmJs variant (`scripts/wasm-probe.sh`) |

Every runtime lane runs the common **and** real-Ktor `engineTest` suites via the `runRealTime` harness. Only lanes CI
runs are called "verified"; every non-executed lane is disclosed in the target-support matrix.

## Layout

```
spec/        Pinned OpenAPI spec, overlays, generation config, and provenance/drift pins
sdk/         The :sdk Kotlin Multiplatform module (generated sources land under build/)
scripts/     check-drift.sh — the generation drift gate
docs/        Design documentation and ADRs
```

## Contributing and security

- [CONTRIBUTING.md](CONTRIBUTING.md) — build prerequisites, the verification gate, the
  "never hand-edit generated sources" rule, and commit conventions.
- [SECURITY.md](SECURITY.md) — how to report a vulnerability privately, supported versions,
  and the incident-response procedure.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — Contributor Covenant 2.1.

## License

See [LICENSE](LICENSE).
