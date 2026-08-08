plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":model"))
    api(testFixtures(project(":model")))
    api("io.kotest:kotest-property-jvm:5.9.1")

    implementation("com.graphql-java:graphql-java:26.0")

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
}
