# OpenRouter Kotlin Phase 0 Scaffold Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Scaffold the `openrouter-kotlin` KMP repository so that a clean clone regenerates the full 89-operation OpenRouter SDK deterministically from the pinned spec via the published `kotlin-sdkgen` 0.3.0 Gradle plugin, compiles it, and proves it callable with a fake-transport smoke test.

**Architecture:** Template-first — we do NOT write generator config, spec, or overlays from scratch. We port the released, conformance-proven corpus from the local `kotlin-sdkgen` checkout (`conformance/openrouter/`), adapt four named fields, and wire the published Gradle plugin (`io.github.nabobery.kotlin-sdkgen:0.3.0`) into a single `:sdk` KMP module. Generated sources are checked in (ADR 0005 drift-review model); a drift gate is `regenerate + git diff --exit-code`.

**Tech Stack:** Kotlin 2.3.20 (multiplatform + serialization), Gradle 9.6.1, kotlin-sdkgen plugin/runtime/testing 0.3.0, Ktor 3.5.2 (transport, test-only for now), kotlinx-serialization 1.11.0, kotlinx-coroutines 1.11.0, ktlint (jlleitschuh plugin), kotlinx binary-compatibility-validator, JDK toolchain 17.

---

## Context the executor must know

**Repos (absolute paths):**
- Target repo (work here): `/Users/avinashchangrani/personal/openrouter-kotlin` — a git repo on branch `main` with **zero commits**; only untracked `LICENSE` and `docs/`.
- Template source (READ-ONLY — never modify): `/Users/avinashchangrani/personal/kotlin-sdkgen`. Referred to below as `$SDKGEN`.

**Why this works:** kotlin-sdkgen 0.3.0 generates all 89/89 OpenRouter operations with zero blockers and zero waivers. The exact working input set lives at `$SDKGEN/conformance/openrouter/`: pinned `openapi.yaml` (sha256 `b901d462e355e54b90ee2320bf7f18d0cb8edea857d5cdd8623d704f77a9eb47`), two load-bearing overlays, and `sdkgen.yaml`. **Both overlays are mandatory** — without `allof-resolution-audit.yaml`, `/messages` and `/responses` do not generate; without `full-spec-compat.yaml`, streaming and pagination metadata is missing.

**Gradle plugin mechanics (verified in `$SDKGEN/integrations/gradle-plugin/src/main/kotlin/com/nabobery/sdkgen/gradleplugin/SdkGenPlugin.kt`):**
- Registering `sdkgen { configurations { register("openrouter") {...} } }` creates a task named `generateOpenrouterSdk` (pattern: `generate<Name>Sdk`, `SdkGenPlugin.kt:111`).
- The plugin wires the task's `outputDirectory` into the `commonMain` Kotlin source set automatically (`SdkGenPlugin.kt:42`). Fallback if that ever fails: add `kotlin.srcDir("generated")` to `commonMain` manually (that is how `$SDKGEN/conformance/openrouter/consumer/build.gradle.kts:66` does it).
- If the `org.jlleitschuh.gradle.ktlint` plugin is applied, sdkgen auto-configures ktlint to exclude generated sources (`SdkGenPlugin.kt:44`).
- Configuration properties: `configFile`, `specFiles`, `overlayFiles`, `outputDirectory` (`SdkGenExtension.kt`).

**Published coordinates (verified in `$SDKGEN/build-logic/.../Adr0008ProductArtifactIds.kt:23-30`):**
- Plugin: `id("io.github.nabobery.kotlin-sdkgen") version "0.3.0"` (Gradle Plugin Portal)
- Runtime: `io.github.nabobery:kotlin-sdkgen-runtime:0.3.0`
- Testing: `io.github.nabobery:kotlin-sdkgen-testing:0.3.0`
- Transports: `io.github.nabobery:kotlin-sdkgen-transport-ktor:0.3.0` (also `-okhttp`, `-java-http`)
- Runtime publishes targets: jvm, js, android, iosArm64, iosSimulatorArm64, iosX64, macosArm64, macosX64, linuxX64, linuxArm64, mingwX64. **Wasm, watchOS, tvOS are NOT supported** — do not add them.

