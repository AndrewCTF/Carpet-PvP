# CarpetLogic

CarpetLogic is the bot programming layer. You build a program as a graph of nodes in a web editor
in your browser; the editor compiles it to a tree of actions; the server walks that tree on a fake
player, one step per tick.

Two things are independent of each other:

- **The programs.** They live in the world folder, you run them with `/carpetlogic programs run`,
  and they work whether or not the web server is running.
- **The web editor.** An HTTP server that serves a page, hands out the schema and takes programs
  back. Purely optional.

## The command

```
/carpetlogic                       same as /carpetlogic status
/carpetlogic status                web server URL, bot count, running programs, saved programs
/carpetlogic open                  a link to the web editor, with a token
/carpetlogic programs              list saved programs and their action counts
/carpetlogic programs run <program> <bot>
/carpetlogic programs stop <bot>
/carpetlogic bots                  list active bots with health and position
```

The whole command is behind the `commandCarpetLogic` rule, which is `"ops"` by default.

`/carpetlogic open` only hands a link to the entity it should:

- a real player gets the link in their own chat, never in the sender's console output. An
  `/execute as <player> run carpetlogic open` therefore does not leak the token to whoever typed it.
- the server console gets the link when it is op level 4.
- anything else is refused: "Only a player or the server console can open the web editor".

If the web server is not running the command says so, and the reason is in the server log. That
happens when the port is taken, or when the Java runtime has no `jdk.httpserver` module.

## Security

### The bind address

`carpetLogicBindAddress` is `127.0.0.1` by default, so the editor is reachable only from the
machine the server runs on. Setting it to `0.0.0.0` listens on every interface. Those two are what the
rule suggests; it will accept any address the machine can resolve.

The bind address and port are read when the server starts, so changing them needs a restart.

`carpetLogicPort` is 9876 by default. If something else already has that port, the server logs
`CarpetLogic web editor could not listen on 127.0.0.1:9876` and the web editor stays down:
`/carpetlogic open` says "The web editor is not running; the server log says why", and
`/carpetlogic status` reports `Web editor: not running`. Everything else keeps working — program
storage, the bot manager and the executor are separate from the HTTP server, so
`/carpetlogic programs run` is unaffected.

### Tokens

A token is the only credential the web server accepts.

- `/carpetlogic open` mints one: 32 random bytes, base64url, no padding.
- The link carries it in the fragment: `http://127.0.0.1:9876/#token=...`. The page reads it on
  load, keeps it in `sessionStorage` for that tab and takes it out of the address bar, so it is not
  sent to the server on any static request.
- Every `/api/` request carries it as `Authorization: Bearer <token>`. Static files are public:
  there is nothing in them but the editor.
- Sessions live in memory only. A server restart invalidates every token.
- The server stores the SHA-256 of the token, not the token itself.
- A token expires after `carpetLogicSessionHours` (24 by default). Expired tokens are dropped when
  they are next used or when a new one is issued.
- At most 4 live sessions per owner; issuing a fifth drops that owner's oldest.
- There is no cookie, no login and no password.

### What a token can do

Every token is only as good as its owner:

- A token minted for a player is tied to that player's UUID. If the player logs out, every API
  request with that token is refused with 403 "The player this link was issued to is not online",
  and any open event stream is closed.
- The player must still be allowed to use `/carpetlogic`. Losing op mid-session takes the token's
  permissions away: 403 "The player this link was issued to may no longer use /carpetlogic".
- A token minted from the console has no owner. It is not tied to any player, so it keeps working
  when nobody is online.
- Viewer mode (`carpetLogicViewerMode`) turns every non-`GET` request into 403, whatever the token.

What a token cannot do, with any token:

- Nothing outside `/api/`, and no path traversal in the static handler.
- Not more than 16 concurrent event streams.
- Not more than a 2 MiB request body.
- Not more than 5 seconds of server-thread time per request.

### Owner permissions and `EXECUTE_COMMAND`

Actions are driven through the fake player's action pack, so they carry no permissions of their
own. `EXECUTE_COMMAND` is the exception, and it is the one place a program can do something the bot
itself would not be allowed to do.

- A command in a program runs **as the player whose editor started it**, with that player's
  permissions. It never runs as the console, and never as the bot.
