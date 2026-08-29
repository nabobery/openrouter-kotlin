# Sample consumers

Four small consumers of `:sdk`. The JVM, JS, and macOS-native samples run a non-streaming chat completion, then
consume a streaming completion as a cold `Flow<ChatStreamEvent>` with `contentDeltas()`. The JVM sample also
demonstrates a stream-idle deadline and early cancellation (via `takeWhile`). The Android sample focuses on
lifecycle-aware streaming into a `TextView`.

| Sample | Module | Engine | Run |
| --- | --- | --- | --- |
| JVM | `:samples:jvm` | CIO | `OPENROUTER_API_KEY=… ./gradlew :samples:jvm:run --args="openrouter/free"` |
| Node.js | `:samples:js` | Js | `OPENROUTER_API_KEY=… ./gradlew :samples:js:jsNodeDevelopmentRun` |
| macOS native | `:samples:apple` | Darwin | `OPENROUTER_API_KEY=… ./gradlew :samples:apple:runDebugExecutableMacosArm64` |
| Android | `:samples:android` | OkHttp | `./gradlew :samples:android:assembleDebug` (see below) |

The JVM, JS, and macOS-native samples are always part of the build and **compile in CI** (no network). Compile them
all on a macOS host with:

```bash
./gradlew samplesCheck
```

Running any sample needs a real `OPENROUTER_API_KEY`. The default model is the zero-cost `openrouter/free` router;
the JVM sample also accepts a model as its first argument.

## Android sample

`:samples:android` is included **only when an Android SDK is present** (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or a
`local.properties` with `sdk.dir`), so the root build never requires one. It consumes the JVM variant of `:sdk`
(the same mechanism every Android app uses to consume `kotlinx-serialization-json`) via the OkHttp engine and
streams into a `TextView` on a lifecycle-scoped coroutine, cancelling with the Activity lifecycle. With an SDK
present:

```bash
./gradlew :samples:android:assembleDebug
```
