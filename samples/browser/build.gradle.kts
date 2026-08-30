plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js {
        browser {
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