**Two policy notes:**
1. `diagnostics.warningsAsErrors` must stay `false` — the corpus emits benign `SDKGEN-LEGACY-NULLABLE-COMPOSITION` warnings; flipping it breaks generation.
2. The `kotlin.targets` list inside `sdkgen.yaml` drives the *generator's* verification gates only (corpus-proven value: `[jvm, js, macos]`). It does NOT need to match the KMP module's target list — generated code is pure `commonMain` source and compiles for every KMP target we declare. Leave the yaml value as-is.

**Commit policy:** Commit at the end of every task with the given message (these are authorized per-task checkpoints for this plan). NEVER push. Work directly on `main` (the repo has no history yet). Do not commit anything inside `$SDKGEN`.

---

### Task 1: Baseline commit (docs + license + gitignore)

**Files:**
- Create: `.gitignore`
- Commit (already present, untracked): `LICENSE`, `docs/**`

**Step 1: Write `.gitignore`**

```gitignore
.gradle/
build/
.kotlin/
local.properties
.DS_Store
*.log
.idea/
```

Note: do NOT ignore `kotlin-js-store/` (yarn lockfiles must be committed for reproducible JS builds) and do NOT ignore `sdk/generated/` (checked-in sources are the drift-review model).

**Step 2: Verify repo state**

Run: `git -C /Users/avinashchangrani/personal/openrouter-kotlin status --short`
Expected: `?? .gitignore`, `?? LICENSE`, `?? docs/` and nothing else.

**Step 3: Commit**

```bash
cd /Users/avinashchangrani/personal/openrouter-kotlin
git add .gitignore LICENSE docs
git commit -m "chore: baseline docs, license, and gitignore"
```

---

### Task 2: Gradle wrapper, settings, version catalog

**Files:**
- Copy: `gradlew`, `gradlew.bat`, `gradle/wrapper/` from `$SDKGEN`
- Create: `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `build.gradle.kts` (root, minimal)

**Step 1: Copy the wrapper (template, Gradle 9.6.1 — the version the plugin is built against)**

```bash
cd /Users/avinashchangrani/personal/openrouter-kotlin
cp /Users/avinashchangrani/personal/kotlin-sdkgen/gradlew /Users/avinashchangrani/personal/kotlin-sdkgen/gradlew.bat .
mkdir -p gradle/wrapper
cp /Users/avinashchangrani/personal/kotlin-sdkgen/gradle/wrapper/gradle-wrapper.jar /Users/avinashchangrani/personal/kotlin-sdkgen/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
chmod +x gradlew
```

**Step 2: Write `settings.gradle.kts`**

```kotlin
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
```

**Step 3: Write `gradle/libs.versions.toml`**

First read `$SDKGEN/gradle/libs.versions.toml` and copy the exact version strings for `ktlint` (the ktlint tool) and the `org.jlleitschuh.gradle.ktlint` plugin entry (search for `jlleitschuh` or `ktlint-gradle`). Then write:

```toml
[versions]
kotlin = "2.3.20"
sdkgen = "0.3.0"
kotlinx-serialization = "1.11.0"
kotlinx-coroutines = "1.11.0"
ktor = "3.5.2"
binary-compatibility-validator = "0.18.1"
# ktlint + ktlint-gradle: copy exact values from $SDKGEN/gradle/libs.versions.toml

[libraries]
sdkgen-runtime = { module = "io.github.nabobery:kotlin-sdkgen-runtime", version.ref = "sdkgen" }
sdkgen-testing = { module = "io.github.nabobery:kotlin-sdkgen-testing", version.ref = "sdkgen" }
sdkgen-transport-ktor = { module = "io.github.nabobery:kotlin-sdkgen-transport-ktor", version.ref = "sdkgen" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
sdkgen = { id = "io.github.nabobery.kotlin-sdkgen", version.ref = "sdkgen" }
binary-compatibility-validator = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version.ref = "binary-compatibility-validator" }
# ktlint plugin: copy the plugin id + version ref from $SDKGEN catalog
```

If `binary-compatibility-validator` 0.18.1 does not resolve, use the newest 0.18.x visible on the Gradle Plugin Portal.

**Step 4: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx4g
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.code.style=official
```

**Step 5: Write a minimal root `build.gradle.kts`**

