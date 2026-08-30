plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Compile everywhere; link the executable only on the matching host (linking needs the platform sysroot).
    // The default hierarchy groups linuxX64 + linuxArm64 under `linuxMain` (CIO) and mingwX64 under `mingwMain`
    // (WinHttp).
    linuxX64 { binaries.executable { entryPoint = "samples.main" } }
    linuxArm64 { binaries.executable { entryPoint = "samples.main" } }
    mingwX64 { binaries.executable { entryPoint = "samples.main" } }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":sdk"))
            implementation(libs.kotlinx.coroutines.core)
        }
        linuxMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        mingwMain.dependencies {
            implementation(libs.ktor.client.winhttp)
        }
    }
}