- If that player is offline when the command comes up, the program fails with
  "EXECUTE_COMMAND needs the player who started this program to be online".
- A program started from `/carpetlogic programs run` or from the console has no owner, so
  `EXECUTE_COMMAND` always fails there with
  "EXECUTE_COMMAND only runs in programs a player started from the web editor".

There is a second, quieter permission path. When an action's schema entry declares `requires`, the
executor checks that carpet rule before running the action. If the rule is off it tries to turn it
on, as the owner — but only when the owner is online, the settings are not locked from `.conf`, and
the owner passes `carpetCommandPermissionLevel`. If it turns it on, the owner is told
"Your bot program turned on the carpet rule X". If it cannot, the program stops with
"The carpet rule 'X' is off. Turn it on with /carpet X true".

So a player who can run programs from the editor can, indirectly, flip the rules their own programs
need — but only ones they were already allowed to flip.

## Rules

Everything CarpetLogic is configured with is an ordinary carpet rule. Change one with
`/carpet <rule> <value>`.

| Rule | Type | Default | What it does |
|---|---|---|---|
| `commandCarpetLogic` | string permission | `ops` | Whether and for whom `/carpetlogic` works at all. Also the permission a token's owner is re-checked against. |
| `carpetLogicPort` | int 1–65535 | `9876` | Port the web editor listens on. Applied at server start. |
| `carpetLogicBindAddress` | `127.0.0.1`, `0.0.0.0` | `127.0.0.1` | Interface the web editor listens on. Applied at server start. |
| `carpetLogicSessionHours` | int | `24` | How long a link from `/carpetlogic open` stays valid. |
| `carpetLogicUpdateInterval` | int 1–1024 | `5` | Ticks between bot status pushes on the event stream. |
| `carpetLogicViewerMode` | boolean | `false` | The editor can look at bots and programs but not change or run anything. Refuses every non-`GET` API call. |
| `carpetLogicMaxPrograms` | int | `4` | How many programs may run at the same time. |

Three more rules decide whether particular actions work:

| Rule | Default | Affects |
|---|---|---|
| `fakePlayerNavigation` | `true` | `NAV_GOTO`, `FOLLOW_PLAYER`, `CHASE_PLAYER`, `PATROL`, `FLEE_FROM`, `WANDER` |
| `fakePlayerElytraGlide` | `false` | `GLIDE_START`, `GLIDE_GOTO`, `GLIDE_HEADING`, `GLIDE_SPEED`, `GLIDE_FREEZE`, `GLIDE_LAND` |
| `swordBlockHitting` | `false` | `SWORD_BLOCK` |

## Programs

### How they are stored

One JSON file per program:

```
<world>/carpetlogic/programs/<id>.json
```

| Field | Meaning |
|---|---|
| `id` | 1–64 characters of letters, digits, `_` and `-`. It names the file, so it may not contain anything else. |
| `name` | the display name, up to 64 characters |
| `description` | free text |
| `actions` | the compiled action tree |
| `graphData` | the editor's node graph, stored verbatim so reopening the program shows the same picture |
| `createdAt`, `updatedAt` | epoch milliseconds |
| `isPreset` | never true in a saved file |

The folder is read when the server starts and on `/carpet reload`. A file with no valid id, or one
whose actions do not fit the schema, is skipped with a warning in the log — the other programs
still load. The built-in presets live in the mod jar instead (`/carpetlogic/presets.json`) and are
listed separately; a program may not be saved over a preset's id.

### The compiled action tree

```json
{
  "type": "MOVE",
  "params": { "direction": "forward", "ticks": 20 },
  "children": [],
  "elseChildren": [],
  "condition": null
}
```

`type` names an action from the schema. `params` holds its parameters. The three list fields exist
only for the action types whose schema entry lists the matching slot.

A program is validated before it runs and before it is saved: known types, only declared
parameters with values of the right type, children and conditions only where the type has a slot
for them, no more than 64 levels of nesting, no more than 10 000 actions, and no string parameter
longer than 1024 characters.

### Statuses

| Status | Meaning |
|---|---|
| `RUNNING` | still going |
| `COMPLETED` | reached the end of the tree |
| `ERROR` | something went wrong; the bot was stopped and the reason is in `error` |

