plugins {
    kotlin("jvm")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("com.google.guava:guava:33.3.1-jre")

    implementation("com.graphql-java:graphql-java:26.0")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")

    testFixturesImplementation("com.google.inject:guice:7.0.0")

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
