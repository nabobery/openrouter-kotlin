# Sample consumers

One small consumer of `:sdk` per **engine family** — this table is the SDK's engine documentation. Most samples run
a non-streaming chat completion, then consume a streaming completion as a cold `Flow<ChatStreamEvent>` with
`contentDeltas()`. The SDK depends only on Ktor client *core* and imposes no engine; each consumer picks one.

| Sample | Module | Target(s) | Engine | Build / run |
| --- | --- | --- | --- | --- |
| JVM | `:samples:jvm` | JVM | **CIO** | `OPENROUTER_API_KEY=… ./gradlew :samples:jvm:run --args="openrouter/free"` |
| Android | `:samples:android` | android | **OkHttp** | `./gradlew :samples:android:compileAndroidMain` (see below) |
| macOS native | `:samples:apple` | macosArm64 | **Darwin** | `OPENROUTER_API_KEY=… ./gradlew :samples:apple:runDebugExecutableMacosArm64` |
| iOS (Swift) | `:samples:ios` | iosArm64/iosSimulatorArm64 | **Darwin** | `./scripts/ios-consumer-check.sh` (see `samples/ios/README.md`) |
| Linux native | `:samples:native-desktop` | linuxX64/linuxArm64 | **CIO** (Curl alt.) | `OPENROUTER_API_KEY=… ./gradlew :samples:native-desktop:runDebugExecutableLinuxX64` |
| Windows native | `:samples:native-desktop` | mingwX64 | **WinHttp** | `OPENROUTER_API_KEY=… ./gradlew :samples:native-desktop:runDebugExecutableMingwX64` |
| Node.js | `:samples:js` | js/node | **Js** (fetch) | `OPENROUTER_API_KEY=… ./gradlew :samples:js:jsNodeDevelopmentRun` |
| Browser | `:samples:browser` | js/browser | **Js** (fetch) | `./gradlew :samples:browser:jsBrowserDevelopmentWebpack`, then serve the bundle |

Compile every sample buildable on the current host (no network) with:

```bash
./gradlew samplesCheck
```

`samplesCheck` is host-aware: it compiles the JVM, JS (Node + browser), and native-desktop klibs everywhere, links a
native-desktop executable only on its matching host, and builds the macOS/iOS and Android samples on the hosts that
can. Running any sample needs a real `OPENROUTER_API_KEY`; the default model is the zero-cost `openrouter/free`
router (the JVM sample also accepts a model as its first argument).

### Native desktop (`:samples:native-desktop`)

One program, engine chosen per target via `expect/actual` (`Main.kt` is common; `Engine.kt` per source set): **Linux**
uses **CIO** (pure-Kotlin, no native dependency), **Windows** uses **WinHttp** (the OS HTTP stack). **Curl** is the
Linux alternative — it needs libcurl development headers, so it is documented here rather than defaulted. The klibs
cross-compile from any host; the executable links only on a matching native host.

### Browser (`:samples:browser`)

Streams into a `<pre>` over Ktor's **Js** (fetch) engine. The OpenRouter API allows browser calls with a user key,
which the page reads from an `<input>` and never bundles into the code. Note that the attribution headers
(HTTP-Referer / X-OpenRouter-Title) are your app's identity on the wire — see
[`docs/security-and-privacy.md`](../docs/security-and-privacy.md) ("Header safety").

## Android sample

`:samples:android` is included **only when an Android SDK is present** (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or a
`local.properties` with `sdk.dir`), so the root build never requires one. It is a
`com.android.kotlin.multiplatform.library` module that consumes the **android** variant of `:sdk`
(`org.jetbrains.kotlin.platform.type=androidJvm`) via the OkHttp engine and streams into a `TextView` on a
lifecycle-scoped coroutine, cancelling with the Activity lifecycle. (It is a library rather than a
`com.android.application` because Kotlin 2.3.20's KGP + AGP 9.2.1 cannot build the standalone-app path — the
legacy `KotlinAndroidTarget` references a class AGP 9.2.1 removed; a runnable APK app awaits a Kotlin/AGP bump.)
With an SDK present:

```bash
./gradlew :samples:android:compileAndroidMain
```
