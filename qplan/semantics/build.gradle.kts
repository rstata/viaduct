plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":model"))
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")

    testImplementation(kotlin("test-junit5"))
    testImplementation("com.google.inject:guice:7.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
