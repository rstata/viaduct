plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":model"))
    implementation(project(":semantics"))
    implementation(testFixtures(project(":model")))
    implementation("com.graphql-java:graphql-java:26.0")

    testFixturesImplementation(project(":model"))
    testFixturesImplementation(testFixtures(project(":model")))
    testFixturesImplementation("com.graphql-java:graphql-java:26.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testFixturesImplementation(testFixtures(viaductLibs.viaduct.engine.api))
    testFixturesImplementation(testFixtures(viaductLibs.viaduct.shared.graphql))
    testFixturesImplementation(kotlin("test"))

    testImplementation(kotlin("test-junit5"))
    testImplementation(testFixtures(viaductLibs.viaduct.engine.api))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
