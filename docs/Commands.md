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
- Summary: Spawn/manage fake players for testing. See also: [FakePlayers.md](FakePlayers.md)
- Usage:
  - /player <name> spawn [at <x> <y> <z>] [facing <yaw> <pitch>]
  - /player <name> kill
  - /player <name> <action...>

## /counter
- Permission: any (requires rule hopperCounters)
- Summary: Query/reset wool counters.
- Usage:
  - /counter [<color>] [reset]

## /distance
- Permission: any (requires rule commandDistance)
- Summary: Measure distance between points.
- Usage:
  - /distance <x1> <y1> <z1> <x2> <y2> <z2>

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
