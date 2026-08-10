pluginManagement {
    plugins {
        kotlin("jvm") version "2.2.21"
        id("me.champeau.jmh") version "0.7.3"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "qplanning"

include("arbitrary", "model", "semantics")
