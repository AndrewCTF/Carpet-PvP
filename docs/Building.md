# Building Carpet PvP

## Supported Minecraft versions

One source tree builds two Minecraft versions:

| Minecraft | Node | Fabric API | Notes |
|---|---|---|---|
| 26.3 | `:26.3` | `0.161.0+26.3` | the active development version |
| 26.2 | `:26.2` | `0.161.0+26.2` | still supported |

Both are listed in `settings.gradle.kts` and both are Gradle subprojects, so a plain
`./gradlew build` compiles and tests both. `stonecutter.gradle.kts` marks `26.3` as the active
node, which is what an IDE opens when you import the project.

Everything else the build needs is shared:

- Java 25 (`java_version` in `gradle.properties`, `options.release = 25` for every compile task)
- Fabric Loom 1.18.2
- Fabric Loader 0.19.5
- Gson, which ships with Minecraft
- No other dependencies

## How the multi-version build works

### One tree, two nodes

`src/` holds the 26.3 code. The 26.2 build gets a generated copy of it under
`versions/26.2/remappedSrc` (and `versions/26.3/remappedSrc` for the active node), produced by
Stonecutter's `stonecutterGenerate` task. You never edit those directories; they are in
`.gitignore` and rebuilt from `src/`.

`versions/<minecraft>/gradle.properties` holds the things that differ per version:

```properties
fabric_version=0.161.0+26.2
minecraft_dependency=>=26.2 <26.3
```

`minecraft_dependency` is substituted into `fabric.mod.json` by `processResources`, so each jar
declares the Minecraft range it actually works on.

### Version conditions in comments

Where a Minecraft API differs between the two versions, the 26.3 call stays active and the 26.2
call goes in a comment block:

```java
//? if >=26.3 {
this.setInvulnerableTime(0);
//?} else {
/*this.invulnerableTime = 0;
*///?}
```

A pure rename uses the line-above form:

```java
//~ if >=26.3 '.getHand()' -> '.hand()'
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

### `excluded_mixins`

A mixin whose target does not exist in a Minecraft version has to leave that version's mixin
config. JSON cannot carry a version condition, so the drop is done at build time instead:
list the mixin in `versions/<minecraft>/gradle.properties` and `processResources` removes it from
`carpet.mixins.json` for that version only.

```properties
excluded_mixins=CoralFeature_renewableCoralMixin,PieceGeneratorSupplier_plopMixin
```

Currently set on 26.3 only. 26.2 keeps both.

## Gradle commands

Build both versions at once, so every core is used:

```
./gradlew build
```

`build` compiles both versions, runs the JUnit tests (`tasks.test` runs one JVM per core) and runs
`webuiTest`, which executes the CarpetLogic web editor's own tests under `node` when `node` is on
the `PATH`. If `node` is missing the task logs that it was skipped and passes.

Build one version:

```
./gradlew :26.3:build
./gradlew :26.2:build
```

Run a dev server or client:

```
./gradlew :26.3:runServer
./gradlew :26.3:runClient
./gradlew :26.2:runServer
```

Each combination gets its own directory under `run/`, so they can all be up at once:

| Task | Directory |
|---|---|
| `:26.3:runServer` | `run/26.3/server` |
| `:26.3:runClient` | `run/26.3/client` |
| `:26.2:runServer` | `run/26.2/server` |
| `:26.3:runSelfTest` | `run/selftest-26.3` |

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

### Self-test

```
./gradlew build runSelfTest                        # both versions, every scenario
./gradlew :26.3:runSelfTest -PselfTest=spawn,nav_goto   # chosen scenarios, one version
```

See [SelfTest.md](SelfTest.md).

## Where the jars land

Each version builds into its own directory:

```
versions/26.3/build/libs/carpet-pvp-26.3-18.jar
versions/26.3/build/libs/carpet-pvp-26.3-18-sources.jar
versions/26.2/build/libs/carpet-pvp-26.2-18.jar
versions/26.2/build/libs/carpet-pvp-26.2-18-sources.jar
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
           versions("26.2", "26.3", "26.4")
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

3. Run `./gradlew build`. Stonecutter generates the new node and `javac` lists everything that does
   not compile. `options.compilerArgs` adds `-Xmaxerrs 2000` so you get the whole list instead of
   the first 100.

4. Fix each error in `src/` with a version condition. `src/` keeps the newest form active:

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

5. Run `./gradlew build runSelfTest`. The self-test covers the parts a compiler cannot: fake
   player spawning, navigation, kits, sword blocking and CarpetLogic.

6. Bump `mod_version` in `gradle.properties` when you release. The release workflow picks up every
   directory under `versions/` on its own.