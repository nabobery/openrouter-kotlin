import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import java.io.File

// Host detection from the public `os.name` / `os.arch` system properties (Gradle's `org.gradle.internal.os`
// package is an unstable internal API and may change across Gradle upgrades). Evaluated once at configuration time.
val osName: String = System.getProperty("os.name").lowercase()
val hostIsMac: Boolean = osName.contains("mac") || osName.contains("darwin")
val hostIsWindows: Boolean = osName.contains("windows")
val hostIsLinux: Boolean = osName.contains("linux")
val hostIsArm: Boolean = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sdkgen)
    // Publication (maven-publish + signing) is configured by gradle/openrouter-publication.gradle.kts, applied after
    // the kotlin {} block — Gradle-core plugins only, so nothing third-party joins the sdkgen buildscript classpath.
    // The Android KMP-library plugin is applied below only when the Android target is enabled, so a contributor
    // on a bare box without an Android SDK can still build every other target.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    // ktlint deferred: kotlin-sdkgen 0.3.0's ktlint auto-integration
    // (SdkGenPlugin.excludeGeneratedOutputFromKtlint) throws "argument type mismatch"
    // against ktlint-gradle 14.2.0 the moment the plugin is applied. Tracked as a
    // follow-up (pin a compatible ktlint-gradle, or lint handwritten sources via CLI).
}

// Coordinates for local consumption by the sample subprojects and publication (ADR 0006). The version is the single
// source of truth in gradle.properties (openrouter.version); scripts/release-version.py keeps SDK_VERSION in lockstep.
group = "io.github.nabobery"
version = providers.gradleProperty("openrouter.version").get()

// The Android Tier 1 target needs an Android SDK. It is enabled automatically when one is present (mirroring the
// settings.gradle.kts sample detection) and can be forced on/off with `-Popenrouter.androidTarget=true|false` — a
// contributor on a bare Linux box (or the ubuntu-24.04-arm runner, which may lack aapt2 for arm64) opts out with
// `-Popenrouter.androidTarget=false`, while CI on ubuntu-latest keeps it on via its bundled Android SDK.
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
    // Add a custom intermediate test source set (engineTest) below via an explicit dependsOn edge. Doing so
    // suppresses the default source-set hierarchy unless it is reapplied, so call it explicitly to keep the
    // standard commonMain/appleMain/nativeMain (and matching test) hierarchy that the Apple/native targets rely on.
    applyDefaultHierarchyTemplate()
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
    // Tier 2 native targets: compile everywhere; linuxX64 additionally runs its test lane in CI (build-linux).
    linuxX64()
    linuxArm64()
    mingwX64()

    // Android Tier 1 target (unblocked by kotlin-sdkgen 0.4.0's generated-source wiring, ADR 0022 D5). The plugin is
    // applied imperatively (conditionally), so the type-safe `androidLibrary { }` accessor is not generated; the
    // target is configured through its extension type instead. `withHostTestBuilder {}` creates the JVM-hosted
    // `androidHostTest` lane that runs the common **and** real-engine (`engineTest`) suites — the engineTest edge is
    // wired in the sourceSets block below; device tests need an emulator and are deferred. (AGP
    // 9.2.1's KMP-library extension no longer exposes `compilerOptions`; the android compilation uses its default
    // JVM target, which the AAR/klib consumers do not constrain the way the published `jvm` target does.)
    if (androidTargetEnabled) {
        extensions.configure(KotlinMultiplatformAndroidLibraryExtension::class.java) {
            namespace = "com.nabobery.openrouter"
            compileSdk = 36
            minSdk = 26
            @Suppress("UnstableApiUsage")
            withHostTestBuilder {}
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The generated and curated public API expose sdkgen-runtime types (CredentialProvider, CallOptions,
            // SdkTransport, typed exceptions, …) and kotlinx-serialization types (JsonElement on model `raw`), so
            // both must be `api` for external consumers to compile against the surface.
            api(libs.sdkgen.runtime)
            api(libs.kotlinx.serialization.json)
            // The curated factory signature exposes Ktor's HttpClient, so it must be `api`.
            api(libs.ktor.client.core)
            // Transport wiring is internal to the OpenRouter factory (no public signature exposes KtorSdkTransport).
            implementation(libs.sdkgen.transport.ktor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.sdkgen.testing)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // Real-engine test lanes (Ktor MockEngine, real time via the `runRealTime` harness) shared by EVERY host
        // test lane. A real Ktor engine hops dispatchers, so these must NOT use `runTest`'s virtual clock;
        // `runRealTime` (runTest + Dispatchers.Default) is the cross-platform driver — it works on JS, which has no
        // `runBlocking`. Attached to every runtime lane below; KGP disables a native test task on a host that cannot
        // execute its target, so a lane only actually runs where its host can boot it.
        val engineTest = create("engineTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.ktor.client.mock)
            }
        }
        jvmTest.get().dependsOn(engineTest)
        jsTest.get().dependsOn(engineTest) // Node + browser test tasks both derive from jsTest.
        macosArm64Test.get().dependsOn(engineTest)
        macosX64Test.get().dependsOn(engineTest)
        iosSimulatorArm64Test.get().dependsOn(engineTest)
        iosX64Test.get().dependsOn(engineTest)
        linuxX64Test.get().dependsOn(engineTest)
        linuxArm64Test.get().dependsOn(engineTest)
        mingwX64Test.get().dependsOn(engineTest)
        // The Android host lane runs the real-engine suite too, matching every other host lane. AGP's KMP-library
        // plugin creates the `androidHostTest` source set (a normal test tree that already includes commonTest), so
        // adding the engineTest edge is the missing link. Wired via `matching`/`configureEach` because AGP registers
        // the source set lazily; ktor-client-mock's JVM artifact drives the real engine on the JVM-hosted lane.
        if (androidTargetEnabled) {
            sourceSets.matching { it.name == "androidHostTest" }.configureEach { dependsOn(engineTest) }
        }
        // The opt-in live smoke test drives a real CIO engine against the OpenRouter API (JVM only).
        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)
        }
    }
}

