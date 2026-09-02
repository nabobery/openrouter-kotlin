// Compiled documentation snippets. Every guide example under docs/guides/**/*.md is injected (kt -> md) from a
// `// region` block in this module by scripts/docs-snippets.py, so the examples in the docs are real Kotlin that
// `samplesCheck` / CI compile — a guide can never drift from a compiling API. Nothing here is executed (no network
// in CI); the module only has to compile. See docs/README.md (Decision: kt -> md, not Knit).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":sdk"))
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    // The fake-transport guide compiles against the openrouter-kotlin-testing artifact, exactly as a consumer's
    // tests would (it re-exports kotlin-sdkgen-testing plus the OpenRouter-specific fake and fixtures).
    implementation(project(":openrouter-kotlin-testing"))
}
