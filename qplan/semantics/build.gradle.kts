plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":model"))
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")

    testImplementation(testFixtures(project(":model")))
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
