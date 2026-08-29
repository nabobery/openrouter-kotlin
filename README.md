# OpenRouter Kotlin

A Kotlin Multiplatform SDK for the [OpenRouter](https://openrouter.ai) API.

The client surface (100 of 101 operations of the 2026-08-29 contract; one accepted waiver) is
**generated** by the [`kotlin-sdkgen`](https://github.com/nabobery/kotlin-sdkgen) `0.3.0` Gradle
plugin from the OpenAPI spec pinned in [`spec/`](spec/) — it is not hand-written. Generation is
deterministic and reproducible from a clean clone. Responses are GA (`client.responses`); the
new `containers`, `scim`, `datasets.getSessionCost`, and `workspaces.getWorkspaceBudget`
operations are covered. See [`docs/coverage/`](docs/coverage/) for the coverage dashboard and
exception register.

> **Status: coverage beta.** On top of the generated surface, a curated inference facade
> (chat / responses / messages) plus incremental SSE streaming is now callable end-to-end. It
> is exercised through a fake transport and the real Ktor `MockEngine` SSE lane on JVM and
> macOS (`engineTest` source set); JS and iOS remain **compile-only** (no runtime streaming
> suite runs there). Android — a Tier 1 target per
> [`docs/target-support.md`](docs/target-support.md) — is **deferred** because of a plugin/AGP
> incompatibility (its sample builds only when an Android SDK is present). The full generated
> surface — including the exact `/messages` and `/responses` operations — is present and callable
> (kotlin-sdkgen 0.3.0). The project is **not yet published**: no Maven coordinates exist, and
> publication remains future work.

## How it works

The published `kotlin-sdkgen` plugin generates a content-addressed source snapshot under
`sdk/build/generated/` and wires it into `commonMain` automatically. Generated sources are
**not** checked in; instead the spec is pinned by digest ([`spec/pin.json`](spec/pin.json)),
the generation config is committed ([`spec/sdkgen.yaml`](spec/sdkgen.yaml)), and the expected
output is pinned by content-address ([`spec/generated.lock.json`](spec/generated.lock.json)).
Reproducibility is enforced by the drift gate below.

Three spec overlays repair generation metadata (see
[`docs/spec-sync-and-release.md`](docs/spec-sync-and-release.md)). The third,
[`spec/overlays/sse-payload.yaml`](spec/overlays/sse-payload.yaml), unwraps the Speakeasy SSE
event envelope: without it every generated `*Stream` operation would throw on its first real
event, because OpenRouter sends each `data:` payload directly rather than wrapped in
`{ "data": … }`. It is removed once `kotlin-sdkgen` unwraps SSE envelopes natively.

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

        // Non-streaming completion.
        val result = client.chat.send(
            model = "openrouter/free",
            messages = listOf(userMessage("Say hello in one sentence.")),
            options = client.options(),
        )
        println(result.choices.firstOrNull()?.message?.content?.raw)

        // Streaming: cold Flow of text deltas.
        client.chat
            .stream(
                model = "openrouter/free",
                messages = listOf(userMessage("Explain structured concurrency in three sentences.")),
                options = client.options(),
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
| `samples/android` | OkHttp (streams into a `TextView`) | `./gradlew :samples:android:assembleDebug` |

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
| JVM | Compiles; fake-transport + Ktor `MockEngine` SSE streaming suites run and pass (CI-verified) |
| macOS (`macosArm64`) | Compiles; fake-transport + Ktor `MockEngine` SSE streaming suites run and pass (CI-verified) |
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
