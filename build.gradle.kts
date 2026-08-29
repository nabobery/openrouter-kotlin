plugins {
    // Version-pin KMP/serialization here so :sdk can apply them without versions.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

// Compiles every always-present sample consumer with no network access. The macOS-native sample's compile task
// only exists on a macOS host, so this aggregate is host-specific (run on the Apple CI runner); the Linux CI job
// compiles the JVM and JS samples directly. Not wired into :sdk:verificationCheck, which stays host-safe on Linux.
tasks.register("samplesCheck") {
    group = "verification"
    description = "Compiles every sample consumer (no network)."
    dependsOn(
        ":samples:jvm:compileKotlin",
        ":samples:js:compileKotlinJs",
        ":samples:apple:compileKotlinMacosArm64",
    )
    // The Android sample only participates when an Android SDK made it part of the build.
    findProject(":samples:android")?.let { dependsOn(":samples:android:assembleDebug") }
}
