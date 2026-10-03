# The self-test

## What it is

The self-test boots a real server on a flat world and drives fake players through scripted
scenarios from the server's tick loop. It checks the things a compiler cannot: that a fake player
spawns where you asked, that it walks there, that a kit goes on, that a blocked sword hit takes less
damage, that a bot program both acts and does not lock up the tick loop.

It is code, not a script: `carpet.pvp.selftest.SelfTest`, driven from `carpet.CarpetServer.tick`.
Nothing in it is specific to one mod loader, and it uses only Minecraft and JDK types.

There is also a smaller set of plain JUnit tests (`./gradlew test`) for pure logic — combat maths,
the pathfinder, the action schema, kit parsing. Those run in `build` without a server. This page is
about the server one.

## Running it

Every version, every scenario:

```
./gradlew build runSelfTest
```

Chosen scenarios, one version:

```
./gradlew :26.3:runSelfTest -PselfTest=spawn,nav_goto
```

`all` (the default) means every scenario:

```
./gradlew :26.3:runSelfTest -PselfTest=all
```

`runSelfTest` is a `JavaExec` that boots the server with:

| Setting | Value | Why |
|---|---|---|
| `-Dcarpet.selftest=<names>` | `-PselfTest`, or `all` | which scenarios to run |
| `-Dcarpet.mixinAudit=true` | always | apply every mixin at boot so a stale one fails immediately |
| `server-port` | `0` | the OS picks a free port, so parallel runs and other dev servers cannot collide |
| `online-mode` | `false` | fake players resolve without asking Mojang |
| `level-type` | `minecraft:flat` | the scenarios assume flat ground at Y = -60 |
| `generate-structures` | `false` | faster world start |
| `difficulty` | `peaceful` | no mobs wandering in |
| `pause-when-empty-seconds` | `0` | the world never pauses |

Before each run the task deletes the world directory and any previous report, then writes `eula.txt`.
The run directory is `run/selftest-<minecraft version>/`.

The task also fails if the report is missing when the process ends, because "a server that dies
before the runner finishes may still exit with 0".

## Reports

`run/selftest-<version>/selftest-report.json`:

