plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":model"))
    implementation(testFixtures(project(":model")))
    implementation("com.graphql-java:graphql-java:26.0")
    implementation(viaductLibs.viaduct.shared.arbitrary)

    testFixturesImplementation(project(":model"))
    testFixturesImplementation(testFixtures(project(":model")))
    testFixturesImplementation("com.graphql-java:graphql-java:26.0")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
