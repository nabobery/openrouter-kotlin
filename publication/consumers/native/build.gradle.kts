// Kotlin/Native consumer of the PUBLISHED coordinates (root artifacts only — module metadata selects each native
// variant). All three Tier 2 targets compile from any host; the host's own target additionally links + runs.
plugins {
    kotlin("multiplatform")
}

val openrouterVersion: String = providers.gradleProperty("openrouterVersion").get()

kotlin {
    linuxX64 { binaries.executable() }
    linuxArm64 { binaries.executable() }
    mingwX64 { binaries.executable() }
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
