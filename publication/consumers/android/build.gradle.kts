// Android consumer of the PUBLISHED coordinates (root artifacts only — module metadata selects the android variant).
// A com.android.kotlin.multiplatform.library module (the path AGP 9.2.1 + KGP 2.3.20 support; the same one :sdk
// uses); its JVM-hosted androidHostTest lane runs the smoke test, proving both artifacts resolve for Android.
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val openrouterVersion: String = providers.gradleProperty("openrouterVersion").get()

kotlin {
    android {
        namespace = "consumers.android"
        compileSdk = 36
        minSdk = 26
        @Suppress("UnstableApiUsage")
        withHostTestBuilder {}
    }
    sourceSets {
        androidMain {
            kotlin.srcDir("../shared")
            dependencies {
                implementation("io.github.nabobery:openrouter-kotlin:$openrouterVersion")
                implementation("io.github.nabobery:openrouter-kotlin-testing:$openrouterVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        val androidHostTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
}
