// Android consumer of the SDK's **android** variant. Structured as a `com.android.kotlin.multiplatform.library`
// module rather than a standalone `com.android.application`: with Kotlin 2.3.20's KGP on the (root-inherited)
// classpath, the legacy application path instantiates KGP's `KotlinAndroidTarget`, which references the class
// `com.android.build.gradle.api.BaseVariant` that AGP 9.2.1 removed — a hard NoClassDefFoundError. The KMP-library
// path is the one AGP 9.2.1 + KGP 2.3.20 support (it is the same path `:sdk` uses). This compiles the Activity code
// against the android variant of `:sdk`; restoring a runnable APK app awaits a compatible Kotlin/AGP combination.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // AGP 9.2.1 deprecated the `androidLibrary { }` accessor in favour of `android { }` for the KMP-library plugin.
    android {
        namespace = "samples.android"
        compileSdk = 36
        minSdk = 26
        // Create the JVM-hosted test compilation so the auto-created commonTest source set is attached to one
        // (otherwise KGP warns "Unused Kotlin Source Sets"); the lane carries no tests — the sample only compiles.
        @Suppress("UnstableApiUsage")
        withHostTestBuilder {}
    }

    sourceSets {
        androidMain.dependencies {
            // Resolves the android variant of :sdk (attribute org.jetbrains.kotlin.platform.type=androidJvm).
            implementation(project(":sdk"))
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.activity.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
    }
}
