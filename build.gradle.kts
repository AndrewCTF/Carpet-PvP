import java.util.concurrent.TimeUnit
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import java.time.Duration

plugins {
    id("maven-publish")
}

val mcVersion = stonecutter.current.version

// 26.1 and up ship unobfuscated, so Loom's no-remap plugin is enough. 1.21.11 is the last
// obfuscated release: it needs the remapping plugin, Mojang's mappings, modImplementation
// for the Fabric dependencies and Java 21 bytecode. Both plugin ids are on the buildscript
// classpath (see stonecutter.gradle.kts). Loom is applied by hand rather than from the
// plugins block, so its DSL is reached through the extension's public type.
val unobfuscated = stonecutter.semantics.eval(mcVersion, ">=26.1")
pluginManager.apply(if (unobfuscated) "net.fabricmc.fabric-loom" else "net.fabricmc.fabric-loom-remap")

// The access widener namespace follows the mappings, so it differs per version.
val accessWidener = rootProject.file(
    if (unobfuscated) "src/main/resources/carpet.accesswidener" else "versions/$mcVersion/carpet.accesswidener"
)

version = property("mod_version") as String
group = property("maven_group") as String
base.archivesName = "${property("archives_base_name")}-$mcVersion"

val loom = extensions.getByType(LoomGradleExtensionAPI::class.java)

loom.runConfigs.configureEach {
    // One world per Minecraft version (an older server cannot open a newer world), and
    // separate client, server and self-test directories so they can run at once.
    runDir = if (name == "selfTest") "../../run/selftest-$mcVersion" else "../../run/$mcVersion/$name"
    // -PmixinAudit: fail at boot if any mixin no longer matches its target.
    if (providers.gradleProperty("mixinAudit").isPresent) jvmArguments.add("-Dcarpet.mixinAudit=true")
}
loom.accessWidenerPath.set(accessWidener)
loom.runConfigs.create("selfTest") {
    // Headless self-test, see carpet.pvp.selftest. -PselfTest=a,b picks scenarios.
    server()
    jvmArguments.addAll(
        "-Dcarpet.selftest=${providers.gradleProperty("selfTest").getOrElse("all")}",
        "-Dcarpet.mixinAudit=true",
        // All self-test servers run at once, so the CarpetLogic web editor gets an ephemeral port
        // instead of the one the rule asks for.
        "-Dcarpet.logicPort=0"
    )
    generateRunConfig.set(false)
}
loom.runConfigs.create("clientCheck") {
    // Boots the client on a private X display, applies every client mixin and quits again.
    client()
    jvmArguments.add("-Dcarpet.mixinAudit=true")
    generateRunConfig.set(false)
}

dependencies {
    // Per-version dependency versions live in versions/<minecraft>/gradle.properties
    add("minecraft", "com.mojang:minecraft:$mcVersion")

    if (!unobfuscated) add("mappings", loom.officialMojangMappings())

    // The Fabric artifacts are remapped mods on obfuscated versions.
    val fabricLoader = "net.fabricmc:fabric-loader:${property("loader_version")}"
    val fabricApi = "net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}"
    if (unobfuscated) {
        implementation(fabricLoader)
        implementation(fabricApi)
    } else {
        add("modImplementation", fabricLoader)
        add("modImplementation", fabricApi)
    }

    // Jakarta annotations (replacement for javax.annotation removed from Java)
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")
    // 1.21.11 code still uses the javax annotations from before they left the JDK
    if (!unobfuscated) compileOnly("com.google.code.findbugs:jsr305:3.0.2")

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
    val log = dir.resolve("logs/latest.log")
    timeout = Duration.ofMinutes(10)
    doFirst {
        dir.resolve("world").deleteRecursively()
        report.delete()
        log.delete()
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
        // A shutdown that throws loses whatever the server still had to write, even when every
        // scenario passed, so it is a failure of the run.
        if (log.isFile) {
            val shutdownFailures = log.readLines().filter { it.contains("Exception stopping the server") }
            if (shutdownFailures.isNotEmpty()) {
                throw GradleException(
                    "The server threw while stopping in $log:\n" + shutdownFailures.joinToString("\n")
                )
            }
        }
    }
}

