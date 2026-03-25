# Fake Players & PvP Bot Scripting (Carpet PvP)

This page documents the complete fake-player and bot-control surface in Carpet PvP.
It includes lifecycle, movement, look/turn, combat, navigation, gliding, cooldown control, scheduling, and scripting usage.

Syntax conventions:
- `<required>` = required argument
- `[optional]` = optional argument
- `a|b` = alternatives

---

## Overview

Fake players are server-side players controlled through `/player <target> ...`.

Typical uses:
- PvP behavior testing (combat timings, crits, movement, shield interactions)
- automation and command scripting
- pathfinding/navigation experiments

Key permission notes:
- `/player` is gated by the `commandPlayer` rule.
- `<target>` accepts player names and player selectors (`@s`, `@p`, `@r`, `@a[...]`).
- Selector targets can fan out to multiple players (for example `@a[tag=bot]`).
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

5) Use selectors with execute context
- `/execute as @a[tag=bot] run player @s attack continuous`

---

## Lifecycle Commands

Spawn variants:
- `/player <name> spawn`
- `/player <name> spawn in <gamemode>`
- `/player <name> spawn at <x> <y> <z>`
- `/player <name> spawn at <x> <y> <z> facing <yaw> <pitch>`
- `/player <name> spawn at <x> <y> <z> facing <yaw> <pitch> in <dimension>`
- `/player <name> spawn at <x> <y> <z> facing <yaw> <pitch> in <dimension> in <gamemode>`
- `/spawnplayer <name> [at <x> <y> <z>] [facing <yaw> <pitch>] [in <dimension>] [in <gamemode>]` (alias for spawn)

Selector-targeting variants (for control commands):
- `/player @s stop`
- `/player @a[tag=bot] move forward`
- `/execute as @a[tag=bot] run player @s look at ~ ~ ~`

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
- Bot jumps, waits until it is falling **and** `fallDistance > 0`, then strikes.
- While airborne, the bot tracks the target entity and looks at it so the hit connects.
- Sprinting is automatically disabled before jump and attack (required for vanilla crit detection).
- After landing, the bot waits at least 3 ticks (or the configured interval) before the next jump.
- MISS swings are suppressed during crit mode to prevent spam while airborne.
- With modern cooldown enabled (`spamClickCombat = false`), weak spam swings are avoided.

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

## Navigation

The navigation system provides Baritone-like pathfinding and movement for fake players, including advanced traversal modes like parkour, pillar-jumping, break-through mining, mob avoidance, sprinting optimization, and more.

### Enable rules:
- `/carpet fakePlayerNavigation true`
- `/carpet fakePlayerElytraGlide true` (required for AIR mode)

### Navigation commands:

**Goto (walk/swim/fly to a position):**
- `/player <bot> nav status`
- `/player <bot> nav stop`
- `/player <bot> nav goto <x> <y> <z> [arrivalRadius]`
- `/player <bot> nav goto land <x> <y> <z> [arrivalRadius]`
- `/player <bot> nav goto water <x> <y> <z> [arrivalRadius]`
- `/player <bot> nav goto air <x> <y> <z> [arrivalRadius]`
- `/player <bot> nav goto air land <x> <y> <z> [arrivalRadius]`
- `/player <bot> nav goto air drop <x> <y> <z> [arrivalRadius]`

**Follow (stay near another player):**
- `/player <bot> nav follow <playerName> [radius]`
  - Bot will continuously re-path to stay within `radius` blocks of the target player.
  - Default radius: 3 blocks.

**Chase (follow and attack a player):**
- `/player <bot> nav chase attack [<distance>] [<interval>] [<playerName>]`
  - Navigates toward the target player and attacks when in range.
  - If `<playerName>` is omitted and only one other player is online, auto-selects them.
  - If multiple players are online and no name is given, lists available targets.
  - `<distance>` (optional, 0.5–3.0): attack range in blocks. Default: 2.5. Lower values make the bot get closer before attacking; max 3.0 uses full reach.
  - `<interval>` (optional, 0+): ticks after a successful hit before the next allowed hit window. Default: 0 (continuous).
