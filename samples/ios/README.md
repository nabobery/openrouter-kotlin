# iOS Swift consumer

A Swift program that consumes `:sdk` the way a real iOS app does — through its **own** shared Kotlin module, exported
as an XCFramework.

## Why a facade, not `:sdk` directly

Exporting `:sdk` itself as an Objective-C framework would emit headers for ~1,850 generated classes: a real
compile-time and binary-size hazard, and an API surface nobody designed for Swift. Real KMP apps consume a library
through a thin shared module that exposes only what the app needs. This sample does the same:

- **`shared/`** — a `kotlin-multiplatform` module (`iosArm64`, `iosSimulatorArm64`, `macosArm64`) whose
  `OpenRouterFacade` exposes two Swift-friendly members over the SDK: `hello(apiKey:model:)` (a `suspend` function,
  seen from Swift as `async`) and `collectDeltas(apiKey:prompt:onDelta:onComplete:)` (a callback-style stream
  consumer — no SKIE / Swift flow-export experiment). The facade owns its `HttpClient(Darwin)` and exposes `close()`.
  It is exported as the `OpenRouterSample` XCFramework (static; the `macosArm64` slice lets the consumer be exercised
  on a Mac without a simulator).
- **`SwiftConsumer/`** — a SwiftPM executable whose `Package.swift` points a `binaryTarget` at the built
  XCFramework. Its `main.swift` constructs the facade, awaits `hello` only when `OPENROUTER_API_KEY` is set, and
  calls `close()`. Compile-and-link is the CI evidence; the network call is opt-in.

## Run the check

```bash
./scripts/ios-consumer-check.sh
```

This builds the XCFramework (`:samples:ios:shared:assembleOpenRouterSampleDebugXCFramework`), then `swift build`s the
consumer against it, and prints the XCFramework size. It runs on a macOS host only and makes no network call. The
same check runs on the Apple CI lane (`build-apple`, macos-15).
