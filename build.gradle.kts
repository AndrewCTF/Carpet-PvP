import java.time.Duration

plugins {
    id("net.fabricmc.fabric-loom")
    id("maven-publish")
}

val mcVersion = stonecutter.current.version

version = property("mod_version") as String
group = property("maven_group") as String
base.archivesName = "${property("archives_base_name")}-$mcVersion"

loom {
    accessWidenerPath = rootProject.file("src/main/resources/carpet.accesswidener")

    runConfigs.configureEach {
        // One world per Minecraft version (an older server cannot open a newer world), and
        // separate client, server and self-test directories so they can run at once.
        runDir(if (name == "selfTest") "../../run/selftest-$mcVersion" else "../../run/$mcVersion/$name")
        // -PmixinAudit: fail at boot if any mixin no longer matches its target.
        if (providers.gradleProperty("mixinAudit").isPresent) vmArg("-Dcarpet.mixinAudit=true")
    }
    runs {
        // Headless self-test, see carpet.pvp.selftest. -PselfTest=a,b picks scenarios.
        create("selfTest") {
            server()
            jvmArguments.addAll(
                "-Dcarpet.selftest=${providers.gradleProperty("selfTest").getOrElse("all")}",
                "-Dcarpet.mixinAudit=true"
            )
            generateRunConfig = false
        }
    }
}

dependencies {
    // Per-version dependency versions live in versions/<minecraft>/gradle.properties
    minecraft("com.mojang:minecraft:$mcVersion")

    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

    // Jakarta annotations (replacement for javax.annotation removed from Java)
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // One test JVM per core
    maxParallelForks = Runtime.getRuntime().availableProcessors()
}

tasks.named<JavaExec>("runSelfTest") {
    val dir = rootProject.file("run/selftest-$mcVersion")
    val report = dir.resolve("selftest-report.json")
    timeout = Duration.ofMinutes(10)
    doFirst {
        dir.resolve("world").deleteRecursively()
        report.delete()
        dir.mkdirs()
        dir.resolve("eula.txt").writeText("eula=true\n")
        // Port 0: the OS hands out a free port, so parallel runs and other dev servers cannot collide.
        dir.resolve("server.properties").writeText(
            """
            server-port=0
            online-mode=false
            level-type=minecraft:flat
            generate-structures=false
            difficulty=peaceful
            pause-when-empty-seconds=0
            """.trimIndent() + "\n"
        )
    }
    // A server that dies before the runner finishes may still exit with 0.
    doLast {
        if (!report.isFile) throw GradleException("Self-test wrote no report: $report")
    }
}

// The CarpetLogic web editor's JavaScript has its own tests. check runs them when node is installed.
val webuiTest = tasks.register<Exec>("webuiTest") {
    group = "verification"
    description = "Tests the CarpetLogic web editor's graph compiler with node."
    val node = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .flatMap { dir -> listOf(File(dir, "node"), File(dir, "node.exe")) }
        .firstOrNull { it.canExecute() }
    val tests = fileTree(rootProject.file("src/test/js")) { include("*.test.js") }
    inputs.files(tests)
    inputs.dir(rootProject.file("src/main/resources/webui"))
    inputs.dir(rootProject.file("src/main/resources/carpetlogic"))
    onlyIf {
        if (node == null) logger.lifecycle("webuiTest skipped: node is not on the PATH")
        node != null
    }
    commandLine(listOf(node?.path ?: "node", "--test") + tests.files.map { it.path })
}

tasks.check {
    dependsOn(webuiTest)
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

    // JSON cannot carry version conditions, so mixins that have no target in this Minecraft
    // version are listed in versions/<minecraft>/gradle.properties and dropped from the config here.
    val excludedMixins = (findProperty("excluded_mixins") as String? ?: "")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    inputs.property("excludedMixins", excludedMixins)
    val mixinConfig = destinationDir.resolve("carpet.mixins.json")
    doLast {
        if (excludedMixins.isEmpty()) return@doLast
        @Suppress("UNCHECKED_CAST")
        val config = groovy.json.JsonSlurper().parse(mixinConfig) as MutableMap<String, Any?>
        for (key in listOf("mixins", "client", "server")) {
            (config[key] as? MutableList<*>)?.removeAll(excludedMixins)
        }
        mixinConfig.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(config)))
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
