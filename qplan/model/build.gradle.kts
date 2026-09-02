import io.gitlab.arturbosch.detekt.Detekt

plugins {
    kotlin("jvm")
    `java-library`
    `java-test-fixtures`
    id("io.gitlab.arturbosch.detekt")
}

dependencies {
    api(viaductLibs.viaduct.engine.api)
    api(viaductLibs.viaduct.shared.viaductschema)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testFixturesImplementation("com.graphql-java:graphql-java:26.0")
    testFixturesImplementation("com.google.inject:guice:7.0.0")
    testFixturesImplementation("jakarta.inject:jakarta.inject-api:2.0.1")
    testFixturesApi(viaductLibs.viaduct.shared.graphql)
    testFixturesApi(viaductLibs.viaduct.shared.utils)
    testFixturesApi(viaductLibs.viaduct.shared.viaductschema)

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    detektPlugins("detekt.build-logic:detekt-rules")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

// Only our own rule is active here (config.setFrom + disableDefaultRuleSets).
detekt {
    disableDefaultRuleSets = true
    config.setFrom(rootProject.layout.projectDirectory.file("detekt-engine-data.yml"))
    ignoreFailures = true
}

// The plain `detekt` task has no classpath, so it skips @RequiresTypeResolution rules. These
// per-compilation tasks do, but aren't wired into `check` by the plugin.
val typeResolvedDetektTasks = tasks.withType<Detekt>().matching {
    it.name in setOf("detektMain", "detektTest", "detektTestFixtures")
}
tasks.named("check") {
    dependsOn(typeResolvedDetektTasks)
}