- `/player <bot> nav chase crit [<distance>] [<interval>] [<playerName>]`
  - Same as `chase attack` but uses crit attacks (jump + hit while falling).
  - Crit chase uses timed jump-reset behavior: it stays grounded during cooldown and jumps once near the next hit window.
- `/player <bot> nav chase jumpreset [<distance>] [<interval>] [<playerName>]`
  - Alias of crit chase with the same timed jump-reset behavior.
- `/player <bot> nav chase stop`
  - Stops chasing (same as `nav stop`).

Chase behavior details:
- Re-paths to the target every 10 ticks.
- When within the configured distance, stops moving, faces the target, and attacks.
- When the target moves out of range, resumes pathfinding.
- In crit/jumpreset modes, the bot performs a single timed jump per hit cycle instead of constant hopping.
- If the target dies or disappears, chase and attack actions stop.
- Requires `fakePlayerNavigation` rule enabled.

Examples:
- `/player Bot nav chase attack` — chase auto-selected target with default range and speed
- `/player Bot nav chase attack 1.5 10 Steve` — close-range attack with 10-tick post-hit interval
- `/player Bot nav chase crit 2.5 5 Steve` — timed crit jump-reset at 2.5 range with 5-tick post-hit interval
- `/player Bot nav chase jumpreset 3 50 Steve` — wait 50 ticks after each successful hit, then timed jump-reset for the next hit

