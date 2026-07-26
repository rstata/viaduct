plugins {
    kotlin("jvm")
}

dependencies {
    implementation("com.graphql-java:graphql-java:26.0")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")

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
