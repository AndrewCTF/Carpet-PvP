plugins {
    id("net.fabricmc.fabric-loom")
    id("maven-publish")
}

val mcVersion = stonecutter.current.version

version = property("mod_version") as String
group = property("maven_group") as String
base.archivesName = "${property("archives_base_name")}-$mcVersion"

loom {
    runConfigs.configureEach {
        // One world per Minecraft version: an older server cannot open a newer world.
        runDir("../../run/$mcVersion")
        // -PmixinAudit: fail at boot if any mixin no longer matches its target.
        if (providers.gradleProperty("mixinAudit").isPresent) vmArg("-Dcarpet.mixinAudit=true")
    }
}

dependencies {
    // Per-version dependency versions live in versions/<minecraft>/gradle.properties
    minecraft("com.mojang:minecraft:$mcVersion")

    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

    // Jakarta annotations (replacement for javax.annotation removed from Java)
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "minecraft_dependency" to project.property("minecraft_dependency")
    )
    inputs.properties(props)

    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    // javac stops at 100 errors by default, which hides most of a version port's work list.
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "2000"))
}

java {
    withSourcesJar()

    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.jar {
    val archivesName = base.archivesName
    inputs.property("archivesName", archivesName)

    from(rootProject.file("LICENSE")) {
        rename { "${it}_${archivesName.get()}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }

    repositories {
        // Add repositories to publish to here.
    }
}
