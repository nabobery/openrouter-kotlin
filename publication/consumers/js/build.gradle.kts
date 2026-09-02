// Node.js consumer of the PUBLISHED coordinates (root artifacts only — module metadata selects the js variant).
plugins {
    kotlin("multiplatform")
}

val openrouterVersion: String = providers.gradleProperty("openrouterVersion").get()

kotlin {
    js {
        nodejs()
        binaries.executable()
    }
    sourceSets {
        val jsMain by getting {
            kotlin.srcDir("../shared")
            dependencies {
                implementation("io.github.nabobery:openrouter-kotlin:$openrouterVersion")
                implementation("io.github.nabobery:openrouter-kotlin-testing:$openrouterVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
    }
}
