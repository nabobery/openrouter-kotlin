import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// A tiny, sample-OWNED facade over :sdk, exported as an XCFramework for a Swift consumer. Exporting :sdk itself as
// an Objective-C framework would emit headers for ~1,850 generated classes — a real compile-time and binary-size
// hazard, and an API surface nobody designed for Swift. Real KMP apps consume a library through their own shared
// module; this sample does the same. macosArm64 is included so the facade can be exercised without a simulator.
kotlin {
    val xcf = XCFramework("OpenRouterSample")
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "OpenRouterSample"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":sdk"))
            implementation(libs.ktor.client.darwin)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
