plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.benchmark)
    // JMH (the JVM benchmark backend) needs the @State classes open; allopen opens kotlinx-benchmark's @State.
    alias(libs.plugins.kotlin.allopen)
}

allOpen {
    annotation("kotlinx.benchmark.State")
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":sdk"))
            // FakeTransport + fixtures are the deterministic "server" (no network, no real engine).
            implementation(libs.sdkgen.testing)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.benchmark.runtime)
        }
    }
}

benchmark {
    targets {
        register("jvm")
        register("macosArm64")
        register("linuxX64")
    }
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
    }
}