// Publication (ADR 0006 coordinates, POM, javadoc jars, signing) — shared with :testing.
extra["openrouterArtifactId"] = "openrouter-kotlin"
extra["openrouterArtifactDescription"] =
    "Kotlin Multiplatform client for the OpenRouter API, generated from the pinned OpenAPI contract with a curated facade."
apply(from = rootProject.file("gradle/openrouter-publication.gradle.kts"))

// Every native test task stays ENABLED. Kotlin/Native disables a test task on a host that cannot execute its target
// (e.g. `macosX64Test`/`mingwX64Test`/`linuxArm64Test` on an arm macOS host print "disabled — cannot run on the
// current host" and are skipped), so a lane runs only where its host can boot it. The host-aware `verificationCheck`
// below asks only for the lanes the current host can actually run; CI schedules the rest on matching runners. This
// replaces the earlier blanket `enabled = false` that hid these lanes even on the hosts that could run them.

// iOS simulator runtime lane: pick the booted simulator device by name, overridable per host/CI image. CI on
// CI passes its image's device explicitly. The local default follows the current Xcode image; override it with
// -Popenrouter.iosSimulator=… when another simulator is installed.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    device.set(providers.gradleProperty("openrouter.iosSimulator").orElse("iPhone 17 Pro"))
}

// AGP's android packaging/metadata tasks scan the android source-set directories (which include the generated
// sources wired via kotlin-sdkgen 0.4.0) without declaring a dependency on the generation task, so a clean
// android publish/assemble races generation. Make every such task run after generation. (Needed for the
// artifact-size budget's publishToMavenLocal and for Android publication.)
if (androidTargetEnabled) {
    tasks.matching {
        it.name.startsWith("prepareAndroidMain") ||
            it.name.startsWith("mergeAndroidMain") ||
            it.name.startsWith("bundleAndroidMain") ||
            it.name.startsWith("generateAndroidMain")
    }.configureEach { mustRunAfter("generateOpenrouterSdk") }
}

