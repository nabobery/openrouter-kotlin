plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js {
        nodejs {
            binaries.executable()
        }
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":sdk"))
            implementation(libs.ktor.client.js)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