A program whose bot disappears stops and says so. Starting a program on a bot that already has one
replaces it. At most `carpetLogicMaxPrograms` run at once.

## Node types

**`src/main/resources/carpetlogic/actions.json` is the source of truth.** The interpreter reads every
parameter through that file, and the web editor is sent the very same file to build its node widgets
and compile graphs against it. If this page and that file ever disagree, the file is right.

It has three sections:

```json
{
  "variables": {
    "referencePrefix": "$",
    "namePattern": "^[A-Za-z_][A-Za-z0-9_]*$",
    "maxVariables": 64
  },
  "actions": { "MOVE": { "kind": "action", "node": "Movement/Move", "params": [ ... ] } }
}
```

Each action has:

| Field | Meaning |
|---|---|
| `kind` | `action` (a step), `control` (holds other actions in the listed slots) or `condition` (only tested by `IF_THEN_ELSE` and `WAIT_UNTIL`) |
| `node` | the editor node this action is compiled from, `Category/NodeName` |
| `slots` | which of `children`, `elseChildren` and `condition` this type may carry |
| `params` | the parameters it takes |
| `requires` | the carpet rule that must be on for the action to work, or absent |

Each parameter has a `name`, a `type` (`int`, `number`, `bool`, `string`), a `default`, and
optionally `min`/`max` or the `options` it may take. A missing parameter falls back to its default,
and a number outside `min`/`max` is clamped.

A `ticks` parameter is how long the step lasts before the next one starts.

The 63 nodes, in full:

### Movement

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Movement/Move` | `MOVE` | action |  | `direction` string = `forward` (`forward`, `backward`, `left`, `right`); `ticks` int = `20`, min `1`, max `6000` |
| `Movement/Strafe` | `STRAFE` | action |  | `direction` string = `left` (`left`, `right`); `ticks` int = `10`, min `1`, max `6000` |
| `Movement/Sprint` | `SPRINT` | action |  | `enabled` bool = `true`; `ticks` int = `0`, min `0`, max `6000` |
| `Movement/Sneak` | `SNEAK` | action |  | `enabled` bool = `true`; `ticks` int = `0`, min `0`, max `6000` |
| `Movement/Jump` | `JUMP` | action |  | `ticks` int = `1`, min `1`, max `200` |
| `Movement/Mount` | `MOUNT` | action |  | `onlyRideables` bool = `true` |
| `Movement/Dismount` | `DISMOUNT` | action |  | none |
| `Movement/StopMovement` | `STOP_MOVEMENT` | action |  | none |

### Combat

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Combat/Attack` | `ATTACK` | action |  | `mode` string = `once` (`once`, `continuous`, `interval`); `interval` int = `10`, min `1`, max `200`; `ticks` int = `1`, min `1`, max `6000` |
| `Combat/CritAttack` | `ATTACK_CRIT` | action |  | `ticks` int = `15`, min `10`, max `100` |
| `Combat/SwordBlock` | `SWORD_BLOCK` | action | `swordBlockHitting` | `ticks` int = `40`, min `1`, max `6000` |
| `Combat/ShieldBlock` | `SHIELD_BLOCK` | action |  | `ticks` int = `40`, min `1`, max `6000` |
| `Combat/UseItem` | `USE` | action |  | `mode` string = `once` (`once`, `continuous`, `interval`); `interval` int = `10`, min `1`, max `200`; `ticks` int = `1`, min `1`, max `6000` |

