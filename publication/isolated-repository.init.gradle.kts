// Rewires every maven-publish project to a file repository so a release can be staged, inspected, and consumed
// without touching ~/.m2 or any remote. Usage:
//   ./gradlew publishAllPublicationsToIsolatedRepository --init-script publication/isolated-repository.init.gradle.kts \
//       [-PpublicationRepository=build/publication-repository]
import org.gradle.api.publish.PublishingExtension

val publicationRepositoryPath = providers.gradleProperty("publicationRepository").orElse("build/publication-repository")
gradle.allprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "isolated"
                    url = gradle.rootProject.layout.projectDirectory.dir(publicationRepositoryPath.get()).asFile.toURI()
                }
            }
        }
    }
}
