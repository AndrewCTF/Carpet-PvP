# Carpet PvP Commands

This page documents the in-game commands provided by Carpet PvP. Syntax shown uses Brigadier-style notation.

Legend:
- <> required, [] optional, | alternatives
- Permission: required op level

## /info
- Permission: any
- Summary: Display basic server and Carpet info.
- Usage:
  - /info

## /log
- Permission: op
- Summary: Subscribe/unsubscribe to Carpet loggers and view events.
- Usage:
  - /log <logger> [add|remove|clear] [player]
  - /log list

## /profile
- Permission: op
- Summary: Simple tick profiler of server operations.
- Usage:
  - /profile [start|stop|dump]

## /player
- Permission: op
- Summary: Spawn/manage fake players for testing, combat automation, nav/glide control, item cooldown control, and animation control. See also: [FakePlayers.md](FakePlayers.md)
- Usage:
  - /player <target> spawn [at <x> <y> <z>] [facing <yaw> <pitch>]
  - /player <target> kill
  - /player <target> move <forward|backward|left|right> [for <ticks>] [sneaking|sprinting]
  - /player <target> attack [crit] [once|continuous|interval <ticks>]
  - /player <target> swing [once|continuous|interval <ticks>]
  - /player <target> animate <attack|use|continuous|interval <ticks>>
  - /player <target> nav stop|status
  - /player <target> nav goto [land|water|air [land|drop]] <x> <y> <z> [arrivalRadius]
  - /player <target> nav follow <playerName> [radius]
  - /player <target> nav come [arrivalRadius]
  - /player <target> nav mine <block> [count] [radius]
  - /player <target> nav patrol <pos1> <pos2> [pos3] [pos4] [loop|once]
  - /player <target> nav options [reset|<name> <value>]
  - /player <target> glide ...
  - /player <target> itemCd [<item> [reset|set [ticks]]]
  - /player <target> <action...>
  - target supports player selectors like @s, @p, @r, @a[tag=bot]
  - execute-context example: /execute as @a[tag=bot] run player @s attack continuous

## /spawnplayer
- Permission: op
- Summary: Dedicated fake-player spawn alias for /player <name> spawn.
- Usage:
  - /spawnplayer <name>
  - /spawnplayer <name> at <x> <y> <z>
  - /spawnplayer <name> at <x> <y> <z> facing <yaw> <pitch>
  - /spawnplayer <name> at <x> <y> <z> facing <yaw> <pitch> in <dimension>
  - /spawnplayer <name> at <x> <y> <z> facing <yaw> <pitch> in <dimension> in <gamemode>

## /counter
- Permission: any (requires rule hopperCounters)
- Summary: Query/reset wool counters.
- Usage:
  - /counter [<color>] [reset]

## /distance
- Permission: any (requires rule commandDistance)
- Summary: Measure distance between points and return integer result for execute-store scoreboard usage.
- Usage:
  - /distance from [<x> <y> <z>]
  - /distance from <x1> <y1> <z1> to [<x2> <y2> <z2>]
  - /distance to [<x> <y> <z>]

## /schedule
- Permission: op (uses commandPlayer permission gate)
- Summary: Run commands after a delay in ticks.
- Usage:
  - /schedule command <ticks> <command...>
  - /schedule list
  - /schedule clear

## /draw
- Permission: any (requires rule commandDraw)
- Summary: Draw shapes via scarpet app.
- Usage:
  - /draw <tool|shape> [...]

## /mobai
- Permission: op
- Summary: Toggle and inspect mob AI.
- Usage:
  - /mobai <enable|disable|status> [@e selector]

## /perimeterinfo
- Permission: any
- Summary: Show mobcap and spawnable areas around you.
- Usage:
  - /perimeterinfo

## /spawn
- Permission: any
- Summary: Mob spawn simulation utilities.
- Usage:
  - /spawn [help|attempts|...]

Notes
- Some commands are enabled only if their corresponding rules are set (see Carpet rules in-game: /carpet, or the docs site).
- For exhaustive options and examples, run the command without args or use tab-completion.
