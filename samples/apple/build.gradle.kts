plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    macosArm64 {
        binaries.executable {
            entryPoint = "samples.main"
        }
    }

    sourceSets {
        macosMain.dependencies {
            implementation(project(":sdk"))
            implementation(libs.ktor.client.darwin)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
