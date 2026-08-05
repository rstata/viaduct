plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":model"))

    testImplementation(project(":arbitrary"))
    testImplementation(testFixtures(project(":model")))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    filter {
        excludeTestsMatching("semantics.resolver03.ResolverStressTest")
        excludeTestsMatching("semantics.resolver04.ResolverStressTest")
    }
}

val resolver03StressCases =
    providers.environmentVariable("RESOLVER03_STRESS_CASES").orElse("10000")
val resolver03StressSeed =
    providers
        .gradleProperty("resolver03StressSeed")
        .orElse(providers.systemProperty("resolver03.stress.seed"))
        .orElse(providers.environmentVariable("RESOLVER03_STRESS_SEED"))

tasks.register<org.gradle.api.tasks.testing.Test>("resolver03Stress") {
    group = "verification"
    description = "Runs the seeded Resolver03 deep stress property."
    maxHeapSize = "2g"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("semantics.resolver03.ResolverStressTest")
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val seed =
            resolver03StressSeed.orNull
                ?: throw GradleException(
                    "Set -Presolver03StressSeed=<long>, -Dresolver03.stress.seed=<long>, " +
                        "or RESOLVER03_STRESS_SEED=<long>",
                )
        systemProperty("resolver03.stress.cases", resolver03StressCases.get())
        systemProperty("resolver03.stress.seed", seed)
    }
}

val resolver04StressCases =
    providers.environmentVariable("RESOLVER04_STRESS_CASES").orElse("10000")
val resolver04StressSeed =
    providers
        .gradleProperty("resolver04StressSeed")
        .orElse(providers.systemProperty("resolver04.stress.seed"))
        .orElse(providers.environmentVariable("RESOLVER04_STRESS_SEED"))

tasks.register<org.gradle.api.tasks.testing.Test>("resolver04Stress") {
    group = "verification"
    description = "Runs the seeded Resolver04 deep stress property."
    maxHeapSize = "2g"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("semantics.resolver04.ResolverStressTest")
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val seed =
            resolver04StressSeed.orNull
                ?: throw GradleException(
                    "Set -Presolver04StressSeed=<long>, -Dresolver04.stress.seed=<long>, " +
                        "or RESOLVER04_STRESS_SEED=<long>",
                )
        systemProperty("resolver04.stress.cases", resolver04StressCases.get())
        systemProperty("resolver04.stress.seed", seed)
    }
}
