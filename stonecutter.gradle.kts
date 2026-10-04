plugins {
    id("dev.kikugie.stonecutter")
    // 26.1+ ships unobfuscated, so it needs the no-remap Loom; older obfuscated
    // versions need the remapping one. build.gradle.kts applies whichever fits.
    id("net.fabricmc.fabric-loom") version "1.18.2" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.18.2" apply false
}

stonecutter active "26.3"