plugins {
    kotlin("jvm")
    `java-test-fixtures`
    id("me.champeau.jmh")
}

dependencies {
    implementation(project(":model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testFixturesImplementation(project(":arbitrary"))
    testFixturesImplementation(testFixtures(project(":model")))
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testFixturesImplementation(kotlin("test-junit5"))

    testImplementation(project(":arbitrary"))
    testImplementation(testFixtures(project(":model")))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add("jmhImplementation", sourceSets["testFixtures"].output)
}

configurations.named("jmhImplementation") {
    extendsFrom(configurations["testFixturesImplementation"])
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    jvmToolchain(21)
}

val stressResolverNames =
    listOf(
        "resolver03",
        "resolver08",
        "resolver09",
        "resolver10",
        "resolver23",
        "resolver24",
        "resolver24i",
        "resolver25",
    )

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    filter {
        stressResolverNames.forEach { resolverName ->
            excludeTestsMatching("semantics.$resolverName.ResolverStressTest")
        }
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
        "object-fragment-from-object-field" to
            "generated object fragment worlds with fromObjectField resolve correctly",
        "mixed-variables" to
            "generated mixed resolver variable worlds resolve correctly",
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

val resolver24ProfileSize =
    providers.gradleProperty("resolver24ProfileSize").orElse("50:2:5")
val resolver24ProfileSeed =
    providers.gradleProperty("resolver24ProfileSeed").orElse("20260810")
val resolver24ProfileRecording =
    rootProject.layout.buildDirectory.file("reports/resolver24-profile/resolver24-properties.jfr")

tasks.register<org.gradle.api.tasks.testing.Test>("resolver24PropertyProfile") {
    group = "verification"
    description = "Profiles the generated Resolver24 properties with Java Flight Recorder."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("semantics.resolver24.ResolverGeneratedTest")
    }
    inputs.property("resolver24ProfileSize", resolver24ProfileSize)
    inputs.property("resolver24ProfileSeed", resolver24ProfileSeed)
    outputs.file(resolver24ProfileRecording)
    outputs.upToDateWhen { false }

    doFirst {
        val size = resolver24ProfileSize.get()
        require(size.matches(Regex("""[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*"""))) {
            "resolver24ProfileSize must have S:R:Q form with positive integers: $size"
        }
        val seed = resolver24ProfileSeed.get()
        seed.toLongOrNull()
            ?: throw GradleException("resolver24ProfileSeed must be a Long: $seed")

        val recording = resolver24ProfileRecording.get().asFile
        recording.parentFile.mkdirs()
        recording.delete()
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.size", size)
        systemProperty("resolver.property.seed", seed)
        systemProperty("kotest.proptest.default.seed", seed)
        jvmArgs(
            "-XX:StartFlightRecording=" +
                "filename=${recording.absolutePath},settings=profile,dumponexit=true",
        )
    }
}

fun registerResolverStressTask(resolverName: String) {
    val displayName = resolverName.replaceFirstChar(Char::uppercase)
    val environmentPrefix = resolverName.uppercase()
    val cases =
        providers.environmentVariable("${environmentPrefix}_STRESS_CASES").orElse("10000")
    val seed =
        providers
            .gradleProperty("${resolverName}StressSeed")
            .orElse(providers.systemProperty("$resolverName.stress.seed"))
            .orElse(providers.environmentVariable("${environmentPrefix}_STRESS_SEED"))

    tasks.register<org.gradle.api.tasks.testing.Test>("${resolverName}Stress") {
        group = "verification"
        description = "Runs the seeded $displayName deep stress property."
        maxHeapSize = "2g"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform()
        filter {
            includeTestsMatching("semantics.$resolverName.ResolverStressTest")
        }
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
        }

        doFirst {
            val configuredSeed =
                seed.orNull
                    ?: throw GradleException(
                        "Set -P${resolverName}StressSeed=<long>, " +
                            "-D$resolverName.stress.seed=<long>, or " +
                            "${environmentPrefix}_STRESS_SEED=<long>",
                    )
            systemProperty("$resolverName.stress.cases", cases.get())
            systemProperty("$resolverName.stress.seed", configuredSeed)
        }
    }
}

stressResolverNames.forEach(::registerResolverStressTask)
