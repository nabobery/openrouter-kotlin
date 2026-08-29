plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    // Sample app (not a published library): build and target the toolchain JDK uniformly for Kotlin and Java.
    jvmToolchain(25)
}

application {
    mainClass.set("samples.MainKt")
}

dependencies {
    implementation(project(":sdk"))
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
}
