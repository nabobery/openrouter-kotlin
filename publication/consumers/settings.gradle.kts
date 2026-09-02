// Standalone Gradle build that resolves the PUBLISHED openrouter-kotlin coordinates from a repository under test
// (an isolated file: repo, or a validated Central deployment) — never project(":sdk"). Isolation is the contract:
// FAIL_ON_PROJECT_REPOS forbids a project repo, and an exclusiveContent rule pins io.github.nabobery:openrouter-kotlin*
// to the repository under test while everything else (kotlin-sdkgen-*, kotlinx, ktor) still resolves from Central,
// exactly as a real consumer would see it.
pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }

val repositoryUrl: String = providers.gradleProperty("consumerRepository").orNull
    ?: error("Pass -PconsumerRepository=<file:///…/build/publication-repository/ | https://central.sonatype.com/api/v1/publisher/deployments/download/>")
val centralToken: String? = providers.environmentVariable("CENTRAL_TOKEN").orNull // base64(username:password)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "underTest"
                    url = uri(repositoryUrl)
                    // A validated Central deployment needs the Portal token; a public repository (repo1.maven.org)
                    // and a local file: repository need none. Attach header credentials only when a token is present.
                    if (repositoryUrl.startsWith("https://") && centralToken != null) {
                        credentials(HttpHeaderCredentials::class) {
                            name = "Authorization"
                            value = "Bearer $centralToken"
                        }
                        authentication { create<HttpHeaderAuthentication>("header") }
                    }
                }
            }
            // Only the artifacts under test come from the repository under test; kotlin-sdkgen-* and everything else
            // must still resolve from Central, exactly as a real consumer would see it.
            filter { includeModuleByRegex("io\\.github\\.nabobery", "openrouter-kotlin(-.*)?") }
        }
        mavenCentral()
        google()
    }
}
rootProject.name = "openrouter-kotlin-consumers"
include(":jvm", ":js", ":native", ":apple")
if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null) include(":android")
