pluginManagement {
    plugins {
        kotlin("jvm") version "2.2.21"
    }

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "detekt-build-logic"

include(":detekt-rules")
