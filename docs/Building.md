# Building Carpet PvP

## Supported Minecraft versions

One source tree builds three Minecraft versions:

| Minecraft | Node | Fabric API | Notes |
|---|---|---|---|
| 26.3 | `:26.3` | `0.161.0+26.3` | the active development version |
| 26.2 | `:26.2` | `0.161.0+26.2` | still supported |
| 1.21.11 | `:1.21.11` | `0.141.6+1.21.11` | the last obfuscated release |

All three are listed in `settings.gradle.kts` and all three are Gradle subprojects, so a plain
`./gradlew build` compiles and tests all of them. `stonecutter.gradle.kts` marks `26.3` as the
active node, which is what an IDE opens when you import the project.

### What differs between 26.x and 1.21.11

| | 26.2, 26.3 | 1.21.11 |
|---|---|---|
| obfuscation | ships unobfuscated | last obfuscated version |
| Loom plugin | `net.fabricmc.fabric-loom` | `net.fabricmc.fabric-loom-remap` |
| mappings | none needed | `loom.officialMojangMappings()` |
| bytecode | Java 25 (`options.release = 25`) | Java 21 (`options.release = 21`) |
| Fabric dependencies | `implementation` | `modImplementation` (they are remapped mods) |
| access widener | `src/main/resources/carpet.accesswidener`, `accessWidener v2 official` | `versions/1.21.11/carpet.accesswidener`, `accessWidener v2 named`, swapped in by `processResources` |
| `javax.annotation` | gone from the JDK | still there, so `com.google.code.findbugs:jsr305` is a `compileOnly` dependency |

`build.gradle.kts` picks the plugin, the mappings and the bytecode level from
`stonecutter.semantics.eval(mcVersion, ">=26.1")`, so nothing about the two kinds of version is
repeated per node.

Everything else the build needs is shared:

- Java 25 to run Gradle (`java_version` in `gradle.properties`)
- Fabric Loom 1.18.2
- Fabric Loader 0.19.5
- Gson, which ships with Minecraft
- No other dependencies

## How the multi-version build works

### One tree, three nodes

`src/` holds the 26.3 code. The 26.2 and 1.21.11 builds get a generated copy of it under
`versions/<minecraft>/build/generated/stonecutter/main/java`, produced by Stonecutter's
`stonecutterPrepare` task. You never edit those directories; they are rebuilt from `src/`.

`versions/<minecraft>/gradle.properties` holds the things that differ per version:

```properties
fabric_version=0.161.0+26.2
minecraft_dependency=>=26.2 <26.3
```

`minecraft_dependency` is substituted into `fabric.mod.json` by `processResources`, so each jar
declares the Minecraft range it actually works on.

### Version conditions in comments

Where a Minecraft API differs, the 26.3 code stays active and the older code goes in a comment
block:

```java
//? if >=26.3 {
this.setInvulnerableTime(0);
//?} else {
/*this.invulnerableTime = 0;
*///?}
```

The conditions compare Minecraft versions, so `>=26.1` means 26.1 and up, `<26.1` means **1.21.11
only** and `<26.3` means 1.21.11 and 26.2. A whole file that only exists on 1.21.11 is written the
same way, with the `//? if <26.1 {` on the first line and the `*///?}` on the last:

```java
//? if <26.1 {
/*package carpet.mixins;
...
}
*///?}
```

**The 26.3 node compiles `src/main/java` directly**, so a file that is `//?` conditional has to be
valid Java as it stands: the active branch plain, the other branch inside `/* … */`. Stonecutter
strips the comment markers for the versions it generates and leaves the block alone for 26.3. A
branch written the other way round compiles for 1.21.11 and breaks 26.3.

A pure rename uses the line-above form, which applies to the line directly below it:

```java
//~ if >=26.3 '.getHand()' -> '.hand()'
```

A rename that has to cover several lines opens a scope, which `//~}` closes:

```java
//~ if >=26.1 'ContainerInput' -> 'ClickType' {
...
//~}
```

And an extra argument only needed on one version uses the inline form:

```java
bot.blockUsingItem(level, attacker, source, amount/*? if >=26.3 {*/, true/*?}*/);
```

Most code needs no conditions at all. Only write one when you have looked the class up in both
game jars and found a real difference:

```
javap -cp ~/.gradle/caches/fabric-loom/26.3/minecraft-merged.jar -p net.minecraft.world.entity.player.Player
javap -c -p -cp ~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar net.minecraft.world.entity.player.Player
```

1.21.11 is obfuscated, so it is read out of the Loom-produced named jar rather than
`~/.gradle/caches/fabric-loom/1.21.11/`:

```
javap -p -cp ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-*/minecraft-merged-*.jar net.minecraft.world.entity.player.Player
```

