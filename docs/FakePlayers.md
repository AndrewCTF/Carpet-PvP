# Fake Players & PvP Bot Scripting (Carpet PvP)

This page documents the complete fake-player and bot-control surface in Carpet PvP.
It includes lifecycle, movement, look/turn, combat, navigation, gliding, cooldown control, scheduling, and scripting usage.

Syntax conventions:
- `<required>` = required argument
- `[optional]` = optional argument
- `a|b` = alternatives

---

## Overview

Fake players are server-side players controlled through `/player <name> ...`.

Typical uses:
- PvP behavior testing (combat timings, crits, movement, shield interactions)
- automation and command scripting
- pathfinding/navigation experiments

Key permission notes:
- `/player` is gated by the `commandPlayer` rule.
- non-OP users cannot control other real players.
- `s` can be used as self-target when command source is a player.

---

## Quick Start

1) Spawn
- `/player Bot spawn`

2) Move and look
- `/player Bot move forward`
- `/player Bot look at ~ ~ ~`

3) Fight
- `/player Bot attack continuous`

4) Stop everything
- `/player Bot stop`

---

## Lifecycle Commands

Spawn variants:
- `/player <name> spawn`
- `/player <name> spawn in <gamemode>`
- `/player <name> spawn at <x> <y> <z>`
- `/player <name> spawn at <x> <y> <z> facing <yaw> <pitch>`
- `/player <name> spawn at <x> <y> <z> facing <yaw> <pitch> in <dimension>`
- `/player <name> spawn at <x> <y> <z> facing <yaw> <pitch> in <dimension> in <gamemode>`

Removal and session:
- `/player <name> kill` (fake players only)
- `/player <name> disconnect` (fake players only)
- `/player <realPlayer> shadow`

Global stop:
- `/player <name> stop`

---

## Action Modes

Many actions support these execution modes:
- default (same as `once`)
- `once`
- `continuous`
- `interval <ticks>`

Applies to commands such as:
- `use`, `jump`, `attack`, `drop`, `dropStack`, `swapHands`, `swing`

---

## Movement

### Base movement
- `/player <name> move` (stop movement)
- `/player <name> move forward`
- `/player <name> move backward`
- `/player <name> move left`
- `/player <name> move right`

### Timed movement (auto-stop)
- `/player <name> move <direction> for <ticks>`

Examples:
- `/player Bot move forward for 40`
- `/player Bot move right for 10`

### Movement modifiers integrated into move
- `/player <name> move <direction> sneaking`
- `/player <name> move <direction> sprinting`
- `/player <name> move <direction> for <ticks> sneaking`
- `/player <name> move <direction> for <ticks> sprinting`
- `/player <name> move <direction> sneaking for <ticks>`
- `/player <name> move <direction> sprinting for <ticks>`

### Standalone sneak/sprint
- `/player <name> sneak`
- `/player <name> unsneak`
- `/player <name> sprint`
- `/player <name> unsprint`
- `/player <name> sneak for <ticks>`
- `/player <name> sprint for <ticks>`

### Jump
- `/player <name> jump [once|continuous|interval <ticks>]`

Notes:
- Movement timers stop motion automatically after the requested duration.
- Jump vibration under ceilings is prevented by head-clearance checks and repathing behavior in navigation mode.

---

## Looking & Turning

### Look (absolute)
- `/player <name> look north|south|east|west|up|down`
- `/player <name> look at <x> <y> <z>`
- `/player <name> look <yaw> <pitch>`

### Turn (relative)
- `/player <name> turn left|right|back`
- `/player <name> turn <yawDelta> <pitchDelta>`

Notes:
- Yaw/pitch mapping is aligned with vanilla rotation argument behavior.

---

## Combat & Animation

### Attack
- `/player <name> attack [once|continuous|interval <ticks>]`
- `/player <name> attack crit [once|continuous|interval <ticks>]`

