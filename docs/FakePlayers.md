# Fake Players & PvP Bot Scripting (Carpet PvP)

This page is the comprehensive guide for creating and controlling fake players (bots) in Carpet PvP.
It covers:
- spawning/killing/disconnecting fake players
- movement, looking, item use, inventory/gear
- attacking (including reliable **critical hits**)
- scripting patterns using Scarpet (`/script`) to build simple bots

> Note: Command syntax uses Brigadier conventions: `<required>` and `[optional]`.

---

## What is a fake player?

A fake player is a server-side player entity (similar to vanilla player mechanics) that can be controlled via commands.
Typical uses:
- PvP testing (timings, knockback, sprint-reset, sword blocking, etc.)
- automation testing (right click, attack, block breaking)
- demo bots for Scarpet scripts

Carpet PvP fake players are controlled via:
- `/player <name> ...`

### Permissions / safety

- You generally need OP permissions (or the appropriate carpet rule permission) to use `/player`.
- Non-OP players can’t control *other real players*.
- You can use `s` as a convenience alias meaning “self” (only if the command source is a player).

---

## Quick start (manual)

### 1) Spawn a bot

- `/player Bot spawn`

Spawn with full options:

- `/player Bot spawn at <x> <y> <z> facing <yaw> <pitch> in <dimension> in <gamemode>`

Examples:
- Spawn at your position facing north-ish:
  - `/player Bot spawn at ~ ~ ~ facing 0 0`
- Spawn in the Nether:
  - `/player Bot spawn at 0 80 0 facing 180 0 in minecraft:the_nether`

### 2) Make it move and look

- `/player Bot move forward`
- `/player Bot look at <x> <y> <z>`
- `/player Bot sprint`
- `/player Bot sneak`

Stop movement:
- `/player Bot move`

### 3) Make it fight

Normal attacks:
- `/player Bot attack continuous`

Critical attacks:
- `/player Bot attack crit continuous`

Stop all actions:
- `/player Bot stop`

---

## Spawn / lifecycle commands

### Spawn

- `/player <name> spawn`
- `/player <name> spawn in <gamemode>` (requires permission level 2)
- `/player <name> spawn at <x> <y> <z>`
- `/player <name> spawn at <x> <y> <z> facing <yaw> <pitch>`
- `/player <name> spawn at <x> <y> <z> facing <yaw> <pitch> in <dimension>`
- `/player <name> spawn at <x> <y> <z> facing <yaw> <pitch> in <dimension> in <gamemode>` (requires permission level 2)

### Remove

- `/player <name> kill` (fake players only)
- `/player <name> disconnect` (fake players only)

### Shadow

- `/player <realPlayerName> shadow`

Creates a “shadow” fake player based on a real player (useful for testing without forcing the real player to perform actions).
Restrictions:
- You cannot shadow fake players.
- You cannot shadow the singleplayer server owner.

---

## Core control model: actions + modes

Most actions support 4 modes:
- default (same as `once`)
- `once`
- `continuous` (runs every tick)
- `interval <ticks>`

Examples:
- `/player Bot use once`
- `/player Bot jump continuous`
- `/player Bot attack interval 10`

Stop everything:
- `/player Bot stop`

---

## Movement

### Directional movement

- `/player <name> move` (stop movement)
- `/player <name> move forward`
- `/player <name> move backward`
- `/player <name> move left`
- `/player <name> move right`

### Sneak / sprint

- `/player <name> sneak`
- `/player <name> unsneak`
- `/player <name> sprint`
- `/player <name> unsprint`

### Jump

- `/player <name> jump [once|continuous|interval <ticks>]`

---

## Looking / rotation

Fake players act on what they are looking at (for attacking and right-click use).

### Look (absolute)

- `/player <name> look north|south|east|west|up|down`
- `/player <name> look at <x> <y> <z>`
- `/player <name> look <yaw> <pitch>`

### Turn (relative)

- `/player <name> turn left|right|back`
- `/player <name> turn <yawDelta> <pitchDelta>` (via rotation argument)

---

## Elytra gliding (precise controls)

Carpet PvP can drive fake-player elytra flight with:
- rate-limited yaw/pitch steering
- a fixed velocity “throttle” (speed)
- optional freeze/hover

This is gated behind a rule (off by default):

- `/carpet fakePlayerElytraGlide true`

