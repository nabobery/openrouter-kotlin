// JVM consumer of the PUBLISHED coordinates (root artifacts only — module metadata selects the jvm variant).
plugins {
    kotlin("jvm")
    application
}

val openrouterVersion: String = providers.gradleProperty("openrouterVersion").get()

kotlin {
    jvmToolchain(25)
    sourceSets["main"].kotlin.srcDir("../shared")
}

dependencies {
    implementation("io.github.nabobery:openrouter-kotlin:$openrouterVersion")
    implementation("io.github.nabobery:openrouter-kotlin-testing:$openrouterVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

application { mainClass.set("consumers.MainKt") }
