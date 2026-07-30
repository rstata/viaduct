pluginManagement {
    plugins {
        kotlin("jvm") version "2.2.21"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "qplanning"

include("arbitrary", "model", "semantics")
