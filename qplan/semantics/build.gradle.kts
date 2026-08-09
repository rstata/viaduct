plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":model"))

    testFixturesImplementation(project(":arbitrary"))
    testFixturesImplementation(testFixtures(project(":model")))
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testFixturesImplementation(kotlin("test-junit5"))

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
        excludeTestsMatching("semantics.resolver08.ResolverStressTest")
    }
}

val resolverPropertySeed =
    providers
        .gradleProperty("resolverPropertySeed")
        .orElse(providers.systemProperty("resolver.property.seed"))
        .orElse(providers.environmentVariable("RESOLVER_PROPERTY_SEED"))

tasks.test {
    inputs.property(
        "resolverPropertySeed",
        resolverPropertySeed.orElse("unseeded"),
    )
    outputs.upToDateWhen { resolverPropertySeed.orNull == null }

    doFirst {
        resolverPropertySeed.orNull?.let { configured ->
            configured.toLongOrNull()
                ?: throw GradleException(
                    "Set resolverPropertySeed, resolver.property.seed, or " +
                        "RESOLVER_PROPERTY_SEED to a Long: $configured",
                )
            systemProperty("resolver.property.seed", configured)
            systemProperty("kotest.proptest.default.seed", configured)
        }
    }
}

val resolverPropertyProfiles =
    mapOf(
        "empty-object-fragment" to
            "generated empty object fragment worlds resolve correctly",
        "node" to "generated node worlds resolve correctly",
        "object-fragment" to
            "generated object fragment worlds without variables resolve correctly",
        "object-fragment-from-argument" to
            "generated object fragment worlds with fromArgument resolve correctly",
        "feature-interaction" to "generated full feature interactions resolve correctly",
        "resolver03-construction-witness" to
            "generated construction witness is exact minimal and permutation invariant",
    )
val resolverPropertyReplayClass = providers.gradleProperty("resolverPropertyClass")
val resolverPropertyReplayProfile = providers.gradleProperty("resolverPropertyProfile")
val resolverPropertyReplayCase =
    providers.gradleProperty("resolverPropertyCase").orElse("all")
val resolverPropertyReplaySize = providers.gradleProperty("resolverPropertySize")

tasks.register<org.gradle.api.tasks.testing.Test>("resolverPropertyReplay") {
    group = "verification"
    description = "Replays one generated resolver profile or S:R:Q case."
    maxHeapSize = "2g"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }

    doFirst {
        val className =
            resolverPropertyReplayClass.orNull
                ?: throw GradleException("Set -PresolverPropertyClass=<fully-qualified-class>")
        require(className.matches(Regex("""[A-Za-z_$][A-Za-z0-9_.$]*"""))) {
            "resolverPropertyClass must be a fully qualified JVM class name: $className"
        }
        val profile =
            resolverPropertyReplayProfile.orNull
                ?: throw GradleException(
                    "Set -PresolverPropertyProfile=<profile>; profiles=" +
                        resolverPropertyProfiles.keys.sorted().joinToString(),
                )
        val method =
            resolverPropertyProfiles[profile]
                ?: throw GradleException(
                    "Unknown resolverPropertyProfile $profile; profiles=" +
                        resolverPropertyProfiles.keys.sorted().joinToString(),
                )
        val seed =
            resolverPropertySeed.orNull
                ?: throw GradleException("Set -PresolverPropertySeed=<long>")
        seed.toLongOrNull()
            ?: throw GradleException("resolverPropertySeed must be a Long: $seed")
        val case = resolverPropertyReplayCase.get()
        require(
            case.equals("all", ignoreCase = true) ||
                case.matches(Regex("""[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*""")),
        ) {
            "resolverPropertyCase must be all or S:R:Q with positive integers: $case"
        }
        val size = resolverPropertyReplaySize.orNull
        require(size == null || case.equals("all", ignoreCase = true)) {
            "resolverPropertySize is allowed only when resolverPropertyCase=all"
        }
        require(
            size == null ||
                size.matches(Regex("""[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*""")),
        ) {
            "resolverPropertySize must have S:R:Q form with positive integers: $size"
        }

        filter.includeTestsMatching("$className.$method")
        systemProperty("resolver.property.seed", seed)
        systemProperty("kotest.proptest.default.seed", seed)
        systemProperty("resolver.property.profile", profile)
        systemProperty("resolver.property.case", case)
        size?.let { systemProperty("resolver.property.size", it) }
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

val resolver08StressCases =
    providers.environmentVariable("RESOLVER08_STRESS_CASES").orElse("10000")
val resolver08StressSeed =
    providers
        .gradleProperty("resolver08StressSeed")
        .orElse(providers.systemProperty("resolver08.stress.seed"))
        .orElse(providers.environmentVariable("RESOLVER08_STRESS_SEED"))

tasks.register<org.gradle.api.tasks.testing.Test>("resolver08Stress") {
    group = "verification"
    description = "Runs the seeded Resolver08 deep stress property."
    maxHeapSize = "2g"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("semantics.resolver08.ResolverStressTest")
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val seed =
            resolver08StressSeed.orNull
                ?: throw GradleException(
                    "Set -Presolver08StressSeed=<long>, -Dresolver08.stress.seed=<long>, " +
                        "or RESOLVER08_STRESS_SEED=<long>",
                )
        systemProperty("resolver08.stress.cases", resolver08StressCases.get())
        systemProperty("resolver08.stress.seed", seed)
    }
}
