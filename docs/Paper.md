# The Paper plugin

Carpet PvP builds twice from one source tree. The Fabric mod is the whole thing: Carpet's rules,
Scarpet, CarpetLogic and the mixins behind them. The Paper plugin is the bot half of it — fake
players, navigation, kits, the combat brain, the menus, the drills and the matches — compiled a
second time against a Paper dev bundle and shipped as a plugin. It has no rules, no scripting and no
mixins; everything it needs comes from `config.yml` and from the commands the plugin registers.

## What is in the plugin

| Area | What the plugin has |
|---|---|
| Fake players | Everything `carpet.pvp` needs: spawn, join, respawn, disconnect, knockback, fall distance, own action pack |
| Navigation | Every option and mode of the action pack's navigation, the same pathfinder and the same search budget |
| Kits | The built-in kits and the world's kit folder: `list`, `reload`, `give`, `save`, `delete`, `restore` |
| Combat brain | Sword, crystal, anchor, ranged, mace and smp styles, the difficulties, factions, per-bot options and stats |
| Practices | Drills, matches between bots, spectating through a bot's eyes, another account's skin on a bot, fight traces |
| Menus | `/bot gui`: the bots of the server and the pages of their settings, kits and spawn options; the kit editor |
| Set-up | `/auto-setup`: a mode and a difficulty, and the fight is set up, restarted and given back |
| Rules | None. The plugin's `config.yml` holds the values Carpet's rules hold on Fabric |

## What is left out, and why

Everything that is Carpet rather than bots, because it is a mod loader feature:

- `/carpet` and every rule: there is no rule system on a Paper server, so the plugin's `config.yml`
  carries the same values. `navigation.enabled` is the one a session turns on and puts back.
- `/script` and Scarpet, `/carpetlogic` and the CarpetLogic combat nodes, and the web editor.
- The rules' own behaviour: `swordBlockHitting`, `fillUpdates`, `optimizedTNT`, `explosionNoBlockDamage`,
  `xpFromExplosions`, `updateSuppressionBlock`, `stackableShulkerBoxes`, `structureBlockIgnored`,
  `persistentParrots`, `lagFreeSpawning`, `interactionUpdates`, `punishWrongToolHits`,
  `sculkSensorRange`, `summonNaturalLightning` and `tickSyncedWorldBorders`. Each of them has a
  mixin behind it on Fabric.
- The mixins themselves. Two of them are felt while fighting: a bot does not take a shield stun from
  a real player (the other half of that is in the fake player and does ship), and a bot's critical
  hit is vanilla's own rather than "any swing made while falling". The mace style plans around that
  critical hit, so the two mace damage scenarios are reported unsupported by the self-test.
- `/spawn`, `/spawnplayer` and the rest of Carpet's own command set.

## Installing

1. Take the plugin jar out of `versions/<minecraft>-paper/build/libs/`, named
   `carpet-pvp-<minecraft>-paper-<mod_version>.jar`.
2. Drop it into the server's `plugins/` folder.
3. Start the server. It writes `plugins/CarpetPvp/config.yml` on the first run; edit it and restart,
   or leave it as it is.

It needs Java 25 on Minecraft 26.x and Java 21 on 1.21.11, the same as the server it runs on.

## Commands

The plugin registers `/bot` and `/auto-setup`. Paper's Brigadier has its own source stack, so the
tree is built over it and unwrapped to the shared bodies every platform runs; the commands below are
the same ones, with the same results, as [Commands.md](Commands.md).

