// Apple consumer of the PUBLISHED coordinates (root artifacts only — module metadata selects each apple variant).
// macosArm64 links + runs on an Apple-silicon host; iosSimulatorArm64 links (its executable needs a booted simulator
// to run, which the matrix does not require).
plugins {
    kotlin("multiplatform")
}

val openrouterVersion: String = providers.gradleProperty("openrouterVersion").get()

kotlin {
    macosArm64 { binaries.executable() }
    iosSimulatorArm64 { binaries.executable() }
    sourceSets {
        commonMain {
            kotlin.srcDir("../shared")
            dependencies {
                implementation("io.github.nabobery:openrouter-kotlin:$openrouterVersion")
                implementation("io.github.nabobery:openrouter-kotlin-testing:$openrouterVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
    }
}
