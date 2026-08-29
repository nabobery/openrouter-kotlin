// AGP 9 built-in Kotlin: apply `com.android.application` alone (no `org.jetbrains.kotlin.android`).
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "samples.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nabobery.openrouter.samples"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    // Consumes the JVM variant of :sdk (the same platform-type compatibility every Android app relies on for
    // kotlinx-serialization-json); the deferred Android :sdk target is not required.
    implementation(project(":sdk"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