```json
{
  "minecraft": "26.3",
  "passed": true,
  "scenarios": [
    {
      "name": "spawn",
      "passed": true,
      "ticks": 3,
      "detail": "SelfA0 is 0.05 blocks from where it was spawned"
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `minecraft` | `server.getServerVersion()` |
| `passed` | true only when the list is non-empty and every entry passed |
| `scenarios[].name` | the scenario name as requested |
| `scenarios[].passed` | whether the check succeeded |
| `scenarios[].ticks` | game ticks the scenario took, including the setup |
| `scenarios[].detail` | the measured values, on success and on failure alike |

The same lines go to the server log with a `[selftest]` prefix, so a failure in CI is readable
without opening the file:

```
[selftest] PASS spawn after 3 ticks: SelfA0 is 0.05 blocks from where it was spawned
[selftest] FAILED, 18 of 19 scenarios passed
```

An unknown scenario name is kept rather than skipped, so a typo shows up as a failure:

```
[selftest] FAIL unknown scenario after 0 ticks: unknown scenario
```

## The scenarios

Thirty-five scenarios, run in this order. Every one spawns its bots 256 blocks further along X than the
last, so a bot left over from an earlier scenario cannot disturb a later one.

| Scenario | Ticks allowed | What it proves |
|---|---|---|
| `spawn` | 200 | `/player <name> spawn at <x> <y> <z>` puts the fake player within half a block of the position asked for. |
| `nav_goto` | 600 | `nav goto` walks to a goal 12 blocks away and finishes inside its default 1-block arrival radius. |
| `nav_come` | 600 | `nav come` navigates to the position of whoever ran the command, not the bot's spawn point. |
| `nav_patrol` | 600 | `nav patrol` with two waypoints visits both of them. |
| `nav_stop` | 600 | `nav stop` clears the navigation state — see the note below; it currently asserts the bot keeps coasting, not that it halts. |
| `nav_follow` | 600 | A following bot keeps within 4 blocks of a leader that walked more than 20 blocks. |
| `chase_attack` | 600 | `nav chase attack 2.5 0 <target>` closes and damages the target. |
| `chase_crit` | 600 | `nav chase crit 2.5 0 <target>` closes and damages the target. |
| `script_run` | 100 | `/script run 1+1` runs through the same command source and returns a positive result, so the mod's server-side setup is intact. |
| `fill_updates` | 100 | A redstone lamp lights when a redstone block is placed next to it with `fillUpdates` on, and stays dark with the rule off. |
| `logic_program` | 600 | A CarpetLogic program built through the Java API walks the bot forward and reaches `COMPLETED`. |
| `logic_forever_budget` | 200 | A `FOREVER` loop with nothing to wait for does not stop the server ticking, and is still `RUNNING` after 40 ticks. |
| `spawn_exact_name` | 200 | A mixed-case name (`sElF0a`) survives the spawn untouched — the player list matches names ignoring case, so only the profile tells the two apart. |
| `spawn_gamemode` | 200 | `spawn ... in creative` really puts the fake player in creative mode. |
| `shield_disable` | 600 | A shield raised with `use continuous` is broken by an axe and goes on cooldown. |
| `kit_give` | 200 | Every built-in kit loads, every entry builds, and each kit gives its bot the expected weapon, chestplate, enchantment and stack. |
| `kit_roundtrip` | 200 | Saving a player's inventory, giving a kit over it and restoring puts every slot back, including the selected hotbar slot — through memory and through the kit file. |
| `sword_block` | 300 | With `swordBlockHitting` on a sword-blocking player loses `swordBlockDamageMultiplier` of a fixed 4-health hit; with the rule off they lose all of it. An idle player loses 4 either way. |
| `explosion_rules` | 600 | With `optimizedTNT` on, a primed tnt leaves the stone next to it standing while `explosionNoBlockDamage` is on and blows it away while it is off. |
| `xp_explosions` | 600 | An ore blown up drops experience with `xpFromExplosions` on and none with it off. |
| `scarpet_events` | 400 | `__on_player_takes_damage` reaches a script only once an app with a handler is loaded, and `damageTickOther` swallows the hits inside its window. |
| `scarpet_explosion` | 600 | `__on_explosion_outcome` fires for a plain tnt explosion. |
| `update_suppression_block` | 100 | A barrier over an unpowered activator rail schedules a tick with `updateSuppressionBlock 0` and does not with the rule at -1. |
| `stackable_shulker_boxes` | 100 | An empty shulker box stacks `stackableShulkerBoxes` times, and any other item is left alone. |
| `structure_block_ignored` | 100 | `structureBlockIgnored` drops the named block from the palette of a saved structure. |
| `persistent_parrots` | 200 | A parrot on the shoulder survives damage with `persistentParrots` on and is dropped with it off. |
| `lag_free_spawning` | 400 | The natural spawner keeps running while `lagFreeSpawning` is on, which needs `carpet.fakes.LevelInterface` to have an implementation. |
| `interaction_updates` | 200 | A redstone block placed by a real use-item-on packet lights the lamp next to it with `interactionUpdates` on and leaves it dark with the rule off. |
| `punish_wrong_tool_hits` | 300 | Hitting a block that needs a tool with bare hands costs a heart with `punishWrongToolHits` on and nothing with it off. |
| `scarpet_item_use_events` | 300 | `__on_player_uses_item` is called for a real use-item packet with `scarpetItemUseEvents` on and never with it off. |
| `sculk_sensor_range` | 300 | A step 12 blocks from a sculk sensor is out of reach at the default range of 8 and inside the 16 `sculkSensorRange` sets, while one 24 blocks further stays out of reach either way. |
| `summon_natural_lightning` | 400 | `/summon lightning` rolls for the skeleton horse trap only with `summonNaturalLightning` on; see the note below for the numbers. |
| `explosion_state_leak` | 600 | An explosion that computes no block positions leaves nothing queued for the next block-damaging one — see the note below. |
| `scarpet_world_data` | 100 | A Scarpet app saves the world data with `save()` and reads the result back. |
| `tick_synced_world_borders` | 900 | A five second border lerp is finished after 160 game ticks at 40 ticks a second with `tickSyncedWorldBorders` on and has barely started with it off. |

`kit_give` checks these values, one per built-in kit:

| Kit | Main hand | Chestplate | Enchantment | Stack |
|---|---|---|---|---|
| `sword` | `diamond_sword` | `diamond_chestplate` | Protection 4 | 4 `golden_apple` |
| `axe` | `diamond_sword` | `diamond_chestplate` | Protection 4 | 4 `golden_apple` |
| `smp` | `netherite_sword` | `netherite_chestplate` | Protection 4 | 16 `experience_bottle` |
| `mace` | `mace` | `netherite_chestplate` | Protection 4 | 16 `wind_charge` |
| `crystal` | `netherite_sword` | `netherite_chestplate` | Blast Protection 4 | 8 `end_crystal` |

`summon_natural_lightning` is the one scenario that leans on a random number. The skeleton horse roll is one
chance in fifty to twenty on a fresh world on hard, so the scenario sums up 600 bolts at one spot and counts
the horses there, then turns the rule off and sums up 20 at a spot 32 blocks away and counts those. The chance
of missing every one of 600 rolls is below one in a million; the count of zero with the rule off is not random
at all, because vanilla only ever rolls that dice in `ServerLevel.tickThunder`, for a storm.

`explosion_state_leak` reaches into `carpet.helpers.OptimizedExplosion` by reflection to leave a block in its
static position set, which is what an explosion that skips the walk would do. Nothing in the game can do it
today, because the branch that fills the set also empties it — see the note in the report.

The `nav_stop` scenario is worth reading. It pins current behaviour, not the behaviour most people
expect: `stopNavigation()` clears the navigation state but leaves the movement inputs alone, so a
bot that was already walking keeps coasting at its last speed. The scenario's own comment says it
should be tightened to "the bot stops moving" once `stopNavigation()` also stops movement. It is
listed here as it stands.

## How a scenario runs

Each scenario is a record of a timeout, the bots to spawn, the commands to issue, an optional
`start` hook, and a `check` function polled every tick:

```java
private record Scenario(int timeout, List<Bot> bots, List<String> commands,
                        Consumer<MinecraftServer> start,
                        Function<MinecraftServer, Probe> check) {}
