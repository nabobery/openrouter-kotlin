import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

// Host detection from the public `os.name` / `os.arch` system properties (mirrors sdk/build.gradle.kts — the six
// lines are duplicated on purpose; a shared convention plugin is post-1.0 work, YAGNI). Evaluated once at
// configuration time.
val osName: String = System.getProperty("os.name").lowercase()
val hostIsMac: Boolean = osName.contains("mac") || osName.contains("darwin")
val hostIsWindows: Boolean = osName.contains("windows")
val hostIsLinux: Boolean = osName.contains("linux")
val hostIsArm: Boolean = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // No sdkgen plugin (this module generates nothing) and no Dokka (Decision 4). The Android KMP-library plugin is
    // applied below only when the Android target is enabled.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

// Coordinates for local consumption and publication (ADR 0006). The version is the single source of truth in
// gradle.properties (openrouter.version).
group = "io.github.nabobery"
version = providers.gradleProperty("openrouter.version").get()

// The klib `unique_name` is `<group>:<project name>`. The Gradle project is named `:openrouter-kotlin-testing`
// (settings.gradle.kts) precisely so this resolves to `io.github.nabobery:openrouter-kotlin-testing` rather than the
// default `io.github.nabobery:testing`, which collides with kotlin-sdkgen-testing's own klib (a project also named
// `testing` under the same group) and is rejected by the JS/native klib resolvers. (:sdk needs no such rename: no
// kotlin-sdkgen module is named `sdk`.)

// Same Android-target predicate as :sdk: enabled when an Android SDK is present, forceable with
// `-Popenrouter.androidTarget=true|false`.
val androidSdkPresent =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        rootProject.file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
val androidTargetEnabled: Boolean =
    providers.gradleProperty("openrouter.androidTarget").map { it.toBoolean() }.getOrElse(androidSdkPresent)
if (androidTargetEnabled) {
    apply(plugin = libs.plugins.android.kotlin.multiplatform.library.get().pluginId)
}

kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()
    // Build with JDK 25 but emit JVM 17 bytecode, matching :sdk (keeps BCV's ASM able to read the classes).
    jvmToolchain(25)
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    js {
        nodejs()
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    if (androidTargetEnabled) {
        extensions.configure(KotlinMultiplatformAndroidLibraryExtension::class.java) {
            namespace = "com.nabobery.openrouter.testing"
            compileSdk = 36
            minSdk = 26
            @Suppress("UnstableApiUsage")
            withHostTestBuilder {}
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The published SDK surface consumers write tests against.
            api(project(":sdk"))
            // The fixture kit consumers build on (FakeTransport, FakeByteStream, SSE fixtures) — re-exported on purpose.
            api(libs.sdkgen.testing)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Publication (ADR 0006 coordinates, POM, javadoc jars, signing) — shared with :sdk.
extra["openrouterArtifactId"] = "openrouter-kotlin-testing"
extra["openrouterArtifactDescription"] =
    "Deterministic fake-transport test kit for the openrouter-kotlin SDK: no network, no secrets, OpenRouter fixtures."
apply(from = rootProject.file("gradle/openrouter-publication.gradle.kts"))

// iOS simulator runtime lane device selection, matching :sdk.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    device.set(providers.gradleProperty("openrouter.iosSimulator").orElse("iPhone 17 Pro"))
}

// AGP's android packaging/metadata tasks scan android source-set directories without declaring a generation
// dependency; this module has no generated sources, so no mustRunAfter wiring is needed.

// ktlint via CLI over handwritten sources (mirrors :sdk — the ktlint-gradle plugin cannot be applied).
val ktlintCli: Configuration = configurations.create("ktlintCli")
dependencies { ktlintCli(libs.ktlint.cli) }

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "ktlint over handwritten sources (testing/src) via the ktlint CLI."
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

// Host-aware verification gate, identical in shape to :sdk's.
val verificationCompileTasks: List<String> =
    buildList {
        add("compileKotlinJvm")
        add("compileKotlinJs")
        add("compileKotlinLinuxX64")
        add("compileKotlinLinuxArm64")
        add("compileKotlinMingwX64")
        if (hostIsMac) {
            add("compileKotlinIosArm64")
            add("compileKotlinIosSimulatorArm64")
            add("compileKotlinIosX64")
            add("compileKotlinMacosArm64")
            add("compileKotlinMacosX64")
        }
        if (androidTargetEnabled) add("compileAndroidMain")
    }

val verificationHostTestLanes: List<String> =
    buildList {
        add("jvmTest")
        add("jsNodeTest")
        if (androidTargetEnabled) add("testAndroidHostTest")
        when {
            hostIsMac && hostIsArm -> {
                add("macosArm64Test")
                add("iosSimulatorArm64Test")
            }
            hostIsMac && !hostIsArm -> {
                add("macosX64Test")
                add("iosX64Test")
            }
            hostIsLinux && hostIsArm -> add("linuxArm64Test")
            hostIsLinux -> add("linuxX64Test")
            hostIsWindows -> add("mingwX64Test")
        }
    }

tasks.register("verificationCheck") {
    group = "verification"
    description = "Host-aware verification: compile all host-buildable targets, run the host's runtime lanes, check API."
    dependsOn("apiCheck", "ktlintCheck")
    dependsOn(verificationCompileTasks)
    dependsOn(verificationHostTestLanes)
}