### `excluded_mixins`

`carpet.mixins.json` lists the union of every mixin the mod has, because JSON cannot carry a
version condition. A mixin that must not load on a version is listed in
`versions/<minecraft>/gradle.properties` and `processResources` removes it from the config for that
version only:

```properties
excluded_mixins=CoralFeature_renewableCoralMixin,PieceGeneratorSupplier_plopMixin
```

The three lists in `versions/*/gradle.properties` are long — 26.3 excludes twenty-three, 26.2
twenty-one, 1.21.11 one — because most of them are Scarpet and fake-player mixins that only exist for
1.21.11. Read the file rather than copying the example above.

A mixin belongs in that list only when it really cannot apply. Two cases need it:

- the file only exists for other versions, because it is a whole-file `//? if <26.1 {` block;
- the target class or method is gone or was renamed in a way a condition cannot express, as with
  `PieceGeneratorSupplier_plopMixin`, which `@Redirect`s inside a lambda of an interface. That one is
  excluded on both 26.3 and 1.21.11.

Everything else must work on every version, even the old ones: a rule that works on 26.3 and
existed on 1.21.11 has to work on 1.21.11 too.

## Gradle commands

Build all versions at once, so every core is used:

```
./gradlew build
```

`build` compiles every version, runs the JUnit tests (`tasks.test` runs one JVM per core) and runs
`webuiTest`, which executes the CarpetLogic web editor's own tests under `node` when `node` is on
the `PATH`. If `node` is missing the task logs that it was skipped and passes.

Build one version:

```
./gradlew :26.3:build
./gradlew :26.2:build
./gradlew :1.21.11:build
```

Run a dev server or client:

```
./gradlew :26.3:runServer
./gradlew :26.3:runClient
./gradlew :1.21.11:runServer
```

Each combination gets its own directory under `run/`, so they can all be up at once:

| Task | Directory |
|---|---|
| `:26.3:runServer` | `run/26.3/server` |
| `:26.3:runClient` | `run/26.3/client` |
| `:26.2:runServer` | `run/26.2/server` |
| `:1.21.11:runServer` | `run/1.21.11/server` |
| `:26.3:runSelfTest` | `run/selftest-26.3` |
| `:26.3:runClientCheck` | `run/26.3/clientCheck` |

The world is per Minecraft version because an older server cannot open a newer world.

### `-PmixinAudit`

```
./gradlew :26.3:runServer -PmixinAudit
```

Adds `-Dcarpet.mixinAudit=true`, which makes the server apply every configured mixin at boot and
log any that no longer matches its target. Normally a stale mixin only fails the first time
something calls the patched method; with the audit it fails at startup instead. `runSelfTest`
always turns it on.

The same check can be run without a server:

```
./gradlew :26.3:webuiTest      # editor graph compiler, needs node
```

### `runClientCheck`

```
./gradlew runClientCheck              # every version
./gradlew :1.21.11:runClientCheck     # one version
```

The audit above runs on the server, so it never touches the `client` list of
`carpet.mixins.json`: a client mixin whose target moved only fails the first time the game loads
that class, which for a rendering class can be minutes into a session or not at all.
`runClientCheck` is the same check on the client. It

- starts an `Xvfb` on the first display of `:90`..`:99` that is free (each version starts at its
  own, so the three run at once), with Mesa's software GLX because the machine has no GPU, and
  stops it when the run is over,
- runs the dev client with `-Dcarpet.mixinAudit=true` until `ClientLifecycleEvents.CLIENT_STARTED`,
- force-loads every class a client mixin targets, so each one is applied and checked,
- then quits.

It fails when the client reports a mixin failure, when it never reached the end of the audit, and
when the run times out. It is not part of `build`, because it needs `Xvfb` and a display; run it
before a release or after touching anything under the `client` list.

### Self-test

```
./gradlew build runSelfTest                        # every version, every scenario
./gradlew :26.3:runSelfTest -PselfTest=spawn,nav_goto   # chosen scenarios, one version
```

There are 116 scenarios, 52 built into `SelfTest.java` and 64 registered in `ScenarioIndex.java`.
See [SelfTest.md](SelfTest.md) for the table and for how to add one.

All three self-test servers start at once, so each one is given its own world directory, gets the
Minecraft server on an ephemeral port (`server-port=0`) and is told to let the CarpetLogic web
editor take an ephemeral port too (`-Dcarpet.logicPort=0`, which `carpet.logic.CarpetLogic` reads
instead of the `carpetLogicPort` rule). Without that the second server to start logs a port clash.

`runSelfTest` fails when the run wrote no report and also when the server threw on its way out:
`Exception stopping the server` anywhere in `run/selftest-<version>/logs/latest.log` fails the
task, because a shutdown that throws loses whatever still had to be written even when every
scenario passed.