Crit mode behavior:
- bot jumps first, then attacks while falling.
- with modern cooldown enabled (`spamClickCombat = false`), weak spam swings are avoided.

### Swing (visual arm animation)
- `/player <name> swing [once|continuous|interval <ticks>]`

Difference from attack:
- `swing` = animation only.
- `attack` = hit detection and actual combat/block break behavior.

### Animate
- `/player <name> animate attack`
- `/player <name> animate use`
- `/player <name> animate continuous`
- `/player <name> animate interval <ticks>`

Notes:
- `animate` provides animation-only routines without forcing real interaction side effects.

---

## Interaction, Hands, and Inventory

### Use (right click)
- `/player <name> use [once|continuous|interval <ticks>]`

### Swap hands
- `/player <name> swapHands [once|continuous|interval <ticks>]`

### Hotbar
- `/player <name> hotbar <1..9>`

### Drop commands
- `/player <name> drop [once|continuous|interval <ticks>]`
- `/player <name> dropStack [once|continuous|interval <ticks>]`
- `/player <name> drop all|mainhand|offhand|<slot>`
- `/player <name> dropStack all|mainhand|offhand|<slot>`

### Equipment
- `/player <name> equip <armor_type>`
- `/player <name> equip <slot> <item>`
- `/player <name> unequip <slot>`
- `/player <name> equipment`

### Mounting
- `/player <name> mount`
- `/player <name> mount anything`
- `/player <name> dismount`

---

## Item Cooldown Control (`itemCd`)

`itemCd` controls/queries cooldown state for fake or real players through `/player` target context.

Commands:
- `/player <name> itemCd`
- `/player <name> itemCd <item>`
- `/player <name> itemCd <item> reset`
- `/player <name> itemCd <item> set`
- `/player <name> itemCd <item> set <ticks>`

Behavior summary:
- no-args form: reset/clear behavior for cooldown tracking context.
- item only: returns cooldown result value (useful for `execute store`).
- `reset`: clears cooldown for that item.
- `set`: applies default cooldown duration.
- `set <ticks>`: applies explicit cooldown.

Examples:
- `/player Bot itemCd minecraft:shield`
- `/player Bot itemCd minecraft:shield reset`
- `/player Bot itemCd minecraft:ender_pearl set`
- `/player Bot itemCd minecraft:ender_pearl set 5`

---

## Navigation (Land/Water/Air)

Enable rules:
- `/carpet fakePlayerNavigation true`
- `/carpet fakePlayerElytraGlide true` (required for AIR mode)

Navigation commands:
- `/player <bot> nav status`
- `/player <bot> nav stop`
- `/player <bot> nav goto <x> <y> <z> [arrivalRadius]`
- `/player <bot> nav goto land <x> <y> <z> [arrivalRadius]`
- `/player <bot> nav goto water <x> <y> <z> [arrivalRadius]`
- `/player <bot> nav goto air <x> <y> <z> [arrivalRadius]`
- `/player <bot> nav goto air land <x> <y> <z> [arrivalRadius]`
- `/player <bot> nav goto air drop <x> <y> <z> [arrivalRadius]`

Per-bot options:
- `/player <bot> nav options reset`
- `/player <bot> nav options <name> <true|false>`
- `/player <bot> nav options autoEatBelow <0..20>`

Option names:
- `breakBlocks`
- `placeBlocks`
- `autoTool`
- `autoEat`
- `autoEatBelow`
- `avoidLava`
- `avoidFire`
- `avoidCobwebs`
- `breakCobwebs`
- `avoidPowderSnow`

Global Carpet rules:
- `fakePlayerNavBreakBlocks`
- `fakePlayerNavPlaceBlocks`
- `fakePlayerNavAutoTool`
- `fakePlayerNavAutoEat`
- `fakePlayerNavAutoEatBelow`
- `fakePlayerNavAvoidLava`
- `fakePlayerNavAvoidFire`
- `fakePlayerNavAvoidCobwebs`
- `fakePlayerNavBreakCobwebs`
- `fakePlayerNavAvoidPowderSnow`

