plugins {
    kotlin("jvm") version "2.1.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.setSrcDirs(listOf("model"))
    }
}