## Checking the documentation

```
scripts/check-docs.py
```

Reads every page under `docs/` and the README and checks that each relative link and anchor
resolves, that every command word is a command the code registers, that every rule name is a field
in `CarpetSettings`, that every bot setting is in `BotPvpConfig.KEYS` or `StyleIndex.options()`, and
that every self-test scenario in `SelfTest.java` and `ScenarioIndex.java` has a row in
`docs/SelfTest.md` and the other way round. It exits 1 on a problem. Targets that another piece of
work still has to deliver — `docs/Paper.md` and the screenshots the README shows — are listed as
expected rather than as failures.

## Where the jars land

Each version builds into its own directory:

```
versions/26.3/build/libs/carpet-pvp-26.3-18.jar
versions/26.3/build/libs/carpet-pvp-26.3-18-sources.jar
versions/26.2/build/libs/carpet-pvp-26.2-18.jar
versions/1.21.11/build/libs/carpet-pvp-1.21.11-18.jar
```

The name comes from `archives_base_name` (`carpet-pvp`), the Minecraft version and `mod_version`
(`18` in `gradle.properties`). `tasks.jar` also drops the `LICENSE` in as
`LICENSE_carpet-pvp-26.3-18`.

`runSelfTest` writes its report to `run/selftest-<version>/selftest-report.json`.

The release workflow builds one version at a time and picks the same paths up:

```
./gradlew --no-daemon :26.3:build -x test
```

## Adding a new Minecraft version

Say `26.4`.

1. Add the version to `settings.gradle.kts`:

   ```kotlin
   stonecutter {
       create(rootProject) {
           versions("1.21.11", "26.2", "26.3", "26.4")
           vcsVersion = "26.4"
       }
   }
   ```

   `vcsVersion` is the node an IDE opens; set it to the new one while you port it.

2. Create `versions/26.4/gradle.properties`:

   ```properties
   fabric_version=<the Fabric API build for 26.4>
   minecraft_dependency=>=26.4 <26.5
   ```

   Add `excluded_mixins=A,B` listing every mixin whose target is gone in 26.4. Leave it out if
   none are.

3. A version at or above 26.1 needs nothing else: `build.gradle.kts` gives it the no-remap Loom
   plugin, no mappings, Java 25 bytecode and `src/main/resources/carpet.accesswidener`. A version
   below 26.1 is obfuscated and needs the entries of
   [What differs between 26.x and 1.21.11](#what-differs-between-26x-and-12111) instead, with the
   access widener copied into `versions/<version>/carpet.accesswidener` and its namespace changed
   to whatever that version's mappings use (`named` for Mojang mappings).

4. Run `./gradlew build`. Stonecutter generates the new node and `javac` lists everything that does
   not compile. `options.compilerArgs` adds `-Xmaxerrs 2000` so you get the whole list instead of
   the first 100.

5. Fix each error in `src/` with a version condition. `src/` keeps the newest form active:

   ```
   //? if >=26.4 {
   newCall();
   //?} else if >=26.3 {
   /*olderCall();
   *///?} else {
   /*oldestCall();
   *///?}
   ```

   Look names up in the game jar rather than guessing:

   ```
   unzip -l ~/.gradle/caches/fabric-loom/26.4/minecraft-merged.jar | grep <Name>
   javap -p -cp ~/.gradle/caches/fabric-loom/26.4/minecraft-merged.jar <class>
   ```

6. Run `./gradlew build runSelfTest`. The self-test covers the parts a compiler cannot: fake
   player spawning, navigation, kits, sword blocking and CarpetLogic.

7. Bump `mod_version` in `gradle.properties` when you release. The release workflow picks up every
   directory under `versions/` on its own.
## Related pages

- [README.md](../README.md) — what the mod is and where the docs are
- [Commands.md](Commands.md) — every command the mod registers
- [Rules.md](Rules.md) — the rules the code sets and their defaults
- [SelfTest.md](SelfTest.md) — the self-test scenarios, how to run them and how to add one
- [Bots.md](Bots.md) — the PvP bots
- [CarpetLogic.md](CarpetLogic.md) — the web editor `webuiTest` exercises
- [FakePlayers.md](FakePlayers.md) — fake players, navigation and gliding
- [Kits.md](Kits.md) — the kit files and the `assets/carpet/kits` resources
- [Practice.md](Practice.md) — drills, matches, spectating and traces
- [AutoSetup.md](AutoSetup.md) — `/auto-setup`
- [Menus.md](Menus.md) — `/bot gui`
- [SwordBlocking.md](SwordBlocking.md) — the mixins behind `swordBlockHitting`
- [Paper.md](Paper.md) — the Paper plugin build
