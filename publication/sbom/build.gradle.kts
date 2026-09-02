// CycloneDX SBOM for what a JVM consumer of io.github.nabobery:openrouter-kotlin resolves at runtime. A sibling
// project for the same reason as :publication:dokka — cyclonedx-core-java brings Jackson, which must never share a
// classloader with the sdkgen plugin. The SBOM describes the JVM runtime graph (the only Gradle-resolvable graph
// common to every target); native/JS klib graphs carry the same coordinates by construction (one version, ADR 0006).
import org.cyclonedx.Version
import org.cyclonedx.model.Component

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.cyclonedx)
}
kotlin { jvmToolchain(25) }
dependencies {
    implementation(project(":sdk"))
    implementation(project(":openrouter-kotlin-testing"))
}

tasks.cyclonedxDirectBom {
    includeConfigs.set(listOf("runtimeClasspath"))
    // Enum-typed since 3.0.0 (verified against the 3.4.1 classes): no string overloads exist.
    projectType.set(Component.Type.LIBRARY)
    schemaVersion.set(Version.VERSION_16)
    // Both formats are always written; each output is a RegularFileProperty (there is no outputFormat/destination).
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx-direct/bom.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx-direct/bom.xml"))
    // Identify the subject as the published coordinate, not the tooling project.
    componentName.set("openrouter-kotlin")
    componentVersion.set(providers.gradleProperty("openrouter.version").get())
}
