pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        // For the Paper nodes' paperweight plugin.
        maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

stonecutter {
    create(rootProject) {
        versions("26.2", "26.3")
        // The Paper nodes build the same sources as a plugin, against a Paper dev bundle. The
        // project name carries the loader so the Minecraft version stays the one the conditions use.
        version("26.2-paper", "26.2").buildscript("paper.gradle.kts")
        version("26.3-paper", "26.3").buildscript("paper.gradle.kts")
        vcsVersion = "26.3"
    }
}

rootProject.name = "carpet-pvp"