// ktlint via CLI: the ktlint-gradle plugin cannot be applied (sdkgen's auto-integration
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

// `sdkgen diff` (kotlin-sdkgen CLI, frozen v1alpha1 contract) over two generation manifests — the semantic
// layer of the compatibility report (docs/compat/README.md). Inputs arrive as -Pcompat.from / -Pcompat.to and
// the JSON output goes to -Pcompat.out. Exit 1 means "differences found" and is EXPECTED; only exit 2 (usage)
// is a real failure, so the exit value is ignored and the consumer (scripts/compat-report.py) reads the JSON.
// Property reads and the output stream are deferred to execution (argumentProviders + doFirst) so the task is
// configuration-cache safe and never evaluates the -P properties unless it actually runs.
val sdkgenCli: Configuration = configurations.create("sdkgenCli")
dependencies { sdkgenCli(libs.sdkgen.cli) }

tasks.register<JavaExec>("sdkgenDiff") {
    group = "verification"
    description = "kotlin-sdkgen CLI `diff` over two generation manifests (semantic compatibility layer)."
    classpath = sdkgenCli
    mainClass.set("com.nabobery.sdkgen.cli.CliModuleKt")
    isIgnoreExitValue = true
    val from = providers.gradleProperty("compat.from")
    val to = providers.gradleProperty("compat.to")
    val out = providers.gradleProperty("compat.out")
    // Capture the project directory as a plain File at configuration time. Resolving the output path against it in
    // doFirst keeps the task configuration-cache safe: `file(...)` would capture a reference to the Project (a
    // disallowed script object), whereas a captured File serializes cleanly.
    val projectDir = layout.projectDirectory.asFile
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("diff", "--from", from.get(), "--to", to.get(), "--format", "json")
        },
    )
    doFirst {
        val outPath = out.get()
        val target = File(outPath).let { if (it.isAbsolute) it else projectDir.resolve(outPath) }
        target.parentFile?.mkdirs()
        standardOutput = target.outputStream()
    }
}

tasks.register("checkSdkVersionConstant") {
    group = "verification"
    description = "Fails if SDK_VERSION in OpenRouterVersion.kt drifts from project.version."
    val versionFile =
        layout.projectDirectory.file("src/commonMain/kotlin/com/nabobery/openrouter/OpenRouterVersion.kt")
    val projectVersion = project.version.toString()
    inputs.file(versionFile)
    inputs.property("projectVersion", projectVersion)
    doLast {
        val text = versionFile.asFile.readText()
        val constant =
            Regex("""SDK_VERSION:\s*String\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                ?: throw GradleException("Could not find the SDK_VERSION constant in ${versionFile.asFile}.")
        if (constant != projectVersion) {
            throw GradleException(
                "SDK_VERSION ('$constant') != project.version ('$projectVersion'); keep OpenRouterVersion.kt in lockstep.",
            )
        }
    }
}

// Host-aware verification gate used by CI. It always compiles the host-agnostic targets (JVM, JS, and the three
// Tier 2 native targets, which cross-compile from any host), adds the Apple compiles only on a macOS host (the
// Apple toolchain is macOS-only), checks the public API baseline, lints, and runs only the runtime test lanes the
// current host can actually execute. CI schedules the remaining native lanes on their matching runners.
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
        // Android host lane task is `testAndroidHostTest` (AGP KMP-library), not `androidHostTest`.
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
    dependsOn("checkSdkVersionConstant", "apiCheck", "ktlintCheck")
    dependsOn(verificationCompileTasks)
    dependsOn(verificationHostTestLanes)
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
