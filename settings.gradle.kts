pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "openrouter-kotlin"

include(":sdk")

// Runtime benchmarks (kotlinx-benchmark). Not built by the PR gate — run nightly via .github/workflows/perf.yml.
include(":benchmarks")

// Sample consumers. JVM (CIO), Node.js (Js), and macOS-native (Darwin) always participate; they depend on
// project(":sdk") and compile in CI. The Android sample is included only when an Android SDK is present, so the
// root build never requires one.
include(":samples:jvm", ":samples:js", ":samples:apple", ":samples:native-desktop", ":samples:browser")
// Compiled documentation snippets: docs/guides/**/*.md examples are injected from this JVM module (kt -> md).
include(":samples:docs")
// The iOS Swift consumer's shared Kotlin facade module (exported as an XCFramework). The Swift package under
// samples/ios/SwiftConsumer consumes the built XCFramework and is driven by scripts/ios-consumer-check.sh.
include(":samples:ios:shared")

val androidSdkPresent =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        File(rootDir, "local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
// Mirror the `:sdk` android-target predicate exactly (sdk/build.gradle.kts): `-Popenrouter.androidTarget=false`
// disables the android target, so the android sample (which consumes that variant) must not be included either —
// otherwise a host with an Android SDK but the target forced off (e.g. ubuntu-24.04-arm) would fail to resolve it.
val androidTargetEnabled =
    startParameter.projectProperties["openrouter.androidTarget"]?.toBoolean() ?: androidSdkPresent
if (androidTargetEnabled) include(":samples:android")