Notes:
- Land mode is amphibious and can route through water.
- Auto-eat selects food using survival-safe inventory consumption.
- Cobweb/lava/fire/powder-snow behavior is configurable per bot and globally.

---

## Elytra Glide Controls

Enable:
- `/carpet fakePlayerElytraGlide true`

Core:
- `/player <name> glide start`
- `/player <name> glide stop`
- `/player <name> glide status`

Freeze and arrival behavior:
- `/player <name> glide freeze`
- `/player <name> glide freeze <true|false>`
- `/player <name> glide freezeAtTarget <true|false>`
- `/player <name> glide arrival stop|freeze|descend|land|circle`

Flight tuning:
- `/player <name> glide speed <blocksPerTick>`
- `/player <name> glide rates <yawDegPerTick> <pitchDegPerTick>`
- `/player <name> glide usePitch <true|false>`

Manual drive:
- `/player <name> glide input <forward> <strafe> <up>`
- `/player <name> glide heading <yaw> <pitch>`

Goto:
- `/player <name> glide goto <x> <y> <z> [arrivalRadius]`
- `/player <name> glide goto smart <x> <y> <z> [arrivalRadius]`

Launch assist:
- `/player <name> glide launch assist <true|false>`
- `/player <name> glide launch pitch <deg>`
- `/player <name> glide launch speed <blocksPerTick>`
- `/player <name> glide launch forwardTicks <ticks>`

---

## Scheduling Commands

New scheduler command:
- `/schedule command <ticks> <command...>`
- `/schedule list`
- `/schedule clear`

Examples:
- `/schedule command 5 clear HerobaneNair`
- `/execute as example_bot run schedule command 1 function example:example`

Notes:
- Scheduled commands run at server tick time.
- Useful for delayed bot actions and scripted sequences.

---

## Distance Command Integration

`/distance` now returns a meaningful integer result (spherical distance) suitable for scoreboard storage.

Example:
- `/execute store result score @s dist run distance from 0 64 0 to 10 64 10`

Distance forms:
- `/distance from [<x> <y> <z>]`
- `/distance from <x1> <y1> <z1> to [<x2> <y2> <z2>]`
- `/distance to [<x> <y> <z>]`

---

## Scarpet Tips

Recommended pattern:
- call `/player` commands through app events/ticks/schedules.
- avoid direct `run()` usage from bare `/script run` contexts if expecting immediate command-side effects.

Example snippets:
```scarpet
run('player Bot spawn');
run('player Bot look at 0 64 0');
run('player Bot attack crit continuous');
```

---

## Troubleshooting

Bot does not move:
- verify spawn success
- ensure no active contradictory command (`move` stop, `stop`)
- check if pathfinding rule is enabled for nav commands

Bot does not crit:
- use `attack crit continuous`
- ensure target is in reach and in crosshair
- if modern cooldown is active, avoid too-short intervals

Navigation stalls:
- check hazard options (`avoid*`, `breakCobwebs`)
- ensure bot has valid tools/blocks if break/place is expected
- verify chunks around destination are loaded

---

## Full `/player` Surface (High-Level)

- Lifecycle: `spawn`, `kill`, `disconnect`, `shadow`, `stop`
- Motion: `move`, `sneak/unsneak`, `sprint/unsprint`, `jump`
- Aim: `look`, `turn`
- Combat: `attack`, `attack crit`, `swing`, `animate`
- Interaction: `use`, `swapHands`, `hotbar`, `drop`, `dropStack`
- Equipment: `equip`, `unequip`, `equipment`
- Riding: `mount`, `dismount`
- Navigation: `nav ...`
- Elytra control: `glide ...`
- Item cooldowns: `itemCd ...`