```kotlin
plugins {
    // Version-pin KMP/serialization here so :sdk can apply them without versions.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

**Step 6: Create a placeholder `sdk/build.gradle.kts`** (empty file, filled in Task 5) so `include(":sdk")` resolves:

```bash
mkdir -p sdk && touch sdk/build.gradle.kts
```

**Step 7: Verify Gradle boots**

Run: `./gradlew help --quiet`
Expected: `Welcome to Gradle 9.6.1` banner or silent success, exit 0. If configuration-cache errors appear, rerun once (first run populates it).

**Step 8: Commit**

```bash
git add gradlew gradlew.bat gradle settings.gradle.kts gradle.properties build.gradle.kts sdk
git commit -m "build: Gradle 9.6.1 wrapper, settings, and version catalog"
```

---

### Task 3: Import the pinned spec and overlays (template port)

**Files:**
- Create: `spec/openapi.yaml`, `spec/overlays/allof-resolution-audit.yaml`, `spec/overlays/full-spec-compat.yaml`, `spec/pin.json`

**Step 1: Copy from the conformance corpus**

```bash
cd /Users/avinashchangrani/personal/openrouter-kotlin
mkdir -p spec/overlays
cp /Users/avinashchangrani/personal/kotlin-sdkgen/conformance/openrouter/openapi.yaml spec/
cp /Users/avinashchangrani/personal/kotlin-sdkgen/conformance/openrouter/overlays/allof-resolution-audit.yaml spec/overlays/
cp /Users/avinashchangrani/personal/kotlin-sdkgen/conformance/openrouter/overlays/full-spec-compat.yaml spec/overlays/
```

**Step 2: Verify digests match the corpus pins (fail loudly if not)**

```bash
shasum -a 256 spec/openapi.yaml spec/overlays/*.yaml
```

Expected output (exact):
```
b901d462e355e54b90ee2320bf7f18d0cb8edea857d5cdd8623d704f77a9eb47  spec/openapi.yaml
f8bc7a924cf9bc0af7ac54abc8a933037d0c30631b7bf690884ba3dfcd5cb6d0  spec/overlays/allof-resolution-audit.yaml
0ced3f18aa83e29f6aadc41d82d302e0312f3736c5c4914e27250dab964fb5c5  spec/overlays/full-spec-compat.yaml
```

If any digest differs, STOP — the template corpus has drifted from this plan; report back rather than proceeding.

**Step 3: Write the provenance pin manifest `spec/pin.json`** (ADR 0005 requires source URL, digest, retrieval provenance, generator coordinate, overlay digests)

```json
{
  "source": "https://openrouter.ai/openapi.yaml",
  "sha256": "b901d462e355e54b90ee2320bf7f18d0cb8edea857d5cdd8623d704f77a9eb47",
  "sizeBytes": 1203455,
  "retrievedAt": "2026-08-17T00:00:00Z",
  "provenance": "Ported from kotlin-sdkgen conformance corpus conformance/openrouter at v0.3.0 (tag commit 827d47985); originally retrieved 2026-08-17.",
  "generator": "io.github.nabobery.kotlin-sdkgen:0.3.0",
  "overlays": [
    { "id": "openrouter-allof-resolution-audit", "file": "overlays/allof-resolution-audit.yaml", "sha256": "f8bc7a924cf9bc0af7ac54abc8a933037d0c30631b7bf690884ba3dfcd5cb6d0" },
    { "id": "openrouter-full-spec-compat", "file": "overlays/full-spec-compat.yaml", "sha256": "0ced3f18aa83e29f6aadc41d82d302e0312f3736c5c4914e27250dab964fb5c5" }
  ]
}
```

Verify `sizeBytes` with `wc -c < spec/openapi.yaml` (expected `1203455`); correct the field if it differs.

**Step 4: Commit**

```bash
git add spec
git commit -m "spec: import pinned OpenRouter OpenAPI spec and sdkgen overlays from conformance corpus v0.3.0"
```

---

### Task 4: Adapt `sdkgen.yaml` for this product

**Files:**
- Create: `spec/sdkgen.yaml` (adapted copy of `$SDKGEN/conformance/openrouter/sdkgen.yaml`)

**Step 1: Write `spec/sdkgen.yaml`** — identical to the corpus file except the four fields marked `# CHANGED`. All `uri:` values are relative to this file's directory (`spec/`), which is why spec, overlays, and config stay co-located, mirroring the corpus layout exactly.

```yaml
version: v1alpha1
source:
  uri: openapi.yaml
  sha256: b901d462e355e54b90ee2320bf7f18d0cb8edea857d5cdd8623d704f77a9eb47
  acquisition:
    mode: local
    offline: true
    allowedHosts: []
    followRedirects: false
    maxRedirects: 0
    maxBytes: 16777216
    timeoutSeconds: 30
    cacheDirectory: .sdkgen/cache
    allowedLocalRoots:
      - .
overlays:
  - id: openrouter-allof-resolution-audit
    uri: overlays/allof-resolution-audit.yaml
    sha256: f8bc7a924cf9bc0af7ac54abc8a933037d0c30631b7bf690884ba3dfcd5cb6d0
    zeroMatchPolicy: fail
    conflictPolicy: fail
  - id: openrouter-full-spec-compat
    uri: overlays/full-spec-compat.yaml
    sha256: 0ced3f18aa83e29f6aadc41d82d302e0312f3736c5c4914e27250dab964fb5c5
    zeroMatchPolicy: fail
    conflictPolicy: fail
kotlin:
  packageName: com.nabobery.openrouter          # CHANGED (was com.nabobery.sdkgen.generated)
  coordinates:
    groupId: io.github.nabobery                 # CHANGED (was com.nabobery.sdkgen)
    artifactId: openrouter-kotlin               # CHANGED (was openrouter-conformance)
  naming:
    clientName: OpenRouter                      # CHANGED (was OpenRouterClient; ADR 0001 names the root client OpenRouter)
  targets:
    - jvm
    - js
    - macos
output:
  sources: generated
  resources: generated-resources
  manifest: manifest.json
  lock: sdkgen.lock
  checkedInSources: true
diagnostics:
  warningsAsErrors: false
  warningAllowlist: []
  format: json
verification:
  gates:
    - schema
    - determinism
    - api
```

**Step 2: Sanity-diff against the corpus original — only the four CHANGED lines (plus comments) may differ**

```bash
diff <(grep -v '#' spec/sdkgen.yaml) /Users/avinashchangrani/personal/kotlin-sdkgen/conformance/openrouter/sdkgen.yaml
```

Expected: exactly four changed lines (`packageName`, `groupId`, `artifactId`, `clientName`).

**Step 3: Commit**

```bash
git add spec/sdkgen.yaml
git commit -m "spec: sdkgen generation config for com.nabobery.openrouter / io.github.nabobery:openrouter-kotlin"
```

---

### Task 5: `:sdk` module — apply the plugin, first generation (JVM only)

**Files:**
- Modify: `sdk/build.gradle.kts`

**Step 1: Write `sdk/build.gradle.kts`** (single target first; the matrix grows in Tasks 8-9)

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sdkgen)
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.sdkgen.runtime)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.sdkgen.testing)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

sdkgen {
    configurations {
        register("openrouter") {
            configFile.set(rootProject.layout.projectDirectory.file("spec/sdkgen.yaml"))
            specFiles.from(rootProject.layout.projectDirectory.file("spec/openapi.yaml"))
            overlayFiles.from(rootProject.layout.projectDirectory.dir("spec/overlays"))
            outputDirectory.set(layout.projectDirectory.dir("generated"))
        }
    }
}
```

**Step 2: Confirm the generation task exists**

Run: `./gradlew :sdk:tasks --all | grep -i sdk`
Expected: `generateOpenrouterSdk` appears.

**Step 3: Run generation**

Run: `./gradlew :sdk:generateOpenrouterSdk`
Expected: BUILD SUCCESSFUL. Warnings with code `SDKGEN-LEGACY-NULLABLE-COMPOSITION` are expected and fine. Any `blocker` diagnostic or overlay digest error is a STOP — report back.

**Step 4: Verify the generated surface**

```bash
ls sdk/generated | head
find sdk/generated -name "*.kt" | wc -l
find sdk/generated -path "*chat*" -name "ChatClient.kt"
grep -rl "class OpenRouter" sdk/generated --include="*.kt" | head -3
```

Expected: ~1,600+ Kotlin files (the corpus snapshot has 1,671 total files); a `ChatClient.kt` under a `com/nabobery/openrouter/chat/` path; a root `OpenRouter` client type. Also locate where `manifest.json` and `sdkgen.lock` were written (`find . -name manifest.json -not -path "*/build/*"`; they may land next to `spec/sdkgen.yaml` or in the output dir — either is fine, they get committed in Task 6).

**Step 5: Compile**

Run: `./gradlew :sdk:compileKotlinJvm`
Expected: BUILD SUCCESSFUL. If compilation fails on unresolved generated sources, the automatic source-set wiring failed — add `kotlin.srcDir("generated")` inside `commonMain {}` and retry.

**Step 6: Commit (build wiring only — generated output is committed after the determinism proof in Task 6)**

```bash
git add sdk/build.gradle.kts
git commit -m "build(sdk): KMP module with kotlin-sdkgen 0.3.0 plugin, JVM target"
```

---

### Task 6: Determinism proof, then commit generated sources

**Step 1: Hash the first generation**

```bash
cd /Users/avinashchangrani/personal/openrouter-kotlin
find sdk/generated -type f | sort | xargs shasum -a 256 | shasum -a 256 > /tmp/gen1.sha
```

**Step 2: Wipe and regenerate from scratch**

```bash
rm -rf sdk/generated
./gradlew :sdk:generateOpenrouterSdk --rerun-tasks
find sdk/generated -type f | sort | xargs shasum -a 256 | shasum -a 256 > /tmp/gen2.sha
diff /tmp/gen1.sha /tmp/gen2.sha && echo DETERMINISTIC
```

Expected: `DETERMINISTIC`. (The generator also runs its own `determinism` verification gate from `spec/sdkgen.yaml`; this proves it end-to-end through the Gradle plugin.) If the hashes differ, STOP and report — do not commit nondeterministic output.

**Step 3: Commit generated sources + manifest + lock**

```bash
git add sdk/generated spec/sdkgen.lock 2>/dev/null; git add -A sdk spec
git status --short   # review: only generated output, manifest, and lock should be staged
git commit -m "feat(sdk): check in deterministic 89-operation generated OpenRouter surface (sdkgen 0.3.0)"
```

---

### Task 7: Fake-transport smoke test (proves the generated code is callable)

**Files:**
- Create: `sdk/src/commonTest/kotlin/com/nabobery/openrouter/SmokeTest.kt`

**Step 1: Copy the template test, don't invent one.** Open
`$SDKGEN/conformance/openrouter/consumer/src/commonTest/kotlin/com/nabobery/sdkgen/generated/OpenRouterFixtureConformanceTest.kt`
and port ONLY the first test (`ordinaryGeneratedCallUsesFakeTransportAndClosesBody`) into `SmokeTest.kt`:
- Change the package to `com.nabobery.openrouter`.
- Change generated-code imports from `com.nabobery.sdkgen.generated.*` to `com.nabobery.openrouter.*` (runtime/testing imports `com.nabobery.sdkgen.runtime.*` / `com.nabobery.sdkgen.testing.*` stay as they are).
- Keep the `FakeTransport` + `FakeByteStream` fixture and the assertion structure exactly; adapt the client construction to the ported package. If the copied test references helpers beyond the first test's needs, drop them.

**Step 2: Run the test**

Run: `./gradlew :sdk:jvmTest --tests "com.nabobery.openrouter.SmokeTest"`
Expected: PASS (1 test). This is an acceptance gate: it proves generated client + runtime executor + serialization all wire together in the new package. If it fails to compile, re-check the import mapping against the actual generated file paths before changing anything else.

**Step 3: Commit**

```bash
git add sdk/src
git commit -m "test(sdk): fake-transport smoke test through the generated ChatClient"
```

---

### Task 8: Expand the KMP target matrix (JS + Apple)

**Files:**
- Modify: `sdk/build.gradle.kts` (the `kotlin {}` block)

**Step 1: Add targets** (leave `spec/sdkgen.yaml` untouched — see Context note 2)

```kotlin
kotlin {
    jvmToolchain(17)
    jvm()
    js { nodejs(); browser() }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    // Tier 2 (linuxX64, linuxArm64, mingwX64) deferred until CI covers them.
    ...
}
```

**Step 2: Compile the matrix (this host is an Apple-silicon Mac; all listed targets compile locally)**

Run: `./gradlew :sdk:compileKotlinJs :sdk:compileKotlinMacosArm64 :sdk:compileKotlinIosSimulatorArm64 :sdk:compileKotlinIosArm64 :sdk:compileKotlinMacosX64 :sdk:compileKotlinIosX64`
Expected: BUILD SUCCESSFUL for all. If the JS toolchain writes `kotlin-js-store/` (yarn lock), commit it too.

**Step 3: Run the smoke test on a second platform**

Run: `./gradlew :sdk:macosArm64Test`
Expected: PASS — same test, non-JVM runtime.

**Step 4: Commit**

```bash
git add sdk/build.gradle.kts kotlin-js-store 2>/dev/null; git add -A sdk kotlin-js-store 2>/dev/null || git add sdk
git commit -m "build(sdk): Tier 1 target matrix (JVM, JS, iOS, macOS)"
```

---### Task 9: Android target (separate task — needs AGP + SDK)

**Files:**
- Modify: `settings.gradle.kts` (google() already present), `gradle/libs.versions.toml`, `sdk/build.gradle.kts`

**Precondition:** `ANDROID_HOME=$HOME/Library/Android/sdk` must exist (`ls $HOME/Library/Android/sdk` non-empty). If it does not, SKIP this task, note the skip in the final report, and continue — Android is deferrable, not droppable.

**Step 1: Add AGP to the catalog** (mirror `$SDKGEN`: AGP `9.2.1`, plugin id `com.android.kotlin.multiplatform.library`, compileSdk 36 — see `$SDKGEN/build-logic/src/main/kotlin/sdkgen.kotlin-kmp-android.gradle.kts`)

```toml
[versions]
agp = "9.2.1"
[plugins]
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
```

**Step 2: Apply in `sdk/build.gradle.kts`**

```kotlin
plugins {
    // ...existing...
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // ...existing targets...
    androidLibrary {
        namespace = "com.nabobery.openrouter"
        compileSdk = 36
        minSdk = 26
    }
}
```

(Property names follow AGP 9's KMP androidLibrary DSL — check `$SDKGEN/build-logic/src/main/kotlin/sdkgen.kotlin-kmp-android.gradle.kts` for the exact block shape and mirror it, including any `withDeviceTest`/`withHostTest` omissions.)

**Step 3: Compile**

Run: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :sdk:compileAndroidMain 2>/dev/null || ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :sdk:build -x test --dry-run`
Then run the real compile task the dry-run reveals for the android target. Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add gradle/libs.versions.toml sdk/build.gradle.kts settings.gradle.kts
git commit -m "build(sdk): Android library target (AGP 9.2.1, compileSdk 36)"
```

---

### Task 10: Quality gates — explicit API, ktlint, API baseline

**Files:**
- Modify: `sdk/build.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml` (ktlint entries from Task 2)

**Step 1: Explicit API mode** — in `sdk/build.gradle.kts` inside `kotlin {}` add `explicitApi()`. Run `./gradlew :sdk:compileKotlinJvm`. Generated code is emitted with explicit visibility, so this should pass; if generated sources somehow violate it, downgrade to `explicitApiWarning()` and note it in the report (do NOT hand-edit generated files).

**Step 2: ktlint** — apply the `org.jlleitschuh.gradle.ktlint` plugin (catalog alias from Task 2) in `sdk/build.gradle.kts`. The sdkgen plugin auto-excludes generated sources when ktlint is present. Run `./gradlew :sdk:ktlintCheck` — expected: PASS (only our handwritten test file is checked). If generated files ARE checked, add the corpus consumer's explicit excludes (see `$SDKGEN/conformance/openrouter/consumer/build.gradle.kts:55-61`).

**Step 3: API baseline** — apply `binary-compatibility-validator` at the root:

```kotlin
plugins {
    alias(libs.plugins.binary.compatibility.validator)
    // ...existing apply false entries...
}
```

Run: `./gradlew apiDump` then `./gradlew apiCheck`
Expected: `sdk/api/sdk.api` (JVM dump) created; apiCheck passes. Klib/native API validation stays off for now (note as follow-up).

**Step 4: Commit**

```bash
git add -A
git commit -m "build: explicit API mode, ktlint, and binary-compatibility baseline"
```

---

### Task 11: Drift gate + CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

**Step 1: Verify the drift gate locally** (regeneration must be a no-op against the committed tree)

```bash
./gradlew :sdk:generateOpenrouterSdk --rerun-tasks
git diff --exit-code -- sdk/generated && echo NO-DRIFT
```

Expected: `NO-DRIFT`.

**Step 2: Write `.github/workflows/ci.yml`** — be honest in comments about what each job runs (do not describe gates CI doesn't run):

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:

jobs:
  build-linux:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - uses: gradle/actions/setup-gradle@v4
      # Regeneration must reproduce the committed generated tree byte-for-byte.
      - name: Generation drift gate
        run: |
          ./gradlew :sdk:generateOpenrouterSdk --rerun-tasks
          git diff --exit-code -- sdk/generated
      - name: Build, test (JVM + JS), lint, API check
        run: ./gradlew :sdk:jvmTest :sdk:compileKotlinJs ktlintCheck apiCheck

  build-apple:
    # Compiles the Apple matrix and runs the macOS test lane; iOS device targets are compile-only here.
    runs-on: macos-14
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :sdk:macosArm64Test :sdk:compileKotlinIosArm64 :sdk:compileKotlinIosSimulatorArm64
```

(If Task 9 ran, add an Android compile step to `build-linux` with the SDK preinstalled on the runner image.)

**Step 3: Commit**

```bash
git add .github
git commit -m "ci: drift gate, test, lint, and API-check workflows"
```

---

### Task 12: README + record what Phase 0 leaves open

**Files:**
- Create: `README.md`
- Create: `docs/plans/records/2026-08-28-phase0-scaffold-outcome.md`

**Step 1: Write a short `README.md`**: what the repo is (Kotlin Multiplatform SDK for OpenRouter, generated by kotlin-sdkgen 0.3.0 from the pinned spec in `spec/`), how to regenerate (`./gradlew :sdk:generateOpenrouterSdk`), how to run tests, target tiers (link `docs/target-support.md`), and a "not yet published" status line. Do not overclaim: no Maven coordinates exist yet, and only the target lanes CI actually runs may be called "verified".

**Step 2: Write the outcome record** listing: tasks completed with commit SHAs; anything skipped (e.g. Android precondition); measured facts (generated file count, test counts per lane); and the known follow-ups for Phase 1 (curated DSL/facade layer over `kotlin-sdkgen-runtime` — NOT a reimplementation; nightly drift cron against the live spec URL with `curl` + digest compare, since sdkgen HTTPS acquisition is inert; Tier 2 targets; klib API validation; publication setup per ADR 0006; and refreshing the stale generator-dependency sections in `docs/plans/2026-07-23-openrouter-kotlin-implementation-plan.md` (G3 gate, lines ~28-52 and ~285-297), `docs/system-design.md:327-332`, `docs/target-support.md:19-21`, `docs/product-requirements.md:395-396` — all now satisfied by sdkgen 0.3.0's 89/89 coverage).

**Step 3: Final verification — the Phase 0 exit gate, from a pristine state**

```bash
git status --short          # expected: empty
./gradlew clean :sdk:generateOpenrouterSdk --rerun-tasks
git diff --exit-code -- sdk/generated
./gradlew build
```

Expected: clean tree, no drift, full build green.

**Step 4: Commit**

```bash
git add README.md docs/plans/records
git commit -m "docs: README and Phase 0 scaffold outcome record"
```

---

## Exit criteria (Phase 0, per docs/plans/2026-07-23 implementation plan lines 78-82)

- [ ] Clean clone regenerates and compiles the exact 89-operation API with zero hand edits to generated sources
- [ ] Determinism proven (double-generation byte-identity, Task 6) and enforced in CI (Task 11)
- [ ] Generated code callable end-to-end through a fake transport with no network or secrets (Task 7)
- [ ] Tier 1 target matrix compiles; JVM + macOS test lanes green (Tasks 8-9)
- [ ] Explicit API mode, lint, and API baseline in place (Task 10)
- [ ] Every deferred item written down in the outcome record (Task 12)

## Failure discipline

- Any digest mismatch (Task 3), generation blocker (Task 5), or nondeterminism (Task 6) is a STOP-and-report, not a work-around.
- Never edit files under `sdk/generated/` by hand, and never modify anything under `/Users/avinashchangrani/personal/kotlin-sdkgen`.
- If a Gradle task name guessed here doesn't exist (plugin internals may differ), discover the real one with `./gradlew :sdk:tasks --all` and use it — then note the correction in the outcome record.