// runClientCheck boots the dev client far enough to load every class a client mixin targets, which
// is where a mixin that no longer matches its target shows up, and then quits. The client needs a
// display, so a private Xvfb with Mesa's software GLX is started for it and killed afterwards.
val xvfbProcesses = mutableMapOf<String, Process>()
val stopXvfb = tasks.register("stopXvfb") {
    group = "verification"
    description = "Stops the Xvfb runClientCheck started, if it is still running."
    doLast {
        xvfbProcesses.values.forEach {
            // SIGTERM, so Xvfb removes its socket instead of leaving a stale one behind.
            it.destroy()
            it.waitFor(5, TimeUnit.SECONDS)
            it.destroyForcibly()
        }
        xvfbProcesses.clear()
    }
}
tasks.named<JavaExec>("runClientCheck") {
    group = "verification"
    description = "Applies every client mixin on a dev client and fails on any that does not match."
    val dir = rootProject.file("run/$mcVersion/clientCheck")
    val log = dir.resolve("logs/latest.log")
    val xvfbLog = dir.resolve("xvfb.log")
    // The marker carpet.client.ClientMixinAudit logs once the audit got through every mixin.
    val done = "client mixin audit finished"
    timeout = Duration.ofMinutes(10)
    outputs.upToDateWhen { false }
    doFirst {
        log.delete()
        dir.mkdirs()
        xvfbLog.writeText("")
        // Other people run displays on this machine, so take the first one Xvfb can open rather than
        // a fixed number. A stale socket from an earlier killed run is fine: Xvfb takes it over.
        val socket = File("/tmp/.X11-unix")
        // Xvfb picks up the host's GLX vendor by default and dies in InitExtensions; Mesa's is needed.
        fun startXvfb(display: String): Process = ProcessBuilder("Xvfb", display, "-screen", "0", "1280x720x24")
            .redirectOutput(xvfbLog)
            .redirectErrorStream(true)
            .apply {
                environment()["LIBGL_ALWAYS_SOFTWARE"] = "1"
                environment()["__GLX_VENDOR_LIBRARY_NAME"] = "mesa"
                environment()["__EGL_VENDOR_LIBRARY_FILENAMES"] = "/usr/share/glvnd/egl_vendor.d/50_mesa.json"
            }
            .start()
        // Each version gets its own first choice of display so the three checks can run at once.
        val first = 90 + stonecutter.versions.indexOfFirst { it.version == mcVersion } * 2
        var display: String? = null
        for (number in first until first + 10) {
            val candidate = ":$number"
            val xvfb = startXvfb(candidate)
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (xvfb.isAlive && !File(socket, "X$number").exists() && System.nanoTime() < deadline) {
                Thread.sleep(100)
            }
            if (xvfb.isAlive && File(socket, "X$number").exists()) {
                display = candidate
                xvfbProcesses[candidate] = xvfb
                break
            }
            xvfb.destroyForcibly()
        }
        if (display == null) throw GradleException("No X display between :$first and :${first + 9} would start Xvfb:\n${xvfbLog.readText()}")
        logger.lifecycle("client check display $display")
        environment("DISPLAY", display)
        environment("LIBGL_ALWAYS_SOFTWARE", "1")
        environment("__GLX_VENDOR_LIBRARY_NAME", "mesa")
        environment("__EGL_VENDOR_LIBRARY_FILENAMES", "/usr/share/glvnd/egl_vendor.d/50_mesa.json")
        // 26.3's window comes from SDL, which prefers a Wayland session if there is one.
        environment.remove("WAYLAND_DISPLAY")
        environment("XDG_SESSION_TYPE", "x11")
        environment("SDL_VIDEODRIVER", "x11")
    }
    doLast {
        val text = if (log.isFile) log.readText() else ""
        val failures = text.lines().filter {
            it.contains("MixinApplyError") || it.contains("InvalidInjectionException") ||
                it.contains("Critical injection failure")
        }
        if (failures.isNotEmpty()) {
            throw GradleException("Client mixins failed to apply:\n${failures.joinToString("\n")}")
        }
        if (!text.contains(done)) {
            throw GradleException("The client never reported \"$done\"; see $log")
        }
    }
    // A finalizer, not part of doLast, so a failed client does not leave a display running.
    finalizedBy(stopXvfb)
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
    if (!unobfuscated) {
        // The copy in src/main/resources is written for the official namespace, so overwrite it
        // with the one from versions/<minecraft> that matches these mappings.
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(accessWidener)
    }

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
    options.release = if (unobfuscated) 25 else 21
    // javac stops at 100 errors by default, which hides most of a version port's work list.
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "2000"))
}

java {
    withSourcesJar()

    sourceCompatibility = if (unobfuscated) JavaVersion.VERSION_25 else JavaVersion.VERSION_21
    targetCompatibility = if (unobfuscated) JavaVersion.VERSION_25 else JavaVersion.VERSION_21
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
