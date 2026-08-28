import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sdkgen)
    // ktlint deferred: kotlin-sdkgen 0.3.0's ktlint auto-integration
    // (SdkGenPlugin.excludeGeneratedOutputFromKtlint) throws "argument type mismatch"
    // against ktlint-gradle 14.2.0 the moment the plugin is applied. Tracked as a
    // follow-up (pin a compatible ktlint-gradle, or lint handwritten sources via CLI).
}

kotlin {
    explicitApi()
    // Build with the latest JDK (25) but emit JVM 17 bytecode: keeps the SDK consumable
    // by JVM 17+ hosts and lets binary-compatibility-validator's ABI reader parse the
    // classes (its ASM cannot read class-file major 69 / JVM 25).
    jvmToolchain(25)
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    js {
        nodejs()
        browser()
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    // Tier 2 (linuxX64, linuxArm64, mingwX64) deferred until CI covers them.

    sourceSets {
        commonMain.dependencies {
            implementation(libs.sdkgen.runtime)
            implementation(libs.kotlinx.serialization.json)
            // The curated factory signature exposes Ktor's HttpClient, so it must be `api`.
            api(libs.ktor.client.core)
            implementation(libs.sdkgen.transport.ktor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.sdkgen.testing)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// Every Apple target except the build host (macosArm64) is compile-only.
// Removing their runtime test tasks from the lifecycle keeps `./gradlew build` from
// executing simulator/device tests the host's Xcode cannot run (e.g. iosSimulatorArm64Test).
// These tasks only exist on a macOS host, so `matching` is a no-op elsewhere.
val compileOnlyAppleTargets = listOf("iosArm64", "iosSimulatorArm64", "iosX64", "macosX64")
compileOnlyAppleTargets.forEach { target ->
    tasks.matching { it.name == "${target}Test" }.configureEach { enabled = false }
}

// The documented, host-safe verification gate used by CI. It compiles every declared target,
// runs the runnable test lanes (JVM + macosArm64), and checks the public API baseline.
// The generateOpenrouterSdk task is pulled in transitively as a compile dependency.
// ktlint via CLI: the ktlint-gradle plugin cannot be applied (sdkgen 0.3.0's auto-integration
// crashes on application against ktlint-gradle 14.2.0), so lint handwritten sources directly.
// Generated sources live under build/ and are naturally out of scope.
val ktlintCli: Configuration = configurations.create("ktlintCli")
dependencies { ktlintCli(libs.ktlint.cli) }

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "ktlint over handwritten sources (sdk/src) via the ktlint CLI."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args("src/**/*.kt")
    workingDir = projectDir
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "ktlint --format over handwritten sources."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args("--format", "src/**/*.kt")
    workingDir = projectDir
}

tasks.register("verificationCheck") {
    group = "verification"
    description = "Host-safe verification: compile all targets, run JVM + macOS tests, check API."
    dependsOn(
        "compileKotlinJvm",
        "compileKotlinJs",
        "compileKotlinIosArm64",
        "compileKotlinIosSimulatorArm64",
        "compileKotlinIosX64",
        "compileKotlinMacosArm64",
        "compileKotlinMacosX64",
        "jvmTest",
        "macosArm64Test",
        "apiCheck",
        "ktlintCheck",
    )
}

sdkgen {
    configurations {
        // The published 0.3.0 plugin generates into build/generated/sdkgen/<name> (its
        // convention), stores a content-addressed snapshot, and auto-wires the resolved
        // `sources` symlink into commonMain. Generated sources are NOT checked in; the
        // generator's own `determinism` gate (spec/sdkgen.yaml) guarantees reproducibility,
        // and CI regenerates from the pinned spec on every run (see .github/workflows/ci.yml).
        register("openrouter") {
            configFile.set(rootProject.layout.projectDirectory.file("spec/sdkgen.yaml"))
            specFiles.from(rootProject.layout.projectDirectory.file("spec/openapi.yaml"))
            overlayFiles.from(rootProject.layout.projectDirectory.dir("spec/overlays"))
        }
    }
}
