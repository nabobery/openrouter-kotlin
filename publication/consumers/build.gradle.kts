import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec

// Declare every consumer plugin here with `apply false` so all subprojects load them from ONE classloader. Applying
// a KMP plugin independently (with its own version) in sibling subprojects loads it in separate classloaders, and
// Kotlin's shared KotlinNativeBundleBuildService then fails across them ("a plugin being applied to two sibling
// projects and then using a shared build service"). Each subproject applies the plugin below without a version.
plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("jvm") version "2.3.20" apply false
    id("com.android.kotlin.multiplatform.library") version "9.2.1" apply false
}

// The Kotlin/JS Node target normally downloads a Node.js distribution from a plugin-added *project* repository, which
// settings.gradle.kts's FAIL_ON_PROJECT_REPOS forbids. Use the host's Node instead (no download, so the Node setup
// task never resolves against that repository) — keeping the isolation contract strict: only the repository under
// test serves io.github.nabobery:openrouter-kotlin*. CI runners ship Node; a local run needs `node` on PATH.
allprojects {
    plugins.withType(NodeJsPlugin::class.java) {
        the<NodeJsEnvSpec>().download.set(false)
    }
    // The JS pipeline also provisions Yarn from a plugin-added repository; disable that download for the same reason.
    plugins.withType(YarnPlugin::class.java) {
        the<YarnRootEnvSpec>().download.set(false)
    }
}
