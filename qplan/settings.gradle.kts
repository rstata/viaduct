pluginManagement {
    plugins {
        kotlin("jvm") version "2.1.21"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "qplanning"

include("model")
