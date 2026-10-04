import java.net.URI
import java.time.Duration

plugins {
    id("dev.kikugie.stonecutter")
    id("io.papermc.paperweight.userdev") version "2.0.0-SNAPSHOT"
    id("java")
}

// The Paper build of the bot features. One source tree, two compilers: the Fabric nodes in
// versions/26.x build the whole mod, this node builds the shared bot code and the plugin that runs
// it. The successful compile of the shared packages is what proves they no longer reach into
// Carpet's rules, mixins or utils.

val mcVersion = stonecutter.current.version
val mcProject = stonecutter.current.project

version = property("mod_version") as String
group = property("maven_group") as String
base.archivesName = "${property("archives_base_name")}-$mcProject"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // The dev bundle carries the Paper server in Mojang's class names, which is what the shared code
    // is written against. Its version is <minecraft>.build.<n>-<channel>, with both in gradle.properties.
    paperweight.paperDevBundle("${mcVersion}.build.${property("paper_build")}-${property("paper_channel")}")
}

// Which sources a Paper server can have: the bot code with no loader in it, the fake player and its
// action pack, and the plugin itself. Everything else (Carpet's rules, Scarpet, the mixins) is
// Fabric-only and is not compiled here.
val pluginSources = sourceSets["main"]
pluginSources.java.include(
        "carpet/pvp/**",
        "carpet/patches/EntityPlayerMPFake.java",
        "carpet/patches/FakeClientConnection.java",
        "carpet/patches/NetHandlerPlayServerFake.java",
        "carpet/helpers/EntityPlayerActionPack.java",
        "carpet/script/utils/Tracer.java",
        "carpet/utils/DelayedTasks.java",
        "carpet/paper/**")
// Fabric entry points sitting inside the shared package: they need the mod loader.
pluginSources.java.exclude("carpet/pvp/PvpInitializer.java", "carpet/pvp/PvpClientInitializer.java")
// The built-in kits and the plugin's own files; the mod's other resources belong to the mod.
pluginSources.resources.srcDir(rootProject.file("src/paper/resources"))
pluginSources.resources.include("assets/carpet/kits/**", "paper-plugin.yml", "config.yml")

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "2000"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)

    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

// Nothing compiles against the dev bundle before paperweight has unpacked and patched it.
tasks.compileJava { dependsOn("paperweightUserdevSetup") }
tasks.processResources { dependsOn("paperweightUserdevSetup") }

// The unit tests belong to the mod: they cover Carpet's rules and scripting, which this node does
// not compile. The Paper build is checked by the self-test task below.
tasks.test { enabled = false }
tasks.compileTestJava { enabled = false }

tasks.jar {
    val archivesName = base.archivesName
    inputs.property("archivesName", archivesName)

    from(rootProject.file("LICENSE")) {
        rename { "${it}_${archivesName.get()}" }
    }
}

// The server jar the self-test boots. Paper keeps every build on its own download service; the
// build number is pinned in gradle.properties so a run is reproducible.
val serverBuild = property("paper_server_build") as String
val serverJar = layout.buildDirectory.file("paper/paper-$mcVersion-$serverBuild.jar")

val downloadServer = tasks.register("downloadPaperServer") {
    group = "build"
    description = "Downloads the Paper server jar the self-test runs on."
    val mc = mcVersion
    val build = serverBuild
    val target = serverJar
    inputs.property("minecraft", mc)
    inputs.property("build", build)
    outputs.file(target)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val info = groovy.json.JsonSlurper().parse(
                URI("https://fill.papermc.io/v3/projects/paper/versions/$mc/builds/$build").toURL())
                as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val downloads = info["downloads"] as Map<String, Map<String, Any>>
        val url = downloads["server:default"]!!["url"] as String
        val file = target.get().asFile
        file.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input -> input.copyTo(file.outputStream()) }
    }
}

// Headless self-test on a real Paper server: the plugin jar is dropped into its plugins folder, the
// scenarios run from the plugin's own tick task, and a failed scenario fails this task.
val selfTest = tasks.register<Exec>("runSelfTest") {
    group = "verification"
    description = "Runs the headless self-test on a Paper server."

    val dir = rootProject.file("run/selftest-$mcProject")
    val report = dir.resolve("selftest-report.json")
    val pluginJar = tasks.jar.get().archiveFile.get().asFile
    val server = serverJar.get().asFile
    val requested = providers.gradleProperty("selfTest").getOrElse("all")

    dependsOn(downloadServer, tasks.jar, tasks.classes)
    inputs.dir(rootProject.file("src/paper/resources"))
    inputs.file(pluginJar)
    outputs.file(report)

    workingDir = dir
    commandLine("java", "-Xmx2G", "-Dcarpet.selftest=$requested", "-jar", server, "nogui")
    timeout = Duration.ofMinutes(20)

    doFirst {
        dir.resolve("world").deleteRecursively()
        dir.resolve("plugins").deleteRecursively()
        report.delete()
        dir.mkdirs()
        dir.resolve("eula.txt").writeText("eula=true\n")
        dir.resolve("server.properties").writeText(
                """
                server-port=0
                online-mode=false
                level-type=minecraft:flat
                generate-structures=false
                difficulty=peaceful
                """.trimIndent() + "\n"
        )
        dir.resolve("plugins").mkdirs()
        pluginJar.copyTo(dir.resolve("plugins/${pluginJar.name}"))
    }
    // A server that dies before the runner finishes may still exit with 0, so the report decides.
    doLast {
        if (!report.isFile) throw GradleException("Self-test wrote no report: $report")
        @Suppress("UNCHECKED_CAST")
        val written = groovy.json.JsonSlurper().parse(report) as Map<String, Any>
        val scenarios = written["scenarios"] as List<Map<String, Any>>
        val failed = scenarios.filter { it["passed"] != true }
        if (written["passed"] != true) {
            failed.forEach { logger.error("FAIL ${it["name"]} after ${it["ticks"]} ticks: ${it["detail"]}") }
            throw GradleException("Paper self-test: ${scenarios.size - failed.size} of ${scenarios.size} scenarios passed")
        }
        logger.lifecycle("Paper self-test: ${scenarios.size} of ${scenarios.size} scenarios passed")
    }
}