### Equipment

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Equipment/Hotbar` | `HOTBAR` | action |  | `slot` int = `1`, min `1`, max `9` |
| `Equipment/EquipArmor` | `EQUIP_ARMOR` | action |  | `armorSet` string = `diamond` (`leather`, `chainmail`, `iron`, `golden`, `gold`, `diamond`, `netherite`) |
| `Equipment/EquipSlot` | `EQUIP_SLOT` | action |  | `slot` string = `mainhand` (`mainhand`, `offhand`, `head`, `chest`, `legs`, `feet`); `item` string = `diamond_sword` |
| `Equipment/Unequip` | `UNEQUIP` | action |  | `slot` string = `all` (`all`, `mainhand`, `offhand`, `head`, `chest`, `legs`, `feet`) |
| `Equipment/Drop` | `DROP` | action |  | `ticks` int = `1`, min `1`, max `200` |
| `Equipment/DropStack` | `DROP_STACK` | action |  | `ticks` int = `1`, min `1`, max `200` |
| `Equipment/SwapHands` | `SWAP_HANDS` | action |  | none |

### Looking

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Look/LookDirection` | `LOOK_DIRECTION` | action |  | `direction` string = `north` (`north`, `south`, `east`, `west`, `up`, `down`) |
| `Look/LookAt` | `LOOK_AT` | action |  | `x` number = `0`; `y` number = `64`; `z` number = `0` |
| `Look/LookAtPlayer` | `LOOK_AT_PLAYER` | action |  | `player` string = `` |
| `Look/LookYawPitch` | `LOOK_YAW_PITCH` | action |  | `yaw` number = `0`, min `-180`, max `180`; `pitch` number = `0`, min `-90`, max `90` |
| `Look/Turn` | `TURN` | action |  | `yaw` number = `90`, min `-360`, max `360`; `pitch` number = `0`, min `-180`, max `180` |

### End crystals

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Crystal/PlaceBlock` | `PLACE_BLOCK` | action |  | `ticks` int = `1`, min `1`, max `100` |
| `Crystal/PlaceCrystal` | `PLACE_CRYSTAL` | action |  | `ticks` int = `1`, min `1`, max `100` |
| `Crystal/DetonateCrystal` | `DETONATE_CRYSTAL` | action |  | `ticks` int = `1`, min `1`, max `100` |

### Navigation

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Navigation/NavGoto` | `NAV_GOTO` | action | `fakePlayerNavigation` | `x` number = `0`; `y` number = `64`; `z` number = `0`; `mode` string = `auto` (`auto`, `land`, `water`, `air`); `radius` number = `1`, min `0`, max `64` |
| `Navigation/NavStop` | `NAV_STOP` | action |  | none |
| `Navigation/FollowPlayer` | `FOLLOW_PLAYER` | action | `fakePlayerNavigation` | `player` string = ``; `distance` number = `3`, min `1`, max `64`; `ticks` int = `200`, min `1`, max `60000` |
| `Navigation/ChasePlayer` | `CHASE_PLAYER` | action | `fakePlayerNavigation` | `player` string = ``; `critical` bool = `false`; `range` number = `3`, min `0.5`, max `3`; `interval` int = `0`, min `0`, max `200`; `ticks` int = `200`, min `1`, max `60000` |
| `Navigation/Patrol` | `PATROL` | action | `fakePlayerNavigation` | `x1` number = `0`; `y1` number = `64`; `z1` number = `0`; `x2` number = `10`; `y2` number = `64`; `z2` number = `0`; `loop` bool = `true`; `ticks` int = `400`, min `1`, max `60000` |
| `Navigation/FleeFrom` | `FLEE_FROM` | action | `fakePlayerNavigation` | `player` string = ``; `distance` number = `16`, min `5`, max `128`; `ticks` int = `100`, min `1`, max `60000` |
| `Navigation/Wander` | `WANDER` | action | `fakePlayerNavigation` | `radius` number = `16`, min `5`, max `128`; `ticks` int = `200`, min `1`, max `60000` |

### Elytra gliding

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Elytra/GlideStart` | `GLIDE_START` | action | `fakePlayerElytraGlide` | none |
| `Elytra/GlideStop` | `GLIDE_STOP` | action |  | none |
| `Elytra/GlideGoto` | `GLIDE_GOTO` | action | `fakePlayerElytraGlide` | `x` number = `0`; `y` number = `100`; `z` number = `0`; `radius` number = `5`, min `0`, max `64` |
| `Elytra/GlideHeading` | `GLIDE_HEADING` | action | `fakePlayerElytraGlide` | `yaw` number = `0`, min `-180`, max `180`; `pitch` number = `-5`, min `-90`, max `90` |
| `Elytra/GlideSpeed` | `GLIDE_SPEED` | action | `fakePlayerElytraGlide` | `speed` number = `1.6`, min `0.1`, max `5` |
| `Elytra/GlideFreeze` | `GLIDE_FREEZE` | action | `fakePlayerElytraGlide` | `enabled` bool = `true` |
| `Elytra/GlideLand` | `GLIDE_LAND` | action | `fakePlayerElytraGlide` | none |

### Control flow and timing

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Control/Delay` | `DELAY` | action |  | `ticks` int = `20`, min `1`, max `6000` |
| `Control/WaitUntil` | `WAIT_UNTIL` | control (`condition`) |  | `timeout` int = `100`, min `1`, max `60000` |
| `Control/ExecuteCommand` | `EXECUTE_COMMAND` | action |  | `command` string = `say Hello` |
| `Control/Repeat` | `LOOP` | control (`children`) |  | `count` int = `3`, min `0`, max `10000` |
| `Control/Forever` | `FOREVER` | control (`children`) |  | none |
| `Control/Sequence` | `SEQUENCE` | control (`children`) |  | none |
| `Control/If-Else` | `IF_THEN_ELSE` | control (`condition`, `children`, `elseChildren`) |  | none |

