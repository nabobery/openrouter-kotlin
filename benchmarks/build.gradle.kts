plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.benchmark)
    // JMH (the JVM benchmark backend) needs the @State classes open; allopen opens kotlinx-benchmark's @State.
    alias(libs.plugins.kotlin.allopen)
}

// The kotlinx-benchmark JAR is an implementation artifact, not a guaranteed fat JAR. Reuse the plugin-managed
// JavaExec classpath when launching JMH with profilers so every benchmark runtime dependency is present on clean CI
// runners as well as locally.
tasks.register<org.gradle.api.tasks.JavaExec>("jvmBenchmarkJmh") {
    group = "benchmark"
    description = "Execute the JVM JMH benchmark with the plugin-managed runtime classpath."
    dependsOn("jvmBenchmarkCompile")
    mainClass.set("org.openjdk.jmh.Main")
    workingDir(rootProject.projectDir)
}

// The benchmark plugin registers its target tasks from an evaluation callback, so attach the classpath after those
// tasks exist while keeping the public task itself registered for Gradle task discovery and configuration cache.
afterEvaluate {
    val jvmBenchmarkTask = tasks.named<org.gradle.api.tasks.JavaExec>("jvmBenchmark")
    tasks.named<org.gradle.api.tasks.JavaExec>("jvmBenchmarkJmh") {
        classpath(jvmBenchmarkTask.get().classpath)
    }
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