### Start / stop

- `/player <name> glide start`
- `/player <name> glide stop`

Notes:
- Running `glide start` while the bot is on the ground will *arm* gliding but will not spam-jump.
- `glide goto ...` will perform a takeoff jump and deploy elytra automatically.

### Freeze / hover

Freeze forces the bot to hold position (zero velocity) and disables gravity while gliding.

- `/player <name> glide freeze` (toggle)
- `/player <name> glide freeze true`
- `/player <name> glide freeze false`

Freeze automatically when reaching a goto target:

- `/player <name> glide freezeAtTarget true`

### Speed (blocks/tick)

`speed` is a direct velocity magnitude in blocks per tick ($20$ ticks per second).

- `/player <name> glide speed <blocksPerTick>`

Example:
- `/player Bot glide speed 0.8`

### Steering precision (deg/tick)

Yaw/pitch changes are rate-limited per tick (smaller = more precise / less twitchy).

- `/player <name> glide rates <yawDegPerTick> <pitchDegPerTick>`

Example:
- `/player Bot glide rates 2.0 1.0`

### Modes

#### Heading hold

Steer to and hold a specific yaw/pitch.

- `/player <name> glide heading <yaw> <pitch>`

Example:
- `/player Bot glide heading 90 -10`

#### Goto position

Fly toward a target position. Optional `arrivalRadius` (default is 1 block).

- `/player <name> glide goto <x> <y> <z> [arrivalRadius]`

Examples:
- `/player Bot glide goto 100 120 100`
- `/player Bot glide goto 100 120 100 2.5`

On arrival, you can choose what happens next:

- `/player <name> glide arrival stop` (default)
- `/player <name> glide arrival freeze`
- `/player <name> glide arrival descend` (keeps elytra deployed and pitches down for a controlled descent; use a lower speed for a gentler glide)
- `/player <name> glide arrival land` (default behavior for goto: reach target XZ then dive down and stop on ground)
- `/player <name> glide arrival circle` (keep orbiting/holding the target instead of finishing)

#### Manual thrust input

Manual thrust inputs are in the range `-1..1`:
- `forward`: forward/back
- `strafe`: left/right
- `up`: up/down

- `/player <name> glide input <forward> <strafe> <up>`

Examples:
- `/player Bot glide input 1 0 0`
- `/player Bot glide input 0.8 0.2 0`
- `/player Bot glide input 0 0 1`

By default, forward thrust uses the bot’s pitch. You can disable that and make “forward” stay horizontal:

- `/player <name> glide usePitch true|false`

Example:
- `/player Bot glide usePitch false`

### Status

- `/player <name> glide status`

---

## Attacking (normal + crit)

### Normal attacks

- `/player <name> attack [once|continuous|interval <ticks>]`

Behavior:
- Attacks whatever entity the bot is currently targeting (crosshair ray-trace).
- If the bot is targeting a block, it will start breaking it (creative breaks quickly; survival follows normal breaking).

### Critical attacks

- `/player <name> attack crit [once|continuous|interval <ticks>]`

Important details (how crit mode works):
- In crit mode, the bot will:
  1) jump if it is on the ground
  2) only perform the attack while **falling** (not rising)
- If the `spamClickCombat` rule is **off** (modern combat), it will also wait for a “charged” hit (attack cooldown) before swinging.

#### Why “bot cannot crit” happens (common causes)

1) **Using `crit once` previously could jump but never hit**
   - This is now fixed: crit actions retry until they successfully land the hit.

2) **The bot is never actually falling when the attack attempt happens**
   - Use `continuous`, or use `interval` but expect the bot to retry every tick until it hits during the falling window.

3) **Attack cooldown is not ready (modern combat)**
   - If `spamClickCombat` is off, the bot won’t do weak spam hits.
   - Use a larger interval (often ~10 ticks+) or enable the spam-click combat rule if that’s your server’s intent.

4) **Targeting/aim issues**
   - The bot only attacks what it is looking at within reach.
   - Make sure you set look direction: `/player Bot look at ...`

#### Practical crit recipes

- Consistent crit attempts:
  - `/player Bot attack crit continuous`

- “Try to crit roughly every half-second” (with cooldown-friendly pacing):
  - `/player Bot attack crit interval 10`

---

## Using items (right click)

- `/player <name> use [once|continuous|interval <ticks>]`

