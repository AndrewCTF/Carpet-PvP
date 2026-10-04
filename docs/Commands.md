# Commands

Every command this mod registers, with the page that covers it in detail. The syntax is Brigadier's:
`<required>`, `[optional]`, `a|b` alternatives.

Two things to know before the list:

- Most commands are behind a rule. Where the rule is off, the command is not registered at all and
  reads as unknown. `/carpet` lists which rules you can change; see [Rules.md](Rules.md) for the
  fork's own rules.
- A rule set to `ops` means op level. `true` means everyone, `false` means nobody, and `0` to `4` is
  an explicit level.

## Fake players and bots

| Command | Permission | What it does | Details |
|---|---|---|---|
| `/player <name> spawn ...` | `commandPlayer` | Spawns a fake player, with optional position, facing, dimension and game mode. | [FakePlayers.md](FakePlayers.md#spawning) |
| `/player <target> <action>` | `commandPlayer` | Drives a player: `move`, `sneak`, `sprint`, `jump`, `use`, `attack`, `swing`, `animate`, `look`, `turn`, `hotbar`, `drop`, `dropStack`, `swapHands`, `mount`, `dismount`, `equip`, `unequip`, `equipment`, `itemCd`, `kill`, `disconnect`, `shadow`, `stop`. | [FakePlayers.md](FakePlayers.md) |
| `/player <target> nav ...` | `commandPlayer` and `fakePlayerNavigation` | Navigates a fake player: `goto`, `follow`, `chase`, `come`, `mine`, `patrol`, `options`, `status`, `stop`. | [FakePlayers.md](FakePlayers.md#navigation) |
| `/player <target> glide ...` | `commandPlayer` and `fakePlayerElytraGlide` | Elytra controls for a fake player: `start`, `stop`, `freeze`, `arrival`, `launch`, `speed`, `rates`, `usePitch`, `input`, `heading`, `goto`, `freezeAtTarget`, `status`. | [FakePlayers.md](FakePlayers.md#elytra-gliding) |
| `/player <target> ai ...` | `commandPlayer` | Reads and changes a fake player's combat AI configuration. | [FakePlayers.md](FakePlayers.md#combat-ai-ai) |
| `/player <target> faction ...` | `commandPlayer` | Creates, joins, leaves, allies and inspects factions. | [FakePlayers.md](FakePlayers.md#factions) |
| `/spawnplayer <name> [...]` | `commandPlayer` | Alias for `/player <name> spawn`, forwarding any trailing arguments. | [FakePlayers.md](FakePlayers.md#spawning) |
| `/bot kit ...` | `commandBot` | `list`, `reload`, `give`, `save`, `delete`, `restore`. Hands out, saves and restores PvP loadouts. | [Kits.md](Kits.md) |
| `/auto-setup [mode] [difficulty]`, `/auto-setup stop` | `commandAutoSetup` | On its own, prints a menu of the modes and difficulties to click. With a mode, builds an arena next to you, hands out the kit, puts a bot in front of you and keeps score between rounds. `stop` takes it all back down. | below |
| `/carpetlogic ...` | `commandCarpetLogic` | `status`, `open`, `programs`, `programs run`, `programs stop`, `bots`. Runs bot programs and opens the web editor. | [CarpetLogic.md](CarpetLogic.md) |
| `/schedule ...` | `commandPlayer` | `command <ticks> <command>`, `list`, `clear`. Runs a command after a delay in ticks. | below |

## Settings

| Command | Permission | What it does | Details |
|---|---|---|---|
| `/carpet` | `carpetCommandPermissionLevel` | Lists and changes rules: `/carpet <rule> <value>`, `/carpet <rule>`, `/carpet list [tag]`, `/carpet defaults`, `/carpet setDefault <rule> <value>`, `/carpet removeDefault <rule>`. | [Rules.md](Rules.md) |

## World and block information

| Command | Permission | What it does | Details |
|---|---|---|---|
| `/info block <pos> [grep <regexp>]` | `commandInfo` | Prints the block state, tile entity and neighbours at a position. `grep` filters the output to matching lines. | below |
| `/distance from [<pos>] [to [<pos>]]`, `/distance to [<pos>]` | `commandDistance` | Measures the in-game distance between points and returns a number, for `execute store`. | below |
| `/perimeterinfo [<pos>] [<mob>]` | `commandPerimeterInfo` | Counts potential spawn spots around a position, optionally for one mob type. | below |
| `/spawn ...` | `commandSpawn` | Mob spawn simulation: `list <pos>`, `tracking [start [from to] | stop | <type>]`, `test [<ticks> [<colour>]]`, `mocking <bool>`, `rates [reset | <type> <rounds>]`, `mobcaps [set <cap> | <dimension>]`, `entities [<type> [all]]`. | below |
| `/draw <shape> ...` | `commandDraw` | Draws a shape out of blocks: `sphere`, `ball`, `diamond`, `pyramid`, `cone`, `cylinder`, `cuboid`, each with `<block> [replace <filter>]`. | below |
| `/counter [<colour>] [reset\|realtime]`, `/counter reset` | `hopperCounters` | Reads and resets the sixteen hopper counters, by wool colour. | below |
| `/track <mob type> <aspect>` | `commandTrackAI` | Tracks a mob type's AI, or `clear`s the tracking. | below |

## Diagnostics

| Command | Permission | What it does | Details |
|---|---|---|---|
| `/log [<logger> [<option>] [<player>]]`, `/log clear [<player>]` | `commandLog` | Subscribes players to a logger and configures it. `/log` alone lists them. | below |
| `/profile [health|entities] [<ticks>]` | `commandProfile` | Reports on server performance over the next `ticks` ticks (20 to 24000, default 100). | below |
| `/perf` | `perfPermissionLevel` | Vanilla's server performance report. Registered only on a non-dedicated server, so in singleplayer. | — |
| `/testcarpet ...` | dev builds only | `dump [category]` prints the rules to stdout; anything else prints a message. Registered only in a development environment. | — |

## Scripting

| Command | Permission | What it does | Details |
|---|---|---|---|
| `/script ...` | `commandScript` | The Scarpet language: `globals`, `resume`, `stop`, `run`, `invoke`, `invokepoint`, `invokearea`, `scan`, `fill`, `outline`, `load`, `unload`, `event`, `download`, `remove`, and the same set under `/script in <app>`. | [docs/scarpet](scarpet/Documentation.md) |

## Notes on the individual commands

### `/schedule`

```
/schedule command <ticks> <command...>
/schedule list
/schedule clear
```

The scheduled command keeps the source and permissions of whoever scheduled it, so
`/execute as <player> run schedule command 1 ...` runs it as that player. `list` prints each entry
with its remaining ticks and who scheduled it. `clear` drops everything, and the list is also dropped
when the server stops. A scheduled command that throws is logged and does not stop the server.

### `/info block`

Needs `commandInfo`. Below op level 2 it refuses to read a position whose chunk is not loaded or that
is out of world bounds. The output is a list of lines: the header, the block state, then anything
interesting about the tile entity and the neighbours. `grep <regexp>` prints only the lines that
match, which is how you pick one field out of a busy block.

### `/distance`

Returns the distance as an integer, so it works with `execute store result score ... run distance`.
`from` sets the start point, defaulting to your own position, and `to` finishes the measurement;
`/distance to` sets an end point to compare against later. The rules that read the stored values —
`carpets` and `commandInfo` for placing carpets — are upstream's.

### `/perimeterinfo`

Counts the positions around a block where the game thinks a mob could spawn: how many are in liquid,
how many are on solid ground, and, if you name a mob, how many that specific mob could use and where.
A few sample positions are printed.

### `/spawn`

Runs the game's own spawn logic in a sandbox so you can see what would happen and why.

| Subcommand | What it does |
|---|---|
| `list <pos>` | which mobs could spawn at a position |
| `tracking` | the tracking report; `start [from to]` begins it over an area, `stop` ends it |
| `tracking <type>` | the recent spawns of one mob category |
| `test [<ticks> [<colour>]]` | runs the spawn cycle and counts what it produced, optionally into a hopper counter |
| `mocking <bool>` | turn mob mocking on or off |
| `rates [reset]` | current spawn rates; `rates <type> <rounds>` sets one |
| `mobcaps [set <cap>]` | the mob cap, per dimension or overridden |
| `entities [<type> [all]]` | the entity list the spawner works from |

### `/draw`

Every shape takes a centre, a radius and, for the shapes with a height, a height and an orientation.
`pyramid` and `cone` also take `up` or `down`. Shapes are drawn with `<block>`, and
`replace <filter>` first removes the blocks that match a predicate, so you can draw through terrain.

### `/counter`

Needs `hopperCounters`. Hoppers pointing into a block of dyed wool count what passes through them, one
stack per tick per hopper, in sixteen channels.

```
/counter                       every counter
/counter <colour>              one counter
/counter <colour> realtime     one counter, as items per real second
/counter <colour> reset        zero one counter
/counter reset                 zero every counter
```

In survival you can do the same by placing carpet on the wool instead of using the command.

### `/track`

```
/track <mob type> clear
/track <mob type> <aspect>
```

The mob type comes from the entity registry and the aspect is one of the tracking types the mod knows
about. There are only two aspects today, and both apply to villagers: `iron_golem_spawning` and
`breeding`. Tab completion tells you which apply to the mob type you typed. Tracking is global, not
per player.

### `/log`

```
/log                                  list the loggers
/log <logger> [clear]                 subscribe yourself, or unsubscribe
/log <logger> <option> [player]       subscribe with an option set
/log clear [player]                   unsubscribe from everything
```

| Logger | Options |
|---|---|
| `tps` | none, it is a HUD |
| `packets` | none, it is a HUD |
| `mobcaps` | `dynamic`, `overworld`, `nether`, `end` |
| `counter` | one per dye colour, `white` by default |
| `pathfinding` | `2`, `5`, `10` — how many entities per mob type |
| `tnt` | `brief`, `full` |
| `projectiles` | `brief`, `full` |
| `fallingBlocks` | `brief`, `full` |
| `explosions` | `brief`, `full` |

The last five print to chat; the first four are overlays, and only work with the mod on the client.
A logger with no options refuses the option form, and you subscribe another player by naming them
last.

### `/profile`

`/profile` and `/profile health [ticks]` report on the server tick itself; `/profile entities
[ticks]` on the entity count. The report is printed to whoever asked for it when the ticks are up.

### `/auto-setup`

`/auto-setup` on its own prints two lines of buttons: the modes, then the difficulties. Every button
runs the same command as if it had been typed, so one click is enough.

`/auto-setup <mode> [difficulty]` does the whole thing:

- turns on the rules the bot needs, remembering which ones it changed;
- builds an arena twenty blocks east of you: a flat fenced floor for `sword` and `smp`, open sky over
  four pillars for `mace`, an obsidian floor with a few holes in it for `crystal`;
- saves your inventory, your place and your game mode to disk, then gives you the kit of the mode
  and moves you into the arena in survival;
- spawns a bot with the same kit at the difficulty you asked for, facing you, and starts the fight
  after three seconds.

A round ends when one of the two is down, or when a totem goes off in crystal. The score is kept,
and the menu after each round is `[Rematch] [Easier] [Harder] [Change mode] [Stop]`, which run
`/auto-setup <mode>`, `/auto-setup <mode> <difficulty>`, the menu, and `/auto-setup stop`.

Asking for the mode you are already fighting is a rematch; asking for another one ends the session
you had and starts a new one. A mode whose combat style is not written yet falls back to the sword
and is still playable.

`/auto-setup stop` takes the bot away, puts every block the arena wrote back, and gives you your
inventory, your place and your game mode. The same happens if you log out or the server stops. If
the server dies instead, the inventory stays on disk until you log back in, and then it is handed
back along with the arena coming down.

## Related pages

- [Rules.md](Rules.md) — the rules these commands are gated on
- [FakePlayers.md](FakePlayers.md) — `/player` in full
- [Kits.md](Kits.md) — `/bot kit`
- [CarpetLogic.md](CarpetLogic.md) — `/carpetlogic`
- [SwordBlocking.md](SwordBlocking.md) — `swordBlockHitting`
- [Paper.md](Paper.md) — the same commands on a Paper server
- [Building.md](Building.md) — building the mod
- [SelfTest.md](SelfTest.md) — the self-test scenarios
- [docs/scarpet](scarpet/Documentation.md) — the Scarpet language