// Shared publication configuration for :sdk and :testing (ADR 0006). Applied AFTER the `kotlin {}` block with
//   extra["openrouterArtifactId"] = "openrouter-kotlin"      (or "openrouter-kotlin-testing")
//   extra["openrouterArtifactDescription"] = "…"
//   apply(from = rootProject.file("gradle/openrouter-publication.gradle.kts"))
// Gradle-core plugins only: nothing third-party joins the buildscript classpath next to the sdkgen plugin.
import org.gradle.api.attributes.Attribute
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension

apply(plugin = "maven-publish")
apply(plugin = "signing")

val artifactBase: String = extra["openrouterArtifactId"] as String
val artifactDescription: String = extra["openrouterArtifactDescription"] as String
val releaseBuild: Provider<Boolean> =
    providers.gradleProperty("openrouter.release").map { it.toBoolean() }.orElse(false)
val signingKey: Provider<String> =
    providers.environmentVariable("GPG_SIGNING_KEY").orElse(providers.gradleProperty("signingInMemoryKey"))
val signingPassphrase: Provider<String> =
    providers.environmentVariable("GPG_SIGNING_PASSPHRASE").orElse(providers.gradleProperty("signingInMemoryKeyPassword"))

if (releaseBuild.get()) {
    check(signingKey.isPresent && signingPassphrase.isPresent) {
        "openrouter.release=true requires GPG_SIGNING_KEY and GPG_SIGNING_PASSPHRASE (or the signingInMemoryKey* properties)."
    }
}

// The lightweight javadoc-jar payload produced by :publication:dokka (a small overview pointing at the Pages-hosted
// full Dokka site — see that project for why the full ~100 MB site is NOT embedded per publication). Consumed through
// a configuration so no cross-project task reference is needed and the dependency stays configuration-cache clean.
val dokkaHtml: Configuration = configurations.create("openrouterDokkaJavadoc") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes { attribute(Attribute.of("com.nabobery.openrouter.dokka", String::class.java), "javadoc") }
}
dependencies { dokkaHtml(project(":publication:dokka")) }

configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(artifactBase)
            description.set(artifactDescription)
            url.set("https://github.com/nabobery/openrouter-kotlin")
            inceptionYear.set("2026")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("nabobery")
                    name.set("Avinash Changrani")
                    url.set("https://github.com/nabobery")
                }
            }
            scm {
                url.set("https://github.com/nabobery/openrouter-kotlin")
                connection.set("scm:git:https://github.com/nabobery/openrouter-kotlin.git")
                developerConnection.set("scm:git:ssh://git@github.com/nabobery/openrouter-kotlin.git")
            }
            issueManagement {
                system.set("GitHub")
                url.set("https://github.com/nabobery/openrouter-kotlin/issues")
            }
        }
        // Maven Central requires a -javadoc.jar per artifact. One jar per publication (distinct task + file name)
        // so signing tasks never share an output; content is the Dokka HTML site (accepted practice for Kotlin).
        val publicationName = name
        val javadocJar = tasks.register<Jar>("${publicationName}JavadocJar") {
            group = "publishing"
            archiveClassifier.set("javadoc")
            archiveFileName.set("$artifactBase-$publicationName-${project.version}-javadoc.jar")
            from(dokkaHtml)
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }
        artifact(javadocJar)
    }
}

// KMP names publications after the Gradle project (`sdk`, `sdk-jvm`, `sdk-iosarm64`, …) and finalizes the native
// ones in its own projects-evaluated callback, so the ADR 0006 artifactId rewrite must run at this point too.
val publicationProject = project // captured: inside projectsEvaluated the receiver is `Gradle`, not the project
val projectName = project.name
gradle.projectsEvaluated {
    publicationProject.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            artifactId = artifactId.replaceFirst(projectName, artifactBase)
        }
    }
}

configure<SigningExtension> {
    isRequired = releaseBuild.get()
    if (signingKey.isPresent && signingPassphrase.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassphrase.get())
        sign(the<PublishingExtension>().publications)
    }
}