### Variables

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Variables/Set` | `SET_VARIABLE` | action |  | `name` string = `counter`; `value` number = `0` |
| `Variables/Add` | `ADD_VARIABLE` | action |  | `name` string = `counter`; `amount` number = `1` |

### Events

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Events/OnEvent` | `ON_EVENT` | control (`children`) |  | `event` string = `when_hit` (`when_hit`, `when_health_below`, `when_target_lost`, `when_target_in_range`); `target` string = ``; `value` number = `5`, min `0`, max `1024` |

### Conditions

| Node | Type | Kind | Rule it needs | Parameters |
|---|---|---|---|---|
| `Conditions/Health` | `CONDITION_HEALTH` | condition |  | `operator` string = `<` (`<`, `<=`, `>`, `>=`, `==`, `!=`); `value` number = `10`, min `0`, max `1024` |
| `Conditions/Distance` | `CONDITION_DISTANCE` | condition |  | `target` string = ``; `operator` string = `<` (`<`, `<=`, `>`, `>=`, `==`, `!=`); `value` number = `5`, min `0`, max `1024` |
| `Conditions/Food` | `CONDITION_FOOD` | condition |  | `operator` string = `<` (`<`, `<=`, `>`, `>=`, `==`, `!=`); `value` number = `10`, min `0`, max `20` |
| `Conditions/Armor` | `CONDITION_ARMOR` | condition |  | `operator` string = `<` (`<`, `<=`, `>`, `>=`, `==`, `!=`); `value` number = `10`, min `0`, max `30` |
| `Conditions/Variable` | `CONDITION_VARIABLE` | condition |  | `name` string = `counter`; `operator` string = `==` (`<`, `<=`, `>`, `>=`, `==`, `!=`); `value` number = `0` |
| `Conditions/Random` | `CONDITION_RANDOM` | condition |  | `chance` number = `50`, min `0`, max `100` |
| `Conditions/HasItem` | `CONDITION_HAS_ITEM` | condition |  | `item` string = `end_crystal` |
| `Conditions/IsFlying` | `CONDITION_IS_FLYING` | condition |  | none |
| `Conditions/IsSneaking` | `CONDITION_IS_SNEAKING` | condition |  | none |
| `Conditions/IsSprinting` | `CONDITION_IS_SPRINTING` | condition |  | none |
| `Conditions/IsInWater` | `CONDITION_IS_IN_WATER` | condition |  | none |

### Notes on the node list

- `Combat/SwordBlock` and `Combat/ShieldBlock` do the same thing: both hold the use button down.
  Which one to pick is about what you meant, not about behaviour.
- `Crystal/PlaceBlock` and `Crystal/PlaceCrystal` both use the item once. `Crystal/DetonateCrystal`
  is a single attack. They exist as separate nodes so a crystal program reads as one.
- `Equipment/Hotbar` takes 1–9, matching the rest of the mod's commands, not 0–8.
- `Look/LookAtPlayer`, `Navigation/FollowPlayer`, `Navigation/ChasePlayer`, `Navigation/FleeFrom`,
  `Conditions/Distance` and `Events/OnEvent` take a `player` name. Leave it empty and the nearest
  other player that is **not** a fake player, in the same dimension, is used.
- `Equipment/EquipSlot`'s `item` is free text, an item id with or without the `minecraft:`
  namespace.
- Number parameters also accept a variable reference, so `ticks` can be `"$wait"`. See below.

## Variables

`SET_VARIABLE` and `ADD_VARIABLE` store a number under a name.