```

The flow per scenario:

1. The bots are spawned with `player <name> spawn at <x> <y> <z> facing 0 0 in minecraft:overworld in <gamemode>`.
2. Every tick, the check first confirms each bot is in the player list.
3. The first time all bots are present, `start` runs and the command list is issued.
4. `check` runs every tick. The scenario ends when it returns `ok`, or when the timeout runs out.
5. Every bot is then disconnected with `player <name> disconnect` and the result is logged.

Two details worth knowing:

- Commands run through `performPrefixedCommand` on the console's command source, so they behave
  exactly as if you had typed them.
- `tick sprint 1d` is set once at the start of the run. Sprinting only removes the wait between
  ticks; the scenarios take the same number of game ticks either way.

`nav_come` needs a command source at a position, so it uses the overload that runs a command as
though it came from a given `Vec3`.

## Adding a scenario

1. Add the name to `SCENARIOS` in `SelfTest.java`. The order of that list is the order scenarios
   run, and it decides each scenario's offset along X (`SPACING * (index + 1)`).

2. Add a `case` to `scenario(...)`. Use `a` for the bot under test and `b` for the second player it
   follows or fights; both are named after the scenario's index so two scenarios never collide.
   Everyone spawns looking along +z, so a bot 2 blocks in front of another is already facing it.

   ```java
   case "my_check":
       Vec3 goal = origin.add(12.0D, 0.0D, 0.0D);
       return new Scenario(600, List.of(new Bot(a, origin)),
               List.of("player " + a + " nav goto " + coords(goal)), server ->
       {
           double left = player(server, a).position().distanceTo(goal);
           return new Probe(left <= 1.0D, fmt("%s is %.2f blocks from the goal", a, left));
       });
   ```

3. Conventions:

   - The timeout is in game ticks; 600 covers a navigation run, 100–300 is enough for anything
     that does not walk.
   - Return `new Probe(condition, message)`. The message goes in the report whether the check
     passed or failed, so put the measured numbers in it.
   - Use `pending(detail)` to say "not yet, keep polling" — a `Probe` that is not ok simply means
     "try again", so the message becomes the explanation for a timeout.
   - Anything with state that has to survive ticks (a flag, a counter) goes in a one-element array
     captured by the lambda, as `nav_patrol`'s `visited[]` and `nav_stop`'s `stopping[]` do.
   - Commands are issued once all bots have joined, so a scenario does not need to wait for the
     profile to resolve itself.
   - State the scenario needs before the first command (a carpet rule, a forceload, `give`) belongs
     in the command list or the `start` hook.
   - A fake player cannot use anything until the 60-tick client-load timer of its connection runs
     down, and its listener drops block use packets until something answers the spawn teleport, so a
     scenario that drives a packet path waits for `hasClientLoaded()` and calls `confirmTeleport`.
   - Entities in a forceloaded chunk only become countable once the chunk map has picked the ticket
     up, which takes some ticks. A scenario that counts entities in a chunk nobody is in waits for
     one to be countable first, as `summon_natural_lightning` does.

4. Run it on its own while you work:

   ```
   ./gradlew :26.3:runSelfTest -PselfTest=my_check
   ```

5. Add a unit test if the scenario depends on a piece of pure logic — scenario selection and the
   report format are already covered in `SelfTestReportTest`.

## The clean-exit check

A self-test that hangs is worse than one that fails, so the run has to prove the server can end.

`finish()` runs after the last scenario:

1. It writes `selftest-report.json`.
2. It spawns one more fake player (`SelfStop`) and logs how many are online, so the run always ends
   the way an operator would: with bots still in the player list.
3. It calls `server.halt(false)` — the same stop an operator's `stop` command triggers.
4. It starts a **non-daemon** thread named `selftest-watchdog`. It has to be non-daemon: the JVM
   ends the moment the server thread stops, taking the result and the thread check with it.

The watchdog holds the JVM open, waits up to 120 seconds for the server thread to join, then gives
the server's own worker threads up to another 120 seconds to finish. Then it reports:

- every non-daemon thread still alive, with its stack trace,
- when the server thread itself did not stop: the players still listed, and per dimension the
  player count, whether the chunk map still has work, how many chunks are loaded, and which players
  the distance manager still holds (read by reflection, as a diagnostic only).

`main` and `DestroyJavaVM` are ignored — those are the JVM's own wait for the last non-daemon
thread. Daemon threads are ignored too, so the game's I/O workers still saving chunks do not count
as a leak.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Every requested scenario passed, and no thread was left behind. |
| 1 | At least one scenario failed, or the report could not be written. |
| 3 | A thread was still running when the watchdog gave up — the server did not shut down cleanly. |
| 3 | The watchdog itself was interrupted while waiting. |

`Runtime.halt()` is used rather than a normal exit so the result does not depend on the JVM's own
shutdown sequence.

A run that executes nothing is not a pass: `allPassed` returns false for an empty result list, so a
typo in `-PselfTest=` cannot look green.