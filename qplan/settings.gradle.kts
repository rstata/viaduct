pluginManagement {
    plugins {
        kotlin("jvm") version "2.2.21"
        id("me.champeau.jmh") version "0.7.3"
        id("io.gitlab.arturbosch.detekt") version "1.23.7"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("viaductLibs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "qplanning"

includeBuild("../core")
includeBuild("build-logic") { name = "detekt-build-logic" }

include("arbitrary", "execution", "model", "semantics")