| Property | Value |
|---|---|
| Reference syntax | the prefix `$` then a name, e.g. `$rounds` |
| Name pattern | `^[A-Za-z_][A-Za-z0-9_]*$` |
| Variables per program | 64 |
| A variable never set | reads as `0` |

Any `int` or `number` parameter can hold `"$name"` instead of a number, and is then read from the
program's variables and clamped to its own `min`/`max`.

`CONDITION_VARIABLE` compares one. A name that does not match the pattern stops the program with
"'X' is not a variable name: it must match ^[A-Za-z_][A-Za-z0-9_]*$", and the 65th variable stops it
with "A program may hold at most 64 variables".

Variables live as long as the program does. They are not saved with it.

## Waiting

Most actions with a `ticks` parameter hold the program for that many ticks before the next action
starts. `ticks` of 0 means no wait at all — the action's cleanup, if it has one, runs immediately.

| Wait | How it ends |
|---|---|
| `Control/Delay` | after `ticks` ticks |
| `ticks` on a step | after that many ticks, then the step's own cleanup (stop attacking, stop using, …) |
| `Navigation/NavGoto`, `FollowPlayer`, `ChasePlayer`, `Patrol` | when navigation stops, or when `ticks` runs out |
| `Navigation/Wander` | when `ticks` runs out, picking a new destination whenever it runs out of road |
| `Navigation/FleeFrom` | when the bot is clear of the danger, retested every 10 ticks, or when `ticks` runs out |
| `Elytra/GlideGoto` | when gliding stops or the goal is within `radius` |
| `Control/WaitUntil` | when its condition holds, or after `timeout` ticks |

An event handler that interrupts the main sequence puts back the wait it interrupted, so the main
sequence resumes exactly where it was.

## Events

`Events/OnEvent` registers a reaction. It is reached once, registers itself, and the sequence
carries on. From then on:

- The event is watched every tick. It fires on its **rising edge only**: the condition has to go
  from false to true.
- When it fires, the handler's children take over from the main sequence. Whatever the main
  sequence was waiting for is put back when they finish, and the main sequence continues from there.
- An event that fires again while its own handler is still running is ignored.
- Whether the event already held when the handler was registered is remembered at that point, so a
  handler never fires immediately on the spot.
- A handler with no children is registered but never runs.

| Event | Fires when | Parameters |
|---|---|---|
| `when_hit` | the bot's health went down since the last tick | none |
| `when_health_below` | health is below `value` | `value` |
| `when_target_lost` | the player in `target` is gone, or in another dimension | `target` |
| `when_target_in_range` | the player in `target` is within `value` blocks | `target`, `value` |

`when_hit` needs health tracking, which is only kept while a program has at least one handler.

### A worked example

A bot that fights five rounds, counting them in a variable, blocking and backing off whenever it
drops below half health. The program an editor sends looks like this — you would not normally write
it by hand:

```json
[
  { "type": "EQUIP_ARMOR", "params": { "armorSet": "diamond" } },
  { "type": "SET_VARIABLE", "params": { "name": "rounds", "value": 0 } },

  { "type": "ON_EVENT",
    "params": { "event": "when_health_below", "value": 8 },
    "children": [
      { "type": "STOP_MOVEMENT" },
      { "type": "SWORD_BLOCK", "params": { "ticks": 30 } },
      { "type": "MOVE", "params": { "direction": "backward", "ticks": 20 } }
    ] },

  { "type": "LOOK_AT_PLAYER", "params": { "player": "" } },

  { "type": "LOOP",
    "params": { "count": 5 },
    "children": [
      { "type": "SPRINT", "params": { "enabled": true } },
      { "type": "MOVE", "params": { "direction": "forward", "ticks": 3 } },
      { "type": "ATTACK_CRIT", "params": { "ticks": 10 } },
      { "type": "ADD_VARIABLE", "params": { "name": "rounds", "amount": 1 } },
      { "type": "WAIT_UNTIL",
        "params": { "timeout": 40 },
        "condition": { "type": "CONDITION_HEALTH",
                       "params": { "operator": "<", "value": 20 } } }
    ] },

  { "type": "STOP_MOVEMENT" }
]
```

What happens, tick by tick:

1. `EQUIP_ARMOR` puts diamond armour on, instantly, and moves on — it has no `ticks`.
2. `$rounds` is set to 0. It is never read again in this program; it is here to show the syntax.
3. The `ON_EVENT` node registers a `when_health_below 8` handler and carries on. From this moment
   the executor remembers the bot is at full health, so dropping below 8 fires once, and only once
   per fall.
4. `LOOK_AT_PLAYER` with an empty name turns the bot towards the nearest real player.
5. The `LOOP` runs its children five times. `SPRINT` turns sprinting on without waiting. `MOVE
   forward 3` starts walking for three ticks, then stops on its own. `ATTACK_CRIT ticks=10` jumps,
   waits for the fall, hits, and stops. `ADD_VARIABLE` bumps `$rounds` without waiting.
   `WAIT_UNTIL` pauses the loop until the bot has been hit at all (health under 20) or 40 ticks pass,
   whichever comes first — so a round cannot outrun the opponent.
6. `STOP_MOVEMENT` finishes the program, which then reports `COMPLETED`.

At any point in the loop, if the bot drops below 8 health the handler jumps in: it stops dead,
raises the sword for 30 ticks (`SWORD_BLOCK`, which needs `swordBlockHitting`), then walks backwards
for 20 ticks. The loop picks up where it was afterwards, still mid-round.

The same program as saved by the editor also carries a `graphData` field with the node graph, so
reopening it in the editor shows the picture rather than a list.

### The built-in presets

Seven programs ship in the mod jar. They appear in the editor's preset list and can be run with
`/carpetlogic programs run <name> <bot>`; they cannot be overwritten.

| Id | Name | What it does |
|---|---|---|
| `preset_wtap` | W-Tap | Sprint, three ticks forward, attack, release sprint, two ticks, re-sprint, six ticks — forever |
| `preset_blockhit` | Block Hit | Attack, four ticks of sword block, two ticks — forever. Needs `swordBlockHitting`. |
| `preset_critchain` | Crit Chain | Sprint, a crit attack every ten ticks, twelve ticks apart |
| `preset_circlestrafe` | Circle Strafe | Strafe left, attack, forward, attack, strafe right, attack, forward |
| `preset_shieldbreak` | Shield Break | Switch to slot 2 and hit to break the shield, switch back to slot 1 and hit three times |
| `preset_dummy` | Target Dummy | Equip diamond armour and stand still facing north |
| `preset_combo` | Combo Practice | W-tap, strafe and crit attacks, then a sword block, combined |

## The per-tick budget

A program runs until it reaches an action that takes time, and resumes on a later tick. Without a
limit, a loop with nothing to wait for would never give the tick back, so the executor runs **at
most 1000 steps per program per tick**.

When a program hits that limit it is paused until the next tick and the web editor is sent one
warning, once per program:

```
Program 'W-Tap' on Bot1 ran 1000 steps in one tick and was paused until the next. Put a Delay inside its loop.
```

The fix is always the same: give the loop a `Control/Delay`, or a `ticks` on one of its steps. The
self-test scenario `logic_forever_budget` covers exactly this case.

## The HTTP API

For scripting the editor rather than clicking in it. Everything here runs on the server thread, on
behalf of the session the token belongs to.

```
Authorization: Bearer <token>
Content-Type: application/json
```

A missing, unknown or expired token gets `401` and a `WWW-Authenticate: Bearer` header. A token
whose owner is offline, or no longer allowed to use `/carpetlogic`, gets `403`. A non-`GET` under
`carpetLogicViewerMode` gets `403`. A body over 2 MiB gets `413`. A request the server thread does
not answer within 5 seconds gets `503`.

Errors are always `{"error": "..."}`.

### `GET /api/status`

Who the token belongs to, and what the server is doing.

Response:

```json
{
  "version": "18",
  "user": "Steve",
  "viewerMode": false,
  "activeBots": 2,
  "runningPrograms": 1,
  "savedPrograms": 7,
  "maxPrograms": 4
}
```

### `GET /api/settings`

The rules CarpetLogic and the actions depend on, so the editor can grey out what is off.

Response keys: `commandCarpetLogic`, `carpetLogicPort`, `carpetLogicBindAddress`,
`carpetLogicSessionHours`, `carpetLogicUpdateInterval`, `carpetLogicMaxPrograms`,
`carpetLogicViewerMode`, `fakePlayerNavigation`, `fakePlayerElytraGlide`, `swordBlockHitting`.