Behavior:
- The bot will attempt to use/interact with the targeted block/entity.
- If nothing is targetable, it will attempt a normal item use.
- Both hands are considered.

Common uses:
- place blocks
- open doors
- eat / drink
- use fishing rod, bow/crossbow, etc. (note: charge timing matters)

---

## Inventory / gear

### Hotbar slot selection

- `/player <name> hotbar <slot>` where `<slot>` is 1..9

### Swap hands

- `/player <name> swapHands [once|continuous|interval <ticks>]`

### Drop items

Drop single item:
- `/player <name> drop [once|continuous|interval <ticks>]`

Drop stack:
- `/player <name> dropStack [once|continuous|interval <ticks>]`

Drop by slot:
- `/player <name> drop all|mainhand|offhand|<slot>`
- `/player <name> dropStack all|mainhand|offhand|<slot>`

Slot notes:
- Slots are `0..40` (includes hotbar + main inventory + armor/offhand indices).

### Equip / unequip

Equip a full armor set:
- `/player <name> equip <armor_type>`

Equip a specific slot:
- `/player <name> equip <slot> <item>`

Valid slot names:
- `head|helmet`, `chest|chestplate`, `legs|leggings`, `feet|boots`, `mainhand|weapon`, `offhand|shield`

Remove equipped item:
- `/player <name> unequip <slot>`

Show current equipment:
- `/player <name> equipment`

---

## Mounting

- `/player <name> mount` (mount nearby rideables)
- `/player <name> mount anything` (mount anything nearby)
- `/player <name> dismount`

---

## Scarpet scripting: turning `/player` into a bot

You can script bots by calling vanilla commands from Scarpet using `run('...')`.

### Key Scarpet note: `run()` must not be used directly in `/script run`

When you call `run('...')` from `/script run`, it may return `null` because the command gets deferred.
Use `run()` from:
- a scheduled callback (`schedule(...)`)
- a tick event (`__on_tick()`)
- or any app event handler

See Scarpet `run(expr)` docs for details.

### Minimal bot app: spawn + fight

Create `world/scripts/pvp_bot.sc`:

```scarpet
__config() -> {
  'scope' -> 'global'
};

spawn_bot(name) -> run(str('player %s spawn', name));

start_crit(name) -> run(str('player %s attack crit continuous', name));

stop_all(name) -> run(str('player %s stop', name));

__command() -> {
  'botspawn' -> _(name) -> spawn_bot(name);
  'botcrit'  -> _(name) -> start_crit(name);
  'botstop'  -> _(name) -> stop_all(name);
};
```

Then:
- `/script load pvp_bot`
- `/botspawn Bot`
- `/botcrit Bot`

### Aim helper: look at a point then attack

```scarpet
bot_look_at(name, x, y, z) -> run(str('player %s look at %d %d %d', name, x, y, z));

bot_attack(name) -> run(str('player %s attack continuous', name));
```

### Simple “follow me” pattern

A common pattern is:
- every tick, compute a direction vector to the player
- turn/aim towards them
- move forward until close enough

Carpet PvP’s `/player` movement controls are intentionally simple (forward/strafe), so scripts typically implement a lightweight steering loop.

---

## Troubleshooting

### Bot doesn’t attack

Checklist:
- Is it spawned? (`/player <name> spawn`)
- Is it looking at the target? (`/player <name> look at ...`)
- Is it close enough (reach is limited)?
- Is something blocking line-of-sight?

### Bot attacks but never crits

Checklist:
- Use `attack crit continuous` to verify basics.
- Make sure the bot is actually falling during the swing (crits require falling).
- If `spamClickCombat` is off, make sure you’re not trying to swing too fast (cooldown gating).

### `run('player ...')` returns null

- Don’t call `run()` directly from `/script run`.
- Use `schedule(0, _() -> run('...'))` or call it from an event handler.

---

## Reference: full `/player` surface (high-level)

Actions:
- `use`, `jump`, `attack`, `attack crit`, `drop`, `dropStack`, `swapHands`
- `hotbar <1..9>`
- `sneak/unsneak`, `sprint/unsprint`
- `look ...`, `turn ...`, `move ...`
- `equip ...`, `unequip ...`, `equipment`
- `mount [anything]`, `dismount`
- `stop`, `kill`, `disconnect`, `shadow`, `spawn ...`