| Command | What it does |
|---|---|
| `/bot spawn <name> <style> [difficulty] [at <pos> facing <yaw> <pitch> in <dimension> in <gamemode>]` | Spawns a fighting bot in one of the combat styles |
| `/bot option <name> [key [value]]` | Reads and writes the per-bot combat settings |
| `/bot duel <a> <b>` | Makes two bots target each other |
| `/bot stop <name>` | Stops a bot fighting |
| `/bot stats <name>` | What a bot's body did, and its last fights |
| `/bot kit list \| reload \| give <players> <kit> \| save <name> \| delete <name> \| restore [players]` | The kits |
| `/bot drill list \| stop \| <name>` | The drills: aim, pearl catch, re-totem, stun slam, crystal timing |
| `/bot match ffa <count> <style> [difficulty]`, `/bot match teams <size> <style> [difficulty]`, `/bot match join <team>`, `/bot match stop` | A match between bots |
| `/bot spectate <name> \| stop` | Looking through a bot's eyes |
| `/bot skin <bot> <player>` | Wears another account's skin on a bot |
| `/bot trace <name>` | The trace of what a bot's last fight was made of |
| `/bot gui [saveas <name> \| discard]` | Opens the menus |
| `/bot <name> ...` | The fake-player controls of Fabric's `/player`: `spawn`, `kill`, `disconnect`, `look`, `turn`, `move`, `hotbar`, `equip`, `unequip`, `equipment`, `itemCd`, `sneak`, `sprint`, `jump`, `swim`, `attack`, `use`, `drop`, `animate`, `mount`, `glide`, `nav ...`, `ai ...`, `faction ...` |
| `/auto-setup [<mode> [difficulty]]`, `/auto-setup stop` | Sets a whole fight up for a player and hands everything back |

Two spellings differ from Fabric's `/player`, because Brigadier on this Paper build will not parse
the other shape: `nav patrol` takes two waypoints rather than three, and `turn` takes
`left`, `right`, `back` or `around <degrees>` rather than a bare angle.

## Permissions

| Permission | Default | What it allows |
|---|---|---|
| `carpetpvp.bot` | op | Everything under `/bot` |
| `carpetpvp.autosetup` | op | `/auto-setup` |

They are declared in `paper-plugin.yml`, so `permissions` in the plugin's jar can set them per group
the way any other plugin's are.

## config.yml

The file is the plugin's copy of Carpet's rules, under three headings. `fakePlayer` is the fake
player rules, `navigation` the `fakePlayerNav*` ones and `combat` the `bot*` ones; the comments in the
file give each one's meaning. It is read once at start-up. `/bot option` reads and writes the combat
settings of one bot at a time and does not touch the file.

Two values are worth knowing about:

- `navigation.enabled` is off in the file as it ships, so `/bot nav ...` says so until it is on. A
  session of `/auto-setup` turns it on while it runs and puts it back when it ends, as it does with
  Carpet's rule.
- `combat.style` and `combat.difficulty` are what a `/bot spawn` leaves out takes.

## Building it

The Paper nodes are Gradle subprojects like the Fabric ones, one for each of the three Minecraft
versions: `:26.3-paper`, `:26.2-paper` and `:1.21.11-paper`.

```
./gradlew build                 # every version, Fabric and Paper
./gradlew :26.3-paper:build     # one Paper node
./gradlew :26.3-paper:runSelfTest
```

`settings.gradle.kts` declares them next to the Fabric versions; `versions/<minecraft>-paper/gradle.properties`
pins the dev bundle and the server build the node uses, and `paper.gradle.kts` compiles the shared
packages plus `carpet/paper/**` against the dev bundle. Paper names its dev bundles after the release
up to 26.1 and after the build from 26.2 on, which is why the whole coordinate rather than a build
number is in that file.

A Paper self-test takes about twenty minutes per node against three minutes for a Fabric one: the
Fabric runs `tick sprint 1d`, and a sprinting Paper server gets through a scenario's nine hundred
ticks before its chunk system has handed out the ground a few chunks away. The Fabric build excludes `carpet/paper/**`
and the Paper build excludes everything that needs the mod loader, so the two cannot both compile
the same file: a change that reaches `carpet.CarpetSettings`, `carpet.fakes`, `carpet.mixins`,
`carpet.utils` or the scripting packages out of the shared code breaks the Paper node's compile,
which is what keeps that code honest.

The report of what the self-test does on a Paper node, including the scenarios it reports unsupported
and why, is in [SelfTest.md](SelfTest.md).

## See also

- [Commands.md](Commands.md) — the same commands on Fabric
- [Kits.md](Kits.md) — the kits
- [FakePlayers.md](FakePlayers.md) — the fake player rules
- [Building.md](Building.md) — how the multi-version build works