**Come (navigate to the command sender's position):**
- `/player <bot> nav come [arrivalRadius]`
  - Shorthand for `nav goto` to your current position.

**Mine (find and mine specific blocks):**
- `/player <bot> nav mine <block> [count] [radius]`
  - Navigates to the nearest instance of `<block>` and mines it.
  - Repeats until `count` blocks are mined (use -1 for unlimited).
  - Default radius: 32 blocks.
  - Example: `/player Bot nav mine minecraft:diamond_ore 10 64`

**Patrol (walk between waypoints):**
- `/player <bot> nav patrol <pos1> <pos2> [pos3] [pos4] [loop|once]`
  - Walks between the given waypoints.
  - `loop` (default): cycles endlessly through waypoints.
  - `once`: walks through waypoints once, then stops.

### Per-bot options:
- `/player <bot> nav options reset`
- `/player <bot> nav options <name> <true|false>`
- `/player <bot> nav options <name> <integer>`

Option names (boolean):
- `breakBlocks` – Allow breaking blocks in the way
- `placeBlocks` – Allow placing blocks to bridge gaps
- `autoTool` – Auto-select best tool when breaking
- `autoEat` – Auto-eat food when hungry
- `avoidLava` – Avoid routing through lava
- `avoidFire` – Avoid routing through fire
- `avoidCobwebs` – Avoid routing through cobwebs
- `breakCobwebs` – Break cobwebs in the way
- `avoidPowderSnow` – Avoid powder snow
- `allowParkour` – Allow gap-jumping (up to 4 blocks)
- `allowPillar` – Allow pillar-jumping (place block at feet to ascend)
- `allowBreakThrough` – Allow mining through obstacles
- `allowDescendMine` – Allow downward mining
- `allowSprint` – Allow sprinting (reduces path cost)
- `mobAvoidance` – Avoid hostile mobs when pathing
- `avoidSoulSand` – Penalize soul sand paths
- `allowOpenDoors` – Allow opening doors
- `allowOpenFenceGates` – Allow opening fence gates
- `allowSwimming` – Allow underwater swimming (default: false = float on surface)

Option names (integer):
- `autoEatBelow` – Hunger threshold for auto-eat (0-20)
- `mobAvoidanceRadius` – Radius to avoid mobs (1-32)
- `maxFallHeight` – Maximum safe fall height (1-64)

### Global Carpet rules:
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
- `fakePlayerNavAllowParkour` – Enable parkour gap-jumping (default: true)
- `fakePlayerNavAllowPillar` – Enable pillar-jumping (default: false)
- `fakePlayerNavAllowBreakThrough` – Enable mining through obstacles (default: false)
- `fakePlayerNavAllowDescendMine` – Enable downward mining (default: false)
- `fakePlayerNavAllowSprint` – Enable sprinting (default: true)
- `fakePlayerNavMobAvoidance` – Enable mob avoidance (default: false)
- `fakePlayerNavMobAvoidanceRadius` – Mob avoidance radius (default: 8)
- `fakePlayerNavMaxFallHeight` – Maximum safe fall (default: 4)
- `fakePlayerNavAvoidSoulSand` – Penalize soul sand (default: false)
- `fakePlayerNavAllowOpenDoors` – Allow door opening (default: true)
- `fakePlayerNavAllowOpenFenceGates` – Allow fence gate opening (default: true)
- `fakePlayerNavAllowSwimming` – Allow underwater swimming (default: false)

### Pathfinding details:

The A* pathfinder supports the following movement types:
- **WALK** – Standard cardinal + diagonal movement
- **JUMP** – Step up 1 block
- **FALL** – Descend (up to maxFallHeight)
- **PARKOUR** – Gap-jump 2-4 blocks, including ascending parkour
- **PILLAR** – Place block at feet and jump up (high cost)
- **BREAK_THROUGH** – Mine through 1-2 obstacle blocks
- **DESCEND_MINE** – Mine downward
- **SWIM** – Water traversal (surface floating or underwater swimming)

Cost model (Baritone-inspired):
- Walk = 1.0, diagonal = √2
- Sprint discount (0.8× cost for flat walking)
- Jump penalty (+0.4 per jump)
- Fall damage penalty (+2.0 per block beyond safe threshold)
- Break penalty (base cost + hardness × 2)
- Pillar penalty (20.0)
- Soul sand penalty (2.5× cost)
- Ice penalty (1.3× cost – prefers non-ice paths where possible)
- Mob avoidance overlay (+4.0 cost near hostile mobs)
- Door/fence gate traversal (+1.0 cost)

Water navigation modes:
- **Floating (default)**: Bot stays at the water surface, auto-jumps to keep afloat, and navigates horizontally. Pathfinder prefers water-surface positions.
- **Swimming (optional, `allowSwimming=true`)**: Bot can dive underwater and navigate in 3D through water. Uses sprint-swimming for speed. Descends by sneaking, ascends by jumping.

Ice handling:
- The bot detects ice blocks (ice, packed ice, blue ice, frosted ice) and adjusts movement:
  - Sprinting is disabled on ice to prevent overshooting waypoints
  - Forward speed is reduced when approaching a waypoint on ice
  - Waypoint arrival distance is increased on ice (1.8 blocks vs normal 0.85)
  - Pathfinder slightly penalizes ice paths (1.3× cost) to prefer non-slippery routes

Notes:
- Land mode is amphibious and can route through water.
- Auto-eat selects food using survival-safe inventory consumption.
- Cobweb/lava/fire/powder-snow behavior is configurable per bot and globally.
- Partial paths are returned when pathfinder limits are hit, allowing progress toward distant goals.
- The pathfinder pre-computes a mob danger map each path calculation for hostile mob avoidance.

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
- `/schedule command 5 clear Bot`
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
- ensure target is in reach (within ~3 blocks) and in line of sight
- the bot automatically looks at the target while airborne; if the target moves behind a wall the ray trace may miss
- sprinting is disabled automatically; if the bot is still sprinting for some reason, crits won't register
- if modern cooldown is active, avoid too-short intervals (minimum 3 ticks between crits)
- check that the bot is not in water, on a ladder, or blind (`canCriticalAttack` conditions)

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
- Navigation: `nav goto|follow|chase|come|mine|patrol|options|status|stop`
- Elytra control: `glide ...`
- Item cooldowns: `itemCd ...`
