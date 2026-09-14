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
    versionCatalogs {
        create("viaductLibs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "qplanning"

includeBuild("../core")
includeBuild("spec") {
    name = "graphql-spec"
}

include("arbitrary", "execution", "model", "semantics")