### `GET /api/schema`

The whole of `carpetlogic/actions.json`, verbatim. This is what the editor compiles against.

### `GET /api/programs`

An array of the saved programs, as stored. Does not include the presets.

### `GET /api/presets`

An array of the built-in presets.

### `POST /api/programs`

Save a program.

Request body: a program object — `id`, `name`, `description`, `actions`, optionally `graphData`.

```json
{
  "id": "myrounds",
  "name": "My rounds",
  "description": "five crits",
  "actions": [ { "type": "MOVE", "params": { "direction": "forward", "ticks": 20 } } ]
}
```

An empty or missing `id` gets one generated. Saving over an existing id keeps that program's
`createdAt`. A program cannot take a preset's id.

Response: `{"success": true, "id": "myrounds"}`

`400` when the actions do not fit the schema.

### `DELETE /api/programs/<id>`

Delete a program. `<id>` is the part after `/api/programs/`.

Response: `{"success": true}` or `{"success": false}` when there is no such program.

`400` for an id that is not 1–64 characters of letters, digits, `_` and `-`.

### `GET /api/bots`

An object keyed by bot name, each value the bot's state: `name`, `x`, `y`, `z`, `yaw`, `pitch`,
`health`, `maxHealth`, `foodLevel`, `gamemode`, `dimension`, `sprinting`, `sneaking`, and one field
per equipment slot (`mainhand`, `offhand`, `head`, `chest`, `legs`, `feet`, `body`, `saddle`) holding
the item id or `"empty"`.

### `POST /api/bots/spawn`

Spawn a bot. Request body:

```json
{ "name": "Bot1", "x": 100.5, "y": 64, "z": -20.5 }
```

`name` is 1–16 letters, digits or underscores. Without `x`, `y` and `z` the bot spawns where the
token's owner is; a console token spawns it at the world spawn point. The dimension is the owner's
dimension, or the overworld for a console token. The game mode is survival.

Response: `{"success": true, "name": "Bot1", "pending": true}` — the bot joins a moment later,
once its profile has been resolved.

`400` when the name is already online, still logging in, outside the world, or is a name that does
not exist on an online-mode server.

### `POST /api/bots/remove`

Take a bot out. Request body: `{"name": "Bot1"}`. Any program running on it is stopped first.

Response: `{"success": true}`. This disconnects the bot rather than killing it: a bot that dies in the
world comes back on the next tick, which is not what an editor's "remove" should do.

### `POST /api/execute`

Run a program from the action tree in the request, without saving it first.

Request body:

```json
{
  "name": "untitled",
  "botName": "Bot1",
  "actions": [ { "type": "MOVE", "params": { "direction": "forward", "ticks": 20 } } ]
}
```

Programs always run from the actions sent here, never by id, because the commands inside them run
as the token's player.

Response: `{"success": true}`

| Status | When |
|---|---|
| `400` | `actions` is missing or not an array, or the actions do not fit the schema |
| `409` | refused: no such bot, or `carpetLogicMaxPrograms` programs are already running |

### `POST /api/stop`

Stop whatever is running on a bot. Request body: `{"botName": "Bot1"}`.

Response: `{"success": true}` or `{"success": false}` when nothing was running.

### `GET /api/events`

A Server-Sent Events stream. This one has no `/api/` prefix in its path handling — it is matched
before the body is read, so send no body.

The first message is a full snapshot:

```
data: {"type":"botUpdate","bots":{...},"programs":{"Bot1":{"programName":"...","status":"RUNNING","currentAction":"MOVE","error":null,"running":true}}}

```

Then, every `carpetLogicUpdateInterval` ticks, another `botUpdate` with the same shape. Program log
lines arrive as:

```
data: {"type":"log","level":"WARN","message":"...","timestamp":1730000000000}

```

Levels are `INFO`, `WARN` and `ERROR`. An idle connection gets `: keep-alive` every 15 seconds, so
proxies do not drop it. The stream closes when the token expires, when the owner goes offline or
loses permission, when the server shuts down, or when the client disconnects.

`503` with "Too many editors are connected" past 16 open streams.