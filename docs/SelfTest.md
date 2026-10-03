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

Twenty-two scenarios, run in this order. Every one spawns its bots 256 blocks further along X than the
last, so a bot left over from an earlier scenario cannot disturb a later one.

| Scenario | Ticks allowed | What it proves |
|---|---|---|
| `spawn` | 200 | `/player <name> spawn at <x> <y> <z>` puts the fake player within half a block of the position asked for. |
| `nav_goto` | 600 | `nav goto` walks to a goal 12 blocks away and finishes inside its default 1-block arrival radius. |
| `nav_come` | 600 | `nav come` navigates to the position of whoever ran the command, not the bot's spawn point. |
| `nav_patrol` | 600 | `nav patrol` with two waypoints visits both of them. |
| `nav_stop` | 600 | `nav stop` stops the bot: four ticks after the command it has not moved a hundredth of a block. |
| `nav_follow` | 600 | A following bot keeps within 4 blocks of a leader that walked more than 20 blocks. |
| `chase_attack` | 600 | `nav chase attack 2.5 0 <target>` closes and damages the target. |
| `chase_crit` | 600 | `nav chase crit 2.5 0 <target>` closes and damages the target. |
| `script_run` | 100 | `/script run 1+1` runs through the same command source and returns a positive result, so the mod's server-side setup is intact. |
| `fill_updates` | 100 | A redstone lamp lights when a redstone block is placed next to it with `fillUpdates` on, and stays dark with the rule off. |
| `logic_program` | 600 | A CarpetLogic program built through the Java API walks the bot forward and reaches `COMPLETED`. |
| `logic_forever_budget` | 200 | A `FOREVER` loop with nothing to wait for does not stop the server ticking, and is still `RUNNING` after 40 ticks. |
| `spawn_exact_name` | 200 | A mixed-case name (`sElF0a`) survives the spawn untouched — the player list matches names ignoring case, so only the profile tells the two apart. |
| `spawn_gamemode` | 200 | `spawn ... in creative` really puts the fake player in creative mode. |
| `animate_use` | 300 | `animate use` swings the off hand and `animate attack` the main hand, read off the swing each one leaves behind. |
| `item_cd` | 300 | An ender pearl throw puts the bot on a 20-tick cooldown; the bare `itemCd` reports one cleared and the cooldown is gone on the same tick. |
| `shield_disable` | 600 | A shield raised with `use continuous` is broken by an axe and goes on cooldown. |
| `kit_give` | 200 | Every built-in kit loads, every entry builds, and each kit gives its bot the expected weapon, chestplate, enchantment and stack. |
| `kit_roundtrip` | 200 | Saving a player's inventory, giving a kit over it and restoring puts every slot back, including the selected hotbar slot — through memory and through the kit file. |
| `kit_folder` | 300 | A hand-written kit file and a saved one, dropped into `<world>/carpet-kits/`, both load after `bot kit reload` and hand out what they say. |
| `sword_block` | 600 | With `swordBlockHitting` on a sword-blocking player loses `swordBlockDamageMultiplier` of a fixed 4-health hit and is pushed by about half of what an idle player is pushed by. An idle player loses 4 either way. |
| `kill` | 300 | `/player <name> kill` takes the bot off the server: it is gone from the player list. |

`kit_give` checks these values, one per built-in kit:

| Kit | Main hand | Chestplate | Enchantment | Stack |
|---|---|---|---|---|
| `sword` | `diamond_sword` | `diamond_chestplate` | Protection 4 | 4 `golden_apple` |
| `axe` | `diamond_sword` | `diamond_chestplate` | Protection 4 | 4 `golden_apple` |
| `smp` | `netherite_sword` | `netherite_chestplate` | Protection 4 | 16 `experience_bottle` |
| `mace` | `mace` | `netherite_chestplate` | Protection 4 | 16 `wind_charge` |
| `crystal` | `netherite_sword` | `netherite_chestplate` | Blast Protection 4 | 8 `end_crystal` |

`sword_block` uses four bots. `SelfA` holds its sword up and `SelfB` stands idle beside it, each with
its own attacker (`SelfC` and `SelfD`) one hit away, so that the knockback of a melee hit can be
compared between a blocking and an idle player without either of them being pushed out of reach.

`kill` spawns its victim from the command list rather than through the scenario's bot list, because
the check has to see the player gone from there.

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