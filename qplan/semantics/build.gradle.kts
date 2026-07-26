plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":model"))

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
