import kotlinx.validation.ExperimentalBCVApi

plugins {
    // Version-pin KMP/serialization here so :sdk can apply them without versions.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.binary.compatibility.validator)
    // Dokka is intentionally not applied because its bundled jackson-core shadows the one the sdkgen YAML parser
    // needs when both plugins share the :sdk classpath
    // (`YAMLParser._updateToken(JsonToken)`, changed in Jackson 2.22), breaking `generateOpenrouterSdk` even after
    // forcing the Jackson version. Source KDoc is instead checked by scripts/kdoc-audit.py.
}

// Enable klib ABI validation (BCV experimental) so the native/JS klib surface is baselined alongside the JVM
// `sdk.api`. The dump lives in `sdk/api/sdk.klib.api`; `apiCheck` validates it. Non-Apple hosts skip Apple
// targets unless strictValidation is set, so the full-target baseline is produced on the macOS host.
apiValidation {
    // :benchmarks is an internal kotlinx-benchmark module with no published API — keep it out of ABI validation.
    ignoredProjects.add("benchmarks")
    @OptIn(ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

// Host-aware sample verification (no network). Every host compiles the JVM, JS (Node + browser), and native-desktop
// (Linux CIO + Windows WinHttp) sample klibs; a native-desktop *executable* links only on its matching host (linking
// needs the platform sysroot), the macOS-native + iOS samples build only on macOS, and the Android sample builds
// only when an Android SDK made it part of the build. This aggregate runs on each CI runner.
// Host detection from the public `os.name` / `os.arch` system properties (Gradle's `org.gradle.internal.os`
// package is an unstable internal API). Evaluated once at configuration time.
val samplesOsName = System.getProperty("os.name").lowercase()
val samplesHostMac = samplesOsName.contains("mac") || samplesOsName.contains("darwin")
val samplesHostWindows = samplesOsName.contains("windows")
val samplesHostLinux = samplesOsName.contains("linux")
val samplesHostArm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
tasks.register("samplesCheck") {
    group = "verification"
    description = "Compiles every sample consumer for the current host (no network)."
    dependsOn(
        ":samples:jvm:compileKotlin",
        ":samples:docs:compileKotlin",
        ":samples:js:compileKotlinJs",
        ":samples:browser:compileKotlinJs",
        ":samples:native-desktop:compileKotlinLinuxX64",
        ":samples:native-desktop:compileKotlinLinuxArm64",
        ":samples:native-desktop:compileKotlinMingwX64",
    )
    when {
        samplesHostMac -> {
            dependsOn(":samples:apple:compileKotlinMacosArm64")
            // The iOS Swift-consumer facade module compiles here too when it is part of the build.
            findProject(":samples:ios:shared")?.let { dependsOn(":samples:ios:shared:compileKotlinMacosArm64") }
        }
        samplesHostLinux && samplesHostArm -> dependsOn(":samples:native-desktop:linkDebugExecutableLinuxArm64")
        samplesHostLinux -> dependsOn(":samples:native-desktop:linkDebugExecutableLinuxX64")
        samplesHostWindows -> dependsOn(":samples:native-desktop:linkDebugExecutableMingwX64")
    }
    // The Android sample (a com.android.kotlin.multiplatform.library consuming the android variant of :sdk) only
    // participates when an Android SDK made it part of the build. A runnable com.android.application APK is blocked
    // by the KGP-2.3.20/AGP-9.2.1 support ceiling (KGP 2.3.20 supports AGP ≤ 9.0.0; AGP 9.2.1's built-in Kotlin
    // delegates to KGP's legacy KotlinAndroidTarget, which references the removed BaseVariant). This module compiles
    // the Activity against the android variant instead.
    findProject(":samples:android")?.let { dependsOn(":samples:android:compileAndroidMain") }
}
