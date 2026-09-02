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

// The deterministic fake-transport test kit published as io.github.nabobery:openrouter-kotlin-testing (ADR 0006).
// Same target matrix as :sdk; depends on project(":sdk") and re-exports kotlin-sdkgen-testing. The project is named
// `:openrouter-kotlin-testing` (its directory stays `testing/`) so its klib `unique_name`
// (`<group>:<project name>`) does not collide with kotlin-sdkgen-testing's own `io.github.nabobery:testing` klib —
// a clash the JS/native klib resolvers reject. This also makes the publication artifactId rewrite a no-op.
include(":openrouter-kotlin-testing")
project(":openrouter-kotlin-testing").projectDir = file("testing")

// Publication tooling isolated in sibling projects so their Jackson-bearing classloaders (Dokka, CycloneDX) never
// meet the sdkgen YAML parser (Decision 4). :publication:dokka feeds every publication's javadoc jar.
include(":publication:dokka")
// CycloneDX SBOM over the published JVM runtime graph, also isolated (cyclonedx-core-java brings Jackson).
include(":publication:sbom")

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
