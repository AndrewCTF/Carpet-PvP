# The self-test

## What it is

The self-test boots a real server on a flat world and drives fake players through scripted
scenarios from the server's tick loop. It checks the things a compiler cannot: that a fake player
spawns where you asked, that it walks there, that a kit goes on, that a blocked sword hit takes less
damage, that a bot wins more duels than a weaker one, that a bot's crystal blast costs what the
combat model says it costs.

It is code, not a script: `carpet.pvp.selftest.SelfTest`, driven from `carpet.CarpetServer.tick`.
Nothing in it is specific to one mod loader, and it uses only Minecraft and JDK types.

There is also a smaller set of plain JUnit tests (`./gradlew test`) for pure logic — combat maths,
the pathfinder, the action schema, kit parsing. Those run in `build` without a server. This page is
about the server one.

The self-test also never looks at the client: `-Dcarpet.mixinAudit=true` applies the mixins in the
`mixins` list, not the `client` list of `carpet.mixins.json`. `./gradlew runClientCheck` is the
client-side counterpart — it boots a dev client on an `Xvfb`, applies every client mixin and quits.
See [Building.md](Building.md#runclientcheck).

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
| `-Dcarpet.logicPort=0` | always | let the operating system pick a free port for the CarpetLogic editor |
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

A scenario that fails is run once more, somewhere new and with bots of its own, and only one that fails
both times fails the run: a fight between bots is not the same fight twice. The log has a `RETRY` line for
the first attempt, the scenario's detail in the report says it passed on a second attempt and what the
first said, and the run's last lines name every scenario that needed one.

There are **137** of them: 52 built into `SelfTest.java` and 85 more registered in
`ScenarioIndex.java`, one file per feature. Every one spawns its bots 256 blocks further along X than
the last, so a bot left over from an earlier scenario cannot disturb a later one.

The built-in list runs first, in the order below, and the index scenarios run after it. The
"ticks" column is the timeout in game ticks; a scenario that reaches it has failed.

### Fake players, navigation and the action pack

| Scenario | Ticks | What it proves |
|---|---|---|
| `spawn` | 200 | `/player <name> spawn at <x> <y> <z>` puts the fake player within half a block of the position asked for. |
| `spawn_exact_name` | 200 | A mixed-case name survives the spawn untouched — the player list matches names ignoring case, so only the game profile tells the two apart. |
| `spawn_gamemode` | 200 | `spawn ... in creative` really puts the fake player in creative mode. |
| `nav_goto` | 600 | `nav goto` walks to a goal 12 blocks away and finishes inside its default 1-block arrival radius. |
| `nav_come` | 600 | `nav come` navigates to the position of whoever ran the command, not the bot's spawn point. |
| `nav_patrol` | 600 | `nav patrol` with two waypoints visits both of them. |
| `nav_stop` | 600 | `nav stop` stops the bot: four ticks after the command it has not moved a hundredth of a block. |
| `nav_follow` | 600 | A following bot keeps within 4 blocks of a leader that walked more than 20 blocks. |
| `nav_maze` | 900 | A bot finds its way through a six-cross-wall serpentine maze and ends within 1.5 blocks of the exit. |
| `nav_parkour` | 900 | A bot crosses a walkway with 2-block and 3-block gaps dug two deep, using sprint jumps. |
| `nav_ladder` | 1200 | A bot climbs a ladder up the west face of a tower and then descends a vine on the east face. |
| `nav_partial_blocks` | 900 | A bot walks a corridor over a slab, slab+carpet, slab+snow, cobblestone+stairs, a bare stair, a grass path and a `blocksGoalmland` block without leaving the course. |
| `nav_moving_target` | 900 | A chaser keeps a target that walks north and then turns west around a wall, never losing it. |
| `nav_crowd` | 1200 | Ten bots chasing one target through a wall with one gate stay within 5 blocks of it while the busiest tick stays inside the shared search cap and at least one of them uses the shared flow field. |
| `nav_tick_budget` | 1200 | A 125-block search is spread over at least two consecutive searching ticks without exceeding the per-bot or the shared node budget. |
| `nav_smooth` | 900 | A bot walking a diagonal across open ground covers no more than 5% more distance than the straight line. |
| `fake_player_fall_distance` | 400 | A fake player dropped from eight blocks reads a fall distance on the way down within 0.35 of what the duel simulator gives for the same drop, and takes exactly the damage a player would, three blocks of the fall being the safe distance. |
| `fake_block_front` | 900 | A sword hit from the front is stopped by a raised shield, the shield takes the wear of stopping it, and the same fighter takes the same hits in full once the shield is taken away. |
| `fake_block_behind` | 700 | The same hit with the source behind the shield lands in full and the shield takes none of it. |
| `fake_block_explosion` | 900 | Explosion damage from behind a raised shield reaches a fake player, and the shield stops none of it. |
| `fake_block_axe` | 900 | An axe from the front takes the shield down and on cooldown, and a hit from the same direction lands on the next tick. |
| `fake_sword_block` | 900 | With `swordBlockHitting` on a fake player holding a sword up loses the rule's share of a fixed hit and with it off loses all of it. |
| `chase_attack` | 600 | `nav chase attack 2.5 0 <target>` closes and damages the target. |
| `chase_crit` | 600 | `nav chase crit 2.5 0 <target>` closes and damages the target. |
| `animate_use` | 300 | `animate use` swings the off hand and `animate attack` the main hand, read off the swing each one leaves behind. |
| `item_cd` | 300 | An ender pearl throw puts the bot on a 20-tick cooldown; the bare `itemCd` reports one cleared and the cooldown is gone on the same tick. |
| `shield_disable` | 600 | A shield raised with `use continuous` is broken by an axe and goes on cooldown. |
| `kill` | 300 | `/player <name> kill` takes the bot off the server: it is gone from the player list. |
| `bot_death_respawn` | 400 | A fake player killed six blocks from its spawn stays on the server and, five ticks later, is alive at full health and back where it was. The delayed-task respawn does not stop the server. |

### Kits

| Scenario | Ticks | What it proves |
|---|---|---|
| `kit_give` | 200 | Every built-in kit loads, every entry builds, and each kit gives its bot the expected weapon, chestplate, enchantment level and stack count. |
| `kit_roundtrip` | 200 | Saving a player's inventory, giving a kit over it and restoring puts every slot back, including the selected hotbar slot — through memory and through the kit file. |
| `kit_folder` | 300 | A hand-written kit file and a saved one, dropped into `<world>/carpet-kits/`, both load after `bot kit reload` and hand out what they say. |
| `ranged_kit` | 300 | `/bot kit give <n> ranged` yields a bow, a crossbow, at least 32 arrows, a sword and real armour value. |

### Sword blocking and 1.8 combat

| Scenario | Ticks | What it proves |
|---|---|---|
| `sword_block` | 600 | With `swordBlockHitting` on, a sword-blocking player loses `swordBlockDamageMultiplier` of a fixed 4-health hit and is pushed to between a quarter and three quarters of an idle player's knockback; with the rule off both lose the full 4 and get equal knockback. |

### Upstream rules this fork carries

| Scenario | Ticks | What it proves |
|---|---|---|
| `fill_updates` | 100 | A redstone lamp lights when a redstone block is placed next to it with `fillUpdates` on, and stays dark with the rule off. |
| `interaction_updates` | 200 | A redstone block placed by a real use-item-on packet lights the lamp next to it with `interactionUpdates` on and leaves it dark with the rule off. |
| `explosion_rules` | 600 | With `optimizedTNT` on, a primed tnt leaves the stone next to it standing while `explosionNoBlockDamage` is on and blows it away while it is off. |
| `explosion_state_leak` | 600 | A leftover block position forced into the optimised explosion's static set does not make the next block-damaging explosion blow the previous one's block away. |
| `xp_explosions` | 600 | An ore blown up drops experience with `xpFromExplosions` on and none with it off. |
| `punish_wrong_tool_hits` | 300 | Hitting a block that needs a tool with bare hands costs a heart with `punishWrongToolHits` on and nothing with it off. |
| `update_suppression_block` | 100 | A barrier over an unpowered activator rail schedules a tick with `updateSuppressionBlock 0` and does not with the rule at -1. |
| `stackable_shulker_boxes` | 100 | An empty shulker box stacks `stackableShulkerBoxes` times, and a chest keeps its own stack limit either way. |
| `structure_block_ignored` | 100 | `structureBlockIgnored` drops the named block from the palette of a saved structure. |
| `persistent_parrots` | 400 | A parrot on the shoulder survives damage with `persistentParrots` on and is dropped with it off. |
| `lag_free_spawning` | 400 | The natural spawner keeps running while `lagFreeSpawning` is on, which needs `carpet.fakes.LevelInterface` to have an implementation. |
| `sculk_sensor_range` | 300 | A step 12 blocks from a sculk sensor is out of reach at the default range of 8 and inside the 16 `sculkSensorRange` sets, while one 36 blocks away is out of reach either way. |
| `summon_natural_lightning` | 400 | Of 600 bolts, at least one rolls the skeleton horse trap with `summonNaturalLightning` on, and none of twenty bolts 32 blocks away does with it off. |
| `tick_synced_world_borders` | 900 | A five second border lerp is finished after 160 game ticks at 40 ticks a second with `tickSyncedWorldBorders` on and has barely started with it off. |

### Scarpet

| Scenario | Ticks | What it proves |
|---|---|---|
| `script_run` | 100 | `/script run 1+1` runs through the same command source and returns a positive result, so the mod's server-side setup is intact. |
| `scarpet_events` | 400 | `__on_player_takes_damage` reaches a script only once an app with a handler is loaded, and `damageTickOther 60` swallows a hit sent 15 ticks after the previous one. |
| `scarpet_explosion` | 600 | `__on_explosion_outcome` fires exactly once for a plain tnt explosion once a handler is loaded. |
| `scarpet_item_use_events` | 300 | `__on_player_uses_item` is called for a real use-item packet with `scarpetItemUseEvents` on and never with it off. |
| `scarpet_world_data` | 100 | A Scarpet app saves the world data with `save()` and reads the result back. See the note below — this one is fragile in a long run. |

### CarpetLogic

| Scenario | Ticks | What it proves |
|---|---|---|
| `logic_program` | 600 | A CarpetLogic program built through the Java API walks the bot forward and reaches `COMPLETED`. |
| `logic_forever_budget` | 200 | A `FOREVER` loop with nothing to wait for does not stop the server ticking, and is still `RUNNING` after 40 ticks. |
| `logic_bot_snapshot` | 300 | `GET /api/bots` returns both bots with exactly the expected fields, the wounded bot's health equal to its real health, `pvp.style` `MELEE`, and `program` and `target` null for an idle bot. |
| `logic_combat_start_stop` | 1000 | A program of `COMBAT_START` → wait for the target below 20 health → `COMBAT_STOP` → `STOP_MOVEMENT` reaches `COMPLETED`, and the bot comes to rest: no input, no target, no navigation, and no further hits. |
| `logic_fight_node` | 1000 | A `FIGHT` node ends on the target's death rather than running its timeout, the program carries on to `COMPLETED`, and the bot comes to rest with combat off. |
| `logic_combat_option` | 1000 | `SET_COMBAT_OPTION difficulty expert` reaches `BotPvpConfig`, while an unknown option puts the program into `ERROR` with `Unknown setting: nosuchoption`. |
| `logic_on_kill_event` | 1000 | A `when_kill` handler runs when the game reports the kill. |
| `logic_totem_pop_event` | 1000 | A `when_totem_pop` handler runs only after the bot's own totem has actually popped, and the bot is back at full health afterwards. |
| `logic_stop_program_stops_fight` | 1000 | `stopProgram` in the middle of a fight leaves the program gone and the bot holding no input, with no target and not navigating. |
| `logic_admin_login` | 15000 | The web editor's admin sign-in over HTTP: with `carpetLogicAdminLogin` off its routes refuse; with it on an operator sets a password through the console's link, signs in and changes `carpetLogicMaxPrograms` with `POST /api/settings`, and the rule really has the new value. A second use of the link, a wrong password, a token from `/carpetlogic open`, a value the rule's validator refuses and the operator once deopped are all refused. It deops its operator and puts both rules back. |
| `logic_save_draft_and_autosave` | 15000 | Saving from the web editor over HTTP: a program that does not compile is kept as a draft in `<world>/carpetlogic/programs/` under a file named after it (a name with a colon, a space and a slash in it becomes a safe file name), holds its graph and the reason, comes back the same after the folder is read again, and is refused by `/carpetlogic programs run`. Saving it again writes nothing; a save from an older copy gets `409`. The version that compiles is saved over it under a new name, the file is renamed with it, and `programs run` walks the bot. It deletes its files. |
| `logic_expression_if_while` | 600 | A program whose `WHILE` condition measures the world, `distance(x, y, z) > 1.5 and $steps < 40`, with an `IF` on `$steps % 2 == 0` inside it, walks the bot to a place six blocks ahead and reaches `COMPLETED`. Its variables then hold what happened: the steps it took, how many of them were even, the distance left as the bot's position gives it, and a text put together from them. |
| `logic_scarpet_node` | 600 | One program with two `SCARPET` nodes and a `CONDITION_SCARPET`, started three times. With no owner it stops with "SCARPET only runs in programs a player started from the web editor"; for a player who is no operator it stops with "SCARPET needs … to be allowed /script run" and the block is still air; for the same player once opped the snippet sets a gold block by the bot's own `x`, `y` and `z`, adds one to the program's variable `n` and gives `n * 6`, the `IF` on `$answer == 42` takes its first branch, `query(p, 'name')` gives the bot's name, and the Scarpet condition reads the block back. The player is deopped and the block cleared afterwards. |

### The bots

| Scenario | Ticks | What it proves |
|---|---|---|
| `bot_spawn_kit` | 200 | `/bot spawn <n> mace expert` hands the bot the mace kit, turns combat on, applies `EXPERT`, refuses an unknown option and accepts the style name `sword` as `MELEE`. |
| `bot_stop_stats_trace` | 600 | An expert sword bot fights the player in front of it and is then asked about: `/bot stats` and `/bot trace` both answer, `/bot stop` succeeds, and the fighter really has stopped. |
| `sword_hits_require_aim` | 700 | A bot with its back to the target lands no hit until its view is within 30° of it, and every rotation step it takes is a whole mouse click. |
| `sword_shield_break` | 1000 | A skilled bot breaks a shield that is being held up and hurts its owner. |
| `sword_difficulty_order` | 2400 | Across six sequential expert-against-beginner duels the expert preset wins at least five. |
| `sword_damage_rate` | 520 | An expert bot against a passive, non-regenerating target in full diamond reaches an 80% hit rate over 320 ticks and drives the target's lowest health to 8 or below, reporting every miss kind and every planner tick. |
| `sword_ladder` | 1280 | Over twelve concurrent duels, four rungs of three, every preset wins at least two of its three duels against the preset below it. |
| `sword_catches_runner` | 500 | An expert bot catches a target walking away in a straight line: it closes to 3.5 blocks or less with at least two hits, sprinting a substantial share of the time. |
| `sword_shield_play` | 900 | With shield play on the bot has its shield up on at least ten ticks and takes at most 0.8× the damage of the identical fight with shield play off — at the cost of its own hits. |
| `sword_only` | 600 | Two bots with the built-in sword kit fight each other for 300 ticks, and on every tick each has its sword in the main hand, nothing in the off hand, nothing else in its inventory and is not using an item. |
| `sword_settings` | 1200 | With a diamond sword in slot 0 and a netherite axe in slot 2: `autoWeapon` off holds the sword, `autoWeapon` with `preferSword` on still holds it, `preferSword` off switches to the axe, and `bhop` on leaves the bot off the ground on more ticks than `bhop` off. |
| `sword_only` | 600 | Two bots given the built-in sword kit and set on each other: on every tick of the fight each has its sword in the main hand, nothing in the off hand, nothing else in its inventory and is not using an item. |
| `sword_hits_passive_target` | 1400 | All five difficulty presets, run at once 60 blocks apart, each land a first hit on a same-kit passive target within 500 ticks, after closing the 4.6-block gap themselves. |
| `bot_stop_stats_trace` | 600 | An expert sword bot lands a hit on the player in front of it, then `bot stats`, `bot trace` and `bot stop` each succeed and leave the fighter with combat off and navigation off. |
| `bot_budget` | 700 | With eight average bots in four duels `botSimBudget` is never exceeded; cutting it to 64 starves fighter-ticks, restoring it makes the bots plan again, and the full budget produced no starved ticks at all. |

### The ranged style

| Scenario | Ticks | What it proves |
|---|---|---|
| `bow_hits_static` | 900 | Over 24 arrows at a stationary target 20 blocks away, the measured hit rate is within 0.4 of what the ballistics model predicts from the aim error, the release tolerance and the arrow spread. |
| `bow_hits_moving` | 900 | The same comparison with the target walking across the line of fire, where the aim lead is what has to make the numbers agree. |
| `crossbow_cycle` | 900 | The charged component appears and disappears at least three times, and at least two shots put the target below full health at 14 blocks. |
| `trident_throw` | 900 | A ranged bot given three tridents completes at least one charge-and-release cast and hits twice from about twelve blocks. |
| `spear_reach` | 1200 | With the spear charged past its wind-up the bot stands at a gap where the spear reaches and the sword does not, and records the peak closing speed. |
| `spear_thrust_damage` | 900 | The bot charges a netherite spear, runs in from outside its reach and thrusts: every thrust takes the health the model gives for the closing speed the game used, within one health. |
| `ranged_closes_ground` | 600 | With a charged spear in hand the bot closes most of eighteen blocks on its target and reaches most of a sprint's speed, which a flat slowdown for every item in use made impossible. |
| `tnt_cart_safe` | 1200 | The bot lays rail and a tnt minecart beside a target hemmed into two walls, stands off at least eight blocks from the cart — where its own plan says the blast can no longer reach — never drops below 20 health, and the cart is still standing. |
| `ranged_keeps_distance` | 1200 | Against a casual sword bot in a walled arena the expert ranged bot holds at least six blocks for at least twenty ticks while holding a bow or crossbow, never closes inside three blocks while shooting, and does land a hit once it switches to the sword. |
| `ranged_duel` | 1200 | In an expert-against-expert duel thirty blocks apart the ranged bot hurts the sword bot before it has to put the sword away. |

### The crystal and anchor styles

| Scenario | Ticks | What it proves |
|---|---|---|
| `crystal_damage_matches_model` | 600 | Five end-crystal blasts at six to ten blocks from a netherite-armoured fighter each cost what `CombatMath` predicts within 0.05 of a health point, and the model's exposure estimate matches the game's. |
| `crystal_place_and_hit` | 900 | An average crystal bot places at least one crystal itself and sets at least one off, with at least its own click period between the two. |
| `crystal_never_suicides` | 900 | An unarmoured casual bot in a pit refuses every blast that would also hit itself, keeps its own totem in the offhand, and ends at full health and alive. |
| `crystal_retotem` | 600 | With the brain's own totem reflex off, a popped totem is not replaced on the pop tick but only after at least `crystal.retotem_delay` ticks. |
| `crystal_anchor` | 800 | From a stone ledge where no crystal base is in reach, an `anchor`-style expert bot places and blows at least one respawn anchor and hurts the target below. |
| `crystal_duel` | 4300 | Two experts with blast protection stripped between them pop a totem inside 1400 ticks and neither leaves the server, and then an expert beats a beginner in at least four of five rounds. |

### The mace style

| Scenario | Ticks | What it proves |
|---|---|---|
| `mace_launch_height` | 400 | A wind charge thrown at the bot's own feet lifts it as high as the duel model's arc and no higher; the game bursts a little higher off the ground than the model assumes, which the scenario allows for. |
| `mace_smash_damage` | 900 | A smash out of a measured fall does within 15% of what `CombatMath` gives for that fall, that enchantment and that armour. |
| `mace_stun_slam` | 900 | An axe takes a raised shield down and the mace hit inside the following hundred-tick window takes at least 2.5 health off what the axe left. |
| `mace_no_fall_damage_on_miss` | 900 | A launch whose target is lifted out of the arc still comes down harmless: the bot spends a second wind charge and lands on full health. |
| `mace_swap_probe` | 700 | Whether this Minecraft version lets a mace hit carry an attack cooldown collected under another item: the same drop charged off the netherite sword, with the mace swapped in on the tick of the hit, and the ticks each mace needs to be ready again. Passes either way and records the answer the style obeys. |
| `mace_breach_swap_probe` | 500 | The same question for Breach: a charged sword hit on its own and the same hit with the Breach mace swapped in on the tick of it, both against full protection netherite. Records what the style may rely on. |
| `mace_duel` | 4500 | The expert mace bot against the expert sword bot in netherite, alternating sides over six rounds: a round is a knockout or, failing that, a damage trade of at least 1.25×. The mace bot has to win four. |

### The smp style

| Scenario | Ticks | What it proves |
|---|---|---|
| `smp_heals` | 1200 | An average SMP bot wounded to 1.5 effective health recovers at least four by spending items, and then hits the dummy again — so the heal is not the bot standing still. |
| `smp_retotem` | 1100 | With `autoTotem` off, a lethal hit pops the totem and a fresh one is back 20 to 24 ticks later. |
| `smp_buffs` | 1400 | An SMP bot given thirty seconds of strength throws a splash and afterwards still has more than a thousand ticks of strength left — it re-buffed before the first ran out. |

### Matches, factions, spectating and traces

| Scenario | Ticks | What it proves |
|---|---|---|
| `match_ffa` | 1500 | `bot match ffa 4 sword skilled` starts four bots on four factions, all four deal damage and all four are hit by another bot, and the history names a winner. |
| `match_teams` | 1500 | `bot match teams 2 sword skilled` starts four bots in two teams, all four deal damage, and no `HIT` in any bot's trace is against its own side. |
| `faction_persistence` | 200 | Factions written to the world's `carpet-factions.json` come back with their names, their members and their alliance intact. |
| `spectate_roundtrip` | 300 | `/bot spectate <target>` puts the caller in spectator with its camera on the target, and `/bot spectate stop` gives back the exact game mode, position, yaw and own camera. |
| `trace_records_fight` | 1400 | The fight trace written after a fight holds exactly as many `HIT` events as the bot's own counters, with the same damage dealt and taken. |

### Drills

| Scenario | Ticks | What it proves |
|---|---|---|
| `drill_aim_scores` | 900 | `bot drill aim` is accepted with a diamond sword in hand, the run ends with at least one hit, and the player has their sword back. |
| `drill_skips_without_needs` | 400 | Empty-handed, `bot drill stunslam`, `bot drill retotem` and an unknown drill name are all refused, no drill starts, and the player still holds nothing new. |
| `drill_stunslam_shield` | 400 | A player carrying neither an axe nor a mace is refused the stun-slam drill, and a player with both gets one whose bot stands behind a raised shield with the sword kit's sword in hand, and stops it when asked. |

### `/auto-setup`

| Scenario | Ticks | What it proves |
|---|---|---|
| `autosetup_roundtrip` | 2400 | `auto-setup sword beginner` builds an arena inside the checked region, moves the player in and gives it the kit's sword, the bot lands a hit, killing the bot ends the round 1–0, re-issuing the mode keeps the score, and `auto-setup stop` restores every slot, the game mode, the position and all 5832 blocks of the snapshot region. |
| `autosetup_each_mode` | 2000 | Bare `/auto-setup` prints its menu, and each of `sword`, `smp`, `mace` and `crystal` starts a session with that mode, that bot style, the right floor block and the kit's first item in the player's hand. `ranged` is not in this list. |
| `autosetup_crash_safe` | 1200 | A session file exists on disk while fighting; after a simulated crash the live session is gone and the player is flagged unrecovered; after logging in again the inventory is fully restored, the file is deleted, `fakePlayerNavigation` is off again and the arena is gone. |
| `autosetup_rules_restored` | 900 | `carpet fakePlayerNavigation` is off when a session starts, on while it runs, and back to false after `auto-setup stop`. |
| `autosetup_login_recovers` | 600 | A player who logs out of a session has their file kept and what is in it waiting for their login; logging in again gives every slot back and deletes the file. A second player who logs out and asks for a new session without that door is given their own things back first, so the new file is written over nothing. |

### The menu

| Scenario | Ticks | What it proves |
|---|---|---|
| `gui_toggle_option` | 200 | A click on the `critical` toggle flips the setting and the button's name to `: off`; a quick-move and a swap click do not; and a change made by command shows up on the already-open button one tick later. |
| `gui_cycle_style` | 200 | Clicking the style button advances to the next style and the button name follows, and a change made by command is reflected on the next tick. |
| `gui_spawn` | 200 | Three clicks on the spawn page put a new bot on the server with that style, that difficulty and combat on. |
| `gui_no_item_theft` | 400 | Every click type over every button on all three pages leaves the viewer's inventory byte-identical, leaves nothing on the cursor, never changes which menu slots hold something, and drops nothing on the ground. |
| `gui_kit_editor_roundtrip` | 300 | A layout built in the kit editor, saved with `/bot gui saveas` and given with `/bot kit give`, comes back slot for slot identical, the viewer's inventory is unchanged throughout, and the empty editor still writes a kit. |
| `gui_quick_fight` | 600 | The quick fight button of the main menu puts two bots of the default style on the server, in a faction each of their own with combat on, and one of them lands a hit. |

### Two scenarios that are written but not registered

`SmpScenarios.pearlRetreat` and `SmpScenarios.duel` exist and are not in `ScenarioIndex`, and their
own comments say why: the pearl is regularly spent before it gets where the plan said it would, and
over eight rounds the SMP bot lost to the sword bot three or four times and won the rest, which is
a coin flip. They are left in the tree rather than weakened.

## Notes on individual scenarios

`kit_give` checks these values, one per built-in kit:

| Kit | Main hand | Chestplate | Enchantment | Stack |
|---|---|---|---|---|
| `sword` | `diamond_sword` | `diamond_chestplate` | Protection 4 | 4 `golden_apple` |
| `axe` | `diamond_sword` | `diamond_chestplate` | Protection 4 | 4 `golden_apple` |
| `smp` | `netherite_sword` | `netherite_chestplate` | Protection 4 | 16 `experience_bottle` |
| `mace` | `mace` | `netherite_chestplate` | Protection 4 | 16 `wind_charge` |
| `crystal` | `netherite_sword` | `netherite_chestplate` | Blast Protection 4 | 8 `end_crystal` |

There is no `ranged` row. `ranged_kit` checks the ranged kit separately.

`summon_natural_lightning` is the one scenario that leans on a random number. The skeleton horse
roll is one chance in fifty to twenty on a fresh world on hard, so the scenario sums up 600 bolts at
one spot and counts the horses there, then turns the rule off and sums up 20 at a spot 32 blocks
away. The chance of missing every one of 600 rolls is below one in a million; the count of zero with
the rule off is not random at all, because vanilla only ever rolls that dice in `ServerLevel
.tickThunder`, for a storm.

`explosion_state_leak` reaches into `carpet.helpers.OptimizedExplosion` by reflection to leave a
block in its static position set, which is what an explosion that skips the walk would do. Nothing
in the game can do it today, because the branch that fills the set also empties it.

The `nav_stop` scenario pins current behaviour, not the behaviour most people expect:
`stopNavigation()` clears the navigation state but leaves the movement inputs alone, so a bot that
was already walking keeps coasting at its last speed. The scenario's own comment says it should be
tightened to "the bot stops moving" once `stopNavigation()` also stops movement. It is listed here
as it stands.

`scarpet_world_data` is the fragile one. Scarpet's `save()` saves every chunk of the world on the
server thread and waits for it, which is fine on a fresh world and fine on its own, but the 51
scenarios before it have forceloaded chunks across about thirteen thousand blocks of X. Once that
backlog is deep enough the synchronous save runs past the server watchdog's sixty seconds and the
watchdog kills the server, so a full `all` run stops there on every Minecraft version with no report
written. There is no "all but one" syntax, so the way through is to run it on its own and the rest in
two lists:

```
./gradlew :26.3:runSelfTest -PselfTest=scarpet_world_data
```

## How a scenario runs

Each scenario is a record of a timeout, the bots to spawn, the commands to issue, an optional
`start` hook, and a `check` function polled every tick:

```java
record Scenario(int timeout, List<Bot> bots, List<String> commands,
                Consumer<MinecraftServer> start,
                Function<MinecraftServer, Probe> check)
{
    // and a four-argument form that leaves start as "driven by commands only"
}
```

The flow per scenario:

1. The bots are spawned with `player <name> spawn at <x> <y> <z> facing 0 0 in minecraft:overworld in <gamemode>`.
2. Every tick, the check first confirms each bot is in the player list.
3. The first time all bots are present, `start` runs and the command list is issued.
4. `check` runs every tick. The scenario ends when it returns `ok`, or when the timeout runs out.
5. Every bot is then disconnected with `player <name> disconnect` and the result is logged. Any rule
   whose value moved during the scenario is put back and the report says so.

Two details worth knowing:

- Commands run through `performPrefixedCommand` on the console's command source, so they behave
  exactly as if you had typed them.
- `tick sprint 1d` is set once at the start of the run. Sprinting only removes the wait between
  ticks; the scenarios take the same number of game ticks either way.

`nav_come` needs a command source at a position, so it uses the overload that runs a command as
though it came from a given `Vec3`. `MatchScenarios.asPlayer` does the same for the player-scoped
`/bot` subcommands.

## Running it on a Paper server

The self-test runs on the Paper plugin as well as on the mod, from the plugin's own tick task:

```
./gradlew :26.3-paper:runSelfTest
./gradlew :26.3-paper:runSelfTest -PselfTest=spawn,nav_goto
```

What differs is written down in one place, `SelfTest.Platform`, which each host installs before the
first scenario: the literal its fake-player command hangs off (`player` on the mod, `bot` on the
plugin, which is why the scenarios build their commands through `SelfTest.cmd(...)`), the scenarios
the host has no feature for, and the setup the run needs. A scenario the plugin reports unsupported
is dropped from the run and logged with the reason before the first one starts, so the report says
what did not run:

```
[selftest] SKIP fill_updates: it needs the carpet fillUpdates rule
```

Two things a course of blocks needs. A Paper server hands out the chunks of a course far from spawn
after the command that fills them has answered "That position is not loaded", so a course starts
with a `forceload` and a scenario that walks a course waits for it through `Courses.laid` rather
than walking onto ground that has not arrived. And a Paper run does not `tick sprint 1d`, which the
mod's runs do: a sprinting server gets through a scenario's nine hundred ticks before the chunk
system has handed out the ground a few chunks away.

See [Paper.md](Paper.md) for the plugin itself.

## Adding a scenario

**Do not add scenarios to `SelfTest.java`.** Several people change it at once. Put yours in a new
file in `carpet.pvp.selftest` named after your feature, and register it with one line at the end of
the static block in `ScenarioIndex.java`. `LifecycleScenarios.java` is the smallest example to
copy, and `.gitattributes` marks `ScenarioIndex.java` `merge=union` so that branches which each add
one line do not conflict.

A scenario file is a class with static methods of this shape:

```java
static Scenario myThing(String a, String b, String c, Vec3 origin)
```

`a` is `SelfA<index>`, the bot under test; `b` is `SelfB<index>`, a second player it can follow or
fight; `c` is `SelfC<index>`, an extra attacker. They are named after the scenario's index, so two
scenarios never collide. `origin` is a clear patch of flat ground 256 blocks from the next scenario.
Everyone spawns looking along +z, so a bot at `origin.add(0, 0, 2)` is already facing one at
`origin`.

Then one line in `ScenarioIndex.java`:

```java
SCENARIOS.put("my_thing", MyScenarios::myThing);
```

Inside, the usual shape is a one-element array for the state that has to survive ticks:

```java
static Scenario myThing(String a, String b, String c, Vec3 origin)
{
    int[] phase = {0};
    return new Scenario(600, List.of(new Bot(a, origin)), List.of(), server -> {
        if (phase[0] == 0)
        {
            SelfTest.run(server, "bot option " + a + " combat true");
            phase[0] = 1;
            return SelfTest.pending("the bot is fighting");
        }
        BotBody body = SelfTest.body(server, a);
        if (body == null) return SelfTest.pending("waiting for " + a + " to start fighting");
        boolean ok = body.stats().hits >= 1;
        return new Probe(ok, SelfTest.fmt("%s landed %d hits", a, body.stats().hits));
    });
}
```

`SelfTest`'s records (`Scenario`, `Probe`, `Bot`) and its static helpers are visible to every file
in the package. The ones a scenario usually needs:

| Helper | What it does |
|---|---|
| `run(server, command)` | issues a command as the console |
| `run(server, command, pos)` | issues a command as if it came from a position |
| `result(server, command)` | issues a command and returns its result, 0 on an exception |
| `player(server, name)` | the named player, or null |
| `pending(detail)` | a `Probe` that is not ok, meaning "not yet, keep polling" |
| `warmingUp(server, names...)` | true while a bot's connection has not loaded yet |
| `waiting(server, names...)` | true while a bot has no body yet |
| `hittable(players...)` | true when every player is loaded, out of hurt animation and above 4 health |
| `damage(server, victim)` | runs `/damage`, returning the health lost |
| `same(a, b)` | whether two floats are within 0.05 |
| `setBlock(pos, block)`, `fill(...)`, `forceload(...)`, `summonTnt(pos)` | return the **command strings** that build a world, so a scenario puts them in its command list rather than calling them |
| `pack(server, name)` | the bot's action pack, where the navigation state lives |
| `body(server, name)` | the bot's body, or null until it has started fighting |
| `stats(bot)` | the bot's counters, an empty set when it has no body |
| `slots(player)`, `holds(player, stack, item)`, `sameInventory(expected, actual)` | reading an inventory |
| `swordKit(name)`, `swordKit(name, withAxe)`, `shieldKit(name)`, `swordCombat(name, difficulty)` | the loadouts several scenarios share |
| `fmt(format, args...)`, `joined(parts...)` | formatting |

Conventions:

- The timeout is in game ticks. 600 covers a navigation run, 100–300 is enough for anything that
  does not walk, and a duel that has to win four of six rounds needs thousands.
- Return `new Probe(condition, message)`. The message goes in the report whether the check passed or
  failed, so put the measured numbers in it.
- Use `pending(detail)` to say "not yet, keep polling" — a `Probe` that is not ok simply means "try
  again", so the message becomes the explanation for a timeout.
- State the scenario needs before the first command (a Carpet rule, a forceload, a `give`) belongs
  in the command list or the `start` hook.
- A fake player cannot use anything until the 60-tick client-load timer of its connection runs down,
  and its listener drops block use packets until something answers the spawn teleport, so a scenario
  that drives a packet path waits for `warmingUp` and calls `confirmTeleport`.
- Entities in a forceloaded chunk only become countable once the chunk map has picked the ticket up,
  which takes some ticks. A scenario that counts entities in a chunk nobody is in waits for one to be
  countable first, as `summon_natural_lightning` does.
- A helper a scenario needs that `SelfTest` does not have belongs in the scenario's own file.

Run it on its own while you work:

```
./gradlew :26.3:runSelfTest -PselfTest=my_thing
```

Add a unit test if the scenario depends on a piece of pure logic — scenario selection and the
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

The watchdog holds the JVM open, waits up to 240 seconds for the server thread to join, then gives
the server's own worker threads up to another 240 seconds to finish. Then it reports:

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
| 3 | A thread was still running when the watchdog gave up, or the watchdog was interrupted while waiting. |

`Runtime.halt()` is used rather than a normal exit so the result does not depend on the JVM's own
shutdown sequence.

A run that executes nothing is not a pass: `allPassed` returns false for an empty result list, so a
typo in `-PselfTest=` cannot look green.

## Related pages

- [Building.md](Building.md) — how the build and `runSelfTest` are wired up
- [Commands.md](Commands.md) — the commands the scenarios drive
- [Rules.md](Rules.md) — the rules they turn on and off
- [Bots.md](Bots.md) — what the bot scenarios are about
- [AutoSetup.md](AutoSetup.md) — what the `autosetup_*` scenarios cover
- [Practice.md](Practice.md) — what the match, drill, trace and faction scenarios cover
- [Menus.md](Menus.md) — what the `gui_*` scenarios cover
- [Kits.md](Kits.md) — what the kit scenarios cover
- [CarpetLogic.md](CarpetLogic.md) — what the `logic_*` scenarios cover