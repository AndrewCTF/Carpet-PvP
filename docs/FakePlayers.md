# Fake players

A fake player is a real `ServerPlayer` that lives entirely on the server. You spawn one with
`/player <name> spawn`, then drive it with `/player <target> ...`. Nothing is sent to a client and no
real account is involved, which is the point: you can have a dozen of them duelling on a server
where you are the only human.

Notation used here:

| Notation | Meaning |
|---|---|
| `<name>` | a player name |
| `<target>` | an entity selector, so `@s`, `@p`, `@r`, `@a[...]` all work |
| `<pos>` | `x y z` |
| `[...]` | optional |

## Contents

- [Spawning](#spawning)
- [Targets and permissions](#targets-and-permissions)
- [Action modes](#action-modes)
- [Movement](#movement)
- [Looking and turning](#looking-and-turning)
- [Combat and animation](#combat-and-animation)
- [Interaction, hands and inventory](#interaction-hands-and-inventory)
- [Item cooldowns](#item-cooldowns)
- [Navigation](#navigation)
- [Elytra gliding](#elytra-gliding)
- [Combat AI (`ai`)](#combat-ai-ai)
- [Factions](#factions)
- [What happens when the server stops](#what-happens-when-the-server-stops)

## Spawning

```
/player <name> spawn
/player <name> spawn in <gamemode>
/player <name> spawn at <pos>
/player <name> spawn at <pos> facing <yaw> <pitch>
/player <name> spawn at <pos> facing <yaw> <pitch> in <dimension>
/player <name> spawn at <pos> facing <yaw> <pitch> in <dimension> in <gamemode>
```

The pieces chain in that fixed order, so `/player Bot spawn at 0 64 0 in minecraft:overworld` is
**not** a valid command: `facing` sits between `at` and `in <dimension>`. `spawn in <gamemode>` is a
separate branch and needs op level 2.

`/spawnplayer` is an alias that forwards to the same code:

```
/spawnplayer <name> [at <pos>] [facing <yaw> <pitch>] [in <dimension>] [in <gamemode>]
```

Defaults, all taken from whoever ran the command:

| Argument | Default when omitted |
|---|---|
| position | the command source's position |
| facing | the command source's yaw and pitch |
| dimension | the command source's dimension |
| game mode | `survival` |

Spectator mode forces flying on, and survival-like modes force it off, so a spawned player does not
drift.

### Offline and online mode

| Server | What happens |
|---|---|
| `online-mode=false` | The profile is built locally from the name you typed, so the name is kept exactly as typed, case included. Nothing is asked of Mojang. |
| `online-mode=true` | The profile is looked up through Mojang's API on a background thread. The bot joins a tick or two later, once the lookup comes back. |

In online mode, a name Mojang does not know falls back to a local offline profile — but only when
`allowSpawningOfflinePlayers` is on. With the rule off you get:

```
Player Steve doesn't exist and cannot spawn in online mode. Turn the server offline or the
allowSpawningOfflinePlayers on to spawn non-existing players
```

The same rule gates offline-mode spawning, where it is checked before anything else happens.

Because the player list matches names ignoring case, two bots differing only in case would be the
same player as far as `/player Bot` is concerned. The self-test scenario `spawn_exact_name` covers
this: the exact name survives the spawn.

### Refusals

A spawn is refused, with a message, when:

- the name is longer than the server's limit,
- the position is outside the world,
- the player is already online,
- the player is already logging on,
- the resolved profile is banned,
- the server is whitelisted, the player is on it, and the sender is not op level 2
  ("Whitelisted players can only be spawned by operators"),
- the name cannot be resolved and `allowSpawningOfflinePlayers` is off.

Bans and the whitelist are checked after the profile resolves, so on an online-mode server the
message arrives a moment after the command.

### Shadows

```
/player <realPlayer> shadow
```

Replaces a real player with a fake player that has the same profile, position, health, game mode and
equipment, and copies their action pack. The real connection is disconnected with the vanilla
duplicate-login message.

Fake players cannot be shadowed, and neither can the owner of a single-player world.

### Removing

```
/player <name> kill          fake players only
/player <name> disconnect    fake players only
```

Both are refused for real players ("Only fake players can be moved or killed"). Both take the fake
player off the server: `kill` reports "Killed" as the disconnect reason and `disconnect` an empty
one, and neither of them leaves the bot in the world. A bot that *dies* in the world — lava, the
void, another player — is a different thing and does come back, on the next tick.

## Targets and permissions

Every `/player` subcommand except `spawn` takes `<target>`, an entity selector:

```
/player Bot attack continuous
/player @s attack continuous
/player @a[tag=bot] move forward
/execute as @a[tag=bot] run player @s look at 0 64 0
```

A selector that matches several players applies the action to all of them. `s` is accepted as
self-target when the source is a player.

Who may do what:

- `commandPlayer` gates the whole command. It is `"true"` by default.
- A non-op player may control fake players and themselves, but not another real player:
  "Non OP players can't control other real players".
- Navigation additionally needs `fakePlayerNavigation` (on by default), and only works on fake players.
- Gliding additionally needs `fakePlayerElytraGlide`, and only works on fake players.
- `ai` and `faction join`/`leave` only do anything to fake players; naming a real player is silently
  skipped. `faction info` answers "No fake player selected" instead.

`/player <name> stop` clears everything the player had going: every action, the movement inputs, the
crit state, navigation and gliding.

## Action modes

`use`, `jump`, `attack`, `drop`, `dropStack`, `swapHands` and `swing` all take a mode:

```
/player <name> use                    same as once
/player <name> use once
/player <name> use continuous
/player <name> use interval <ticks>
```

| Mode | Behaviour |
|---|---|
| `once` (default) | acts one tick from now, then stops |
| `continuous` | acts every tick until stopped |
| `interval <ticks>` | acts every `<ticks>` ticks, starting after `<ticks>` |

`attack crit` has two extra modes: the bare form and `once` wait until the swing actually hits
something, and `interval <ticks>` waits for a hit before counting the interval. That is what stops a
crit bot from resetting its own attempt by swinging at air.

## Movement

```
/player <name> move                   stop moving
/player <name> move forward
/player <name> move backward
/player <name> move left
/player <name> move right
```

Each direction also takes modifiers:

```
/player <name> move forward for <ticks>
/player <name> move forward sneaking
/player <name> move forward sprinting
/player <name> move forward for <ticks> sneaking
/player <name> move forward for <ticks> sprinting
/player <name> move forward sneaking for <ticks>
/player <name> move forward sprinting for <ticks>
```

`for <ticks>` stops the movement by itself when the time is up, which is how you get "walk three
blocks and stop". The bare `/player <name> move` also clears sneaking and sprinting.

Sneak and sprint also work on their own:

```
/player <name> sneak
/player <name> sneak for <ticks>
/player <name> unsneak
/player <name> sprint
/player <name> sprint for <ticks>
/player <name> unsprint
```

```
/player <name> jump [once|continuous|interval <ticks>]
```

`jump once` only jumps when the player is on the ground. `jump continuous` holds the jump key.

## Looking and turning

```
/player <name> look north|south|east|west|up|down
/player <name> look at <pos>
/player <name> look <yaw> <pitch>
```

```
/player <name> turn left|right|back
/player <name> turn <yaw> <pitch>
```

`turn` is relative: `turn right` adds 90 to the current yaw. `look <yaw> <pitch>` is absolute and
takes the same coordinates as vanilla's rotation argument, so `~` and `^` work.

## Combat and animation

```
/player <name> attack [once|continuous|interval <ticks>]
/player <name> attack crit [once|continuous|interval <ticks>]
```

Attack does real hit detection: it ray-traces and either hits an entity or starts breaking a block,
in survival, or breaks it outright in creative.

### What crit mode does

```
/player <name> attack crit continuous
```

1. On the ground, the bot records the entity in front of it, turns sprinting off (vanilla will not
   register a crit while sprinting), and jumps.
2. In the air it keeps looking at the recorded target, so the swing connects even if the target
   moved.
3. It waits until it is falling (`delta Y < 0`) **and** `fallDistance > 0`, then hits.
4. After a hit it waits to land, then waits at least 3 ticks — or the action's interval, if longer —
   before jumping again.
5. Swings that hit nothing are suppressed while airborne, so a crit bot does not spam at air.

With `spamClickCombat` off — the default — a swing is skipped unless the attack strength is at
least 0.9, which is the modern combat rule. Turn that rule on for 1.8-style spam clicking.

### Swing and animate

```
/player <name> swing [once|continuous|interval <ticks>]
/player <name> animate attack
/player <name> animate use
/player <name> animate continuous
/player <name> animate interval <ticks>
```

`swing` is the arm animation only. `animate` is the same thing under two names: `animate attack`
swings the main hand once and `animate use` swings the off hand once, `animate continuous` repeats
the main-hand swing every tick and `animate interval <ticks>` repeats it on an interval. The hand
each one uses is what a client watching sees, so the two are told apart in game.

## Interaction, hands and inventory

```
/player <name> use [once|continuous|interval <ticks>]
/player <name> swapHands [once|continuous|interval <ticks>]
/player <name> hotbar <1-9>
```

```
/player <name> drop all|mainhand|offhand|<slot 0-40>
/player <name> dropStack all|mainhand|offhand|<slot 0-40>
/player <name> drop [once|continuous|interval <ticks>]
/player <name> dropStack [once|continuous|interval <ticks>]
```

The bare `drop` drops one item from the slot and `dropStack` drops the whole stack; `all`, `mainhand`,
`offhand` and `<slot>` pick the slot.

### Equipment

```
/player <name> equipment                                  show what is worn
/player <name> equip <armor_set>                         equip a full set
/player <name> equip <slot> <item>                       equip one item
/player <name> unequip <slot>
```

`<armor_set>` is one of `leather`, `chainmail`, `iron`, `golden` (or `gold`), `diamond`, `netherite`.

`<slot>` is one of `head`/`helmet`, `chest`/`chestplate`, `legs`/`leggings`, `feet`/`boots`,
`mainhand`/`weapon`, `offhand`/`shield`.

`equipment` lists every equipment slot, with remaining durability for damageable items.

### Riding

```
/player <name> mount            nearest boat, minecart or horse
/player <name> mount anything   nearest entity of any kind
/player <name> dismount
```

## Item cooldowns

```
/player <name> itemCd                       clear every item cooldown
/player <name> itemCd <item>                query one item's cooldown
/player <name> itemCd <item> reset          clear one item's cooldown
/player <name> itemCd <item> set            set the default cooldown for one item
/player <name> itemCd <item> set <ticks>    set an exact cooldown
```

The query and the `set <ticks>` forms return a number, so they work with
`execute store result score ... run`.

```
/player Bot itemCd minecraft:shield
/player Bot itemCd minecraft:shield reset
/player Bot itemCd minecraft:ender_pearl set 5
/execute store result score @s cd run player @s itemCd minecraft:shield
```

Reading the values: `set` with no ticks uses 20 ticks, whatever the item. The remaining-ticks
number is an estimate — it is derived from the cooldown percentage, so it is roughly right and no
more than that.

The bare form, with no item, clears **every** cooldown the player is carrying and reports how many
there were, "Cleared 3 item cooldowns for `<name>`". Its return value is that same count, so a player
with nothing on cooldown gets 0 back.

## Navigation

Navigation needs the rule `fakePlayerNavigation`, which is on by default. To switch navigation off for the whole server:

```
/carpet fakePlayerNavigation false
```

Every `nav` subcommand only works on a fake player. `nav status` prints the mode, the target, the
arrival radius, and whatever else the mode has going — the follow target, the blocks being mined,
the current waypoint, or the chase settings.

### Stop

```
/player <name> nav stop
```

Stops navigating and lets go of the movement inputs navigation was driving — forward, strafe,
sprint and jump — so the bot stands still where it is rather than walking on at its last speed.
`nav chase stop` is the same command.

### Goto

```
/player <name> nav goto <pos> [arrivalRadius]
/player <name> nav goto land <pos> [arrivalRadius]
/player <name> nav goto water <pos> [arrivalRadius]
/player <name> nav goto air <pos> [arrivalRadius]
/player <name> nav goto air land <pos> [arrivalRadius]
/player <name> nav goto air drop <pos> [arrivalRadius]
```

| Form | Mode | What it means |
|---|---|---|
| `goto <pos>` | `auto` | the controller picks land, water or air for the context |
| `goto land` | `land` | walking, jumping, parkour, pillar-jumping, breaking through |
| `goto water` | `water` | swimming |
| `goto air` | `air` | elytra glide, landing on the floor under the target |
| `goto air drop` | `air` | elytra glide, arriving at the target's height |

`arrivalRadius` defaults to 1 block.

### Follow

```
/player <name> nav follow
/player <name> nav follow <target>
/player <name> nav follow <target> <radius>
```

`<target>` is an entity selector. With none, the bot follows the nearest other player in the same
dimension. With a selector that matches several players it follows the nearest one and says so. A
selector that matches no players, or that matches only the bot itself, is refused.

`radius` defaults to 1 block and cannot be below 1.

### Chase

```
/player <name> nav chase stop
/player <name> nav chase attack [<distance>] [<interval>] [<target>]
/player <name> nav chase crit [<distance>] [<interval>] [<target>]
/player <name> nav chase jumpreset [<distance>] [<interval>] [<target>]
```

The bot navigates to the target and attacks when in range.

| Argument | Default | Meaning |
|---|---|---|
| `<distance>` | `2.5` | attack range, 0.5 to 3.0 |
| `<interval>` | `0` | ticks between hits; 0 means as fast as it can |
| `<target>` | see below | who to chase |

Without a target the bot chases the only other player online. With more than one other player it
refuses and lists the options, because picking for you would be guesswork.

`crit` and `jumpreset` are the same thing: jump, hit while falling, and jump once per hit cycle
rather than continuously. Both take the same arguments.

`nav chase stop` is `nav stop`, see [Stop](#stop).

```
/player Bot nav chase attack
/player Bot nav chase attack 1.5 10 Steve
/player Bot nav chase crit 2.5 5 Steve
```

### Come

```
/player <name> nav come [arrivalRadius]
```

Walks to the position of whoever ran the command. `arrivalRadius` defaults to 1 block.

### Mine

```
/player <name> nav mine <block> [<count>] [<radius>]
```

Walks to the nearest `<block>` within `<radius>` and mines it, then looks for another.

| Argument | Default | Meaning |
|---|---|---|
| `<block>` | required | a block id such as `minecraft:diamond_ore` |
| `<count>` | `-1` | how many blocks to mine; `-1` means no limit |
| `<radius>` | `32` | how far to look, 1 to 128 |

```
/player Bot nav mine minecraft:diamond_ore 10 64
```

An unknown block id is refused rather than silently ignored.

### Patrol

```
/player <name> nav patrol <pos1> <pos2> [loop|once]
/player <name> nav patrol <pos1> <pos2> <pos3> [loop|once]
/player <name> nav patrol <pos1> <pos2> <pos3> <pos4> [loop|once]
```

Walks the waypoints in order. `loop`, the default, cycles forever; `once` stops at the end. The
arrival radius for a patrol is 1.5 blocks.

### Per-bot options

```
/player <name> nav options reset
/player <name> nav options <name> <true|false>
/player <name> nav options <name> <0-64>
```

An option set here overrides the matching carpet rule for this bot only. `reset` drops every
override, so the rules apply again.

Booleans:

| Option | Default rule | What it does |
|---|---|---|
| `breakBlocks` | `fakePlayerNavBreakBlocks` | break blocks in the way |
| `placeBlocks` | `fakePlayerNavPlaceBlocks` | place blocks to bridge a gap |
| `autoTool` | `fakePlayerNavAutoTool` | switch to the right tool before breaking |
| `autoEat` | `fakePlayerNavAutoEat` | eat when hungry |
| `avoidLava` | `fakePlayerNavAvoidLava` | route around lava |
| `avoidFire` | `fakePlayerNavAvoidFire` | route around fire |
| `avoidCobwebs` | `fakePlayerNavAvoidCobwebs` | route around cobwebs |
| `breakCobwebs` | `fakePlayerNavBreakCobwebs` | break cobwebs in the way |
| `avoidPowderSnow` | `fakePlayerNavAvoidPowderSnow` | route around powder snow |
| `allowParkour` | `fakePlayerNavAllowParkour` | jump gaps |
| `allowPillar` | `fakePlayerNavAllowPillar` | place a block at the feet to climb |
| `allowBreakThrough` | `fakePlayerNavAllowBreakThrough` | mine through an obstacle |
| `allowDescendMine` | `fakePlayerNavAllowDescendMine` | mine downwards |
| `allowSprint` | `fakePlayerNavAllowSprint` | sprint while walking |
| `mobAvoidance` | `fakePlayerNavMobAvoidance` | route around hostile mobs |
| `avoidSoulSand` | `fakePlayerNavAvoidSoulSand` | penalise soul sand |
| `allowOpenDoors` | `fakePlayerNavAllowOpenDoors` | open doors |
| `allowOpenFenceGates` | `fakePlayerNavAllowOpenFenceGates` | open fence gates |
| `allowSwimming` | `fakePlayerNavAllowSwimming` | swim underwater instead of floating |

Numbers:

| Option | Accepted range | Rule default | What it does |
|---|---|---|---|
| `autoEatBelow` | 0–20, clamped | `fakePlayerNavAutoEatBelow` | hunger level at which it eats |
| `mobAvoidanceRadius` | 1–32, clamped | `fakePlayerNavMobAvoidanceRadius` | how far to keep from mobs |
| `maxFallHeight` | 1–64, clamped | `fakePlayerNavMaxFallHeight` | the biggest fall it will take |

A name that is not one of these is refused: "Unknown boolean nav option" or "Unknown integer nav
option".

The command accepts `0` to `64` for any integer option and clamps it to the option's own range, so
`/player Bot nav options maxFallHeight 200` sets 64.

### Movement types the pathfinder uses

`WALK`, `JUMP` (a step up), `FALL` (down, up to `maxFallHeight`), `PARKOUR` (a gap of two to four
blocks, landing up to one block higher or lower), `PILLAR` (place a block and jump),
`BREAK_THROUGH` (mine one or two blocks of obstacle), `DESCEND_MINE` and `SWIM`. Which of these a
path may use depends on the options above.

In water the bot floats on the surface and hops to stay up, unless `allowSwimming` is on, in which
case it navigates in three dimensions.

Ice, packed ice, blue ice and frosted ice are handled specially, because skating past your own
waypoint is worse than walking slowly:

- the bot stops sprinting on it,
- within three blocks of the waypoint it drops to 40% forward speed,
- the pathfinder costs ice 30% more, so it prefers a grippier route when one exists.

See [Rules.md](Rules.md) for the rule defaults.

## Elytra gliding

```
/carpet fakePlayerElytraGlide true
```

Only fake players can glide.

```
/player <name> glide start
/player <name> glide stop
/player <name> glide status
```

`glide status` reports whether gliding is on, whether it is frozen, the speed and the arrival action.

```
/player <name> glide freeze            toggle
/player <name> glide freeze <true|false>
/player <name> glide freezeAtTarget <true|false>
```

`freeze` stops the bot in mid-air without ending the glide. `freezeAtTarget` freezes it when it
reaches the goal instead of applying the arrival action.

```
/player <name> glide arrival stop|freeze|descend|land|circle
```

What happens on arrival:

| Action | Behaviour |
|---|---|
| `stop` | stop there |
| `freeze` | stop and hold still |
| `descend` | sink down |
| `land` | come down and touch the ground |
| `circle` | circle while descending |

Tuning:

```
/player <name> glide speed <blocksPerTick>
/player <name> glide rates <yawDegPerTick> <pitchDegPerTick>
/player <name> glide usePitch <true|false>
```

`usePitch` decides whether the bot's forward input follows its pitch. On, the default, thrusting
forward also climbs or dives with the nose; off, forward is flat and the pitch only aims the
body. Driving it by hand:

```
/player <name> glide input <forward> <strafe> <up>
/player <name> glide heading <yaw> <pitch>
```

`input` takes three numbers from -1 to 1 — forward, strafe and up — and `heading` takes a yaw from
-360 to 360 and a pitch from -90 to 90. Both switch gliding on.

Going somewhere:

```
/player <name> glide goto <pos> [arrivalRadius]
/player <name> glide goto smart <pos> [arrivalRadius]
```

The plain form steers towards the point. `smart` plans an A* path through the air first, with
compressed waypoints, and sets the arrival action to `land`. It can fail, and says so:

```
No smart path found (range/terrain/chunks). Try a higher goal Y or move closer.
```

Taking off:

```
/player <name> glide launch assist <true|false>
/player <name> glide launch pitch <-45..45>
/player <name> glide launch speed <blocksPerTick>
/player <name> glide launch forwardTicks <0-20>
```

The launch settings shape the run-up before the elytra deploys: whether to run off a ledge, the
pitch, the horizontal boost and how many ticks of it.

## Combat AI (`ai`)

`/player <target> ai ...` configures the built-in combat AI on fake players. The global rules in the
`pvp` category are the defaults a new bot inherits; these are the per-bot overrides.

```
/player <name> ai                     show the whole config
/player <name> ai show
/player <name> ai reset              back to the global rule defaults, and leave any faction
/player <name> ai <setting> <value>
```

| Setting | Values | What it does |
|---|---|---|
| `combat` | `true`/`false` | master toggle for this bot |
| `autotarget` | `true`/`false` | look for a target by itself |
| `targetplayers` | `true`/`false` | may target real players |
| `targetmobs` | `true`/`false` | may target mobs |
| `targetbots` | `true`/`false` | may target other fake players |
| `revenge` | `true`/`false` | retaliate against whoever hit it in the last 100 ticks, even out of `targetrange` |
| `targetrange` | 2–64 | how far to look for a target |
| `retreathealth` | 0–20 | break off below this health; 0 means never |
| `autototem` | `true`/`false` | keep a totem in the offhand |
| `autoshield` | `true`/`false` | put a shield up when low, if no totem |
| `autofood` | `true`/`false` | eat while walking; pushed into navigation as its `autoEat` option |
| `autoweapon` | `true`/`false` | let the sword style pick the best weapon in the hotbar |
| `combatstyle` | `sword`, `crystal`, `anchor`, `ranged`, `mace`, `smp` | which style fights. All six have an implementation; `sword` is the `MELEE` enum |
| `difficulty` | `beginner`, `casual`, `average`, `skilled`, `expert` | applies a whole difficulty preset at once |
| `prefersword` | `true`/`false` | prefer a sword to an axe in melee |
| `shieldbreak` | `true`/`false` | switch to an axe while the target has a shield up |
| `critical` | `true`/`false` | chase with crit hits |
| `strafe` | `true`/`false` | strafe sideways in melee range |
| `bhop` | `true`/`false` | sprint-hop while closing on a target more than three blocks away |
| `wtap` | `true`/`false` | release sprint for a tick after a sprint hit |
| `shieldplay` | `true`/`false` | raise the shield against a swing about to land |
| `meleerange` | 2–6 | how close it tries to stay |
| `attackcooldown` | 0–40 | ticks between hits |
| `skill` | 0–1 | how good the bot's view is: reaction time, aim noise, rotation speed |
| `reactiondelay` | 0–40 | ticks between seeing a target and acting on it |
| `pingticks` | 0–20 | ticks the target's state is behind by |
| `clickspersecond` | 1–20 | how fast the bot can click |
| `plannerrange` | 2–16 | how close the target has to be before the fight planner runs |
| `plannerhorizon` | 2–40 | ticks ahead the fight planner looks |
| `plannerpopulation` | 2–64 | action sequences the fight planner keeps |
| `misschance` | 0–100 | chance of deliberately missing a swing |
| `mistakechance` | 0–100 | chance of aiming a long way off the target |
| `faction` | a name, or empty/`none` | the faction this bot belongs to |

Booleans also accept `1` and `0`. Numbers outside their range are clamped rather than refused.

`ai show` prints one line per selected bot:

```
Bot1: combat=true autoTarget=true targets[players=true,mobs=false,bots=true] revenge=true range=16.0
retreatHP=0 | auto[totem=true,shield=false,food=true,potion=false,armor=false,weapon=false,repair=false]
| style=MELEE difficulty=AVERAGE preferSword=true shieldBreak=false crit=true strafe=true bhop=false
wtap=true shieldPlay=true meleeRange=3.0 atkCd=0 | human[skill=0.6,reaction=0,ping=1,clicks/s=10.0,plannerRange=8.0]
planner[horizon=12,population=10] | realism[miss=0,mistake=0] | faction=none
```

The numbers are the global rule values, so this is what a fresh server with no `/carpet` changes
prints. Any style option the bot has been given is added at the end as `style options {...}`.

Setting `faction` through `ai` also joins or leaves the faction registry, so `/player <name>
faction info` stays right.

### Style options

Besides the settings above, each style reads options of its own, set the same way and per bot. They
are named `<style>.<option>`, they keep the type of their default, and every one of them is also
gated by the bot's difficulty, so turning one on below its floor changes nothing.

```
/player <name> ai mace.windcharge false
/player <name> ai crystal.reach 3.5
```

There are 38 of them. [Bots.md](Bots.md#the-per-style-settings) lists all of them with what each
does and the difficulty each one needs; the nine `mace.*` options are:

| Option | Values | What it does |
|---|---|---|
| `mace.windcharge` | `true`/`false` | the wind charge launch |
| `mace.chain` | `true`/`false` | a second charge on the way down |
| `mace.pearl` | `true`/`false` | the ender pearl entry |
| `mace.elytra` | `true`/`false` | the elytra dive, which needs the wings on the chest |
| `mace.stunslam` | `true`/`false` | the axe on a raised shield, then the smash inside the window |
| `mace.enchants` | `true`/`false` | pick between the Density and the Breach mace by the target's armour |
| `mace.bounce` | `true`/`false` | one more hit off the bounce a Wind Burst mace gives |
| `mace.safeland` | `true`/`false` | the charge that keeps a launch that hit nothing from costing fall damage |
| `mace.swap` | `true`/`false` | change item on the tick of the hit, where a measurement says it is worth anything |

Every one of them is also gated by the difficulty preset: a beginner knows the launch, the safe
landing and the enchantment pick, and each harder preset adds more of them — the chain at `casual`,
the pearl and the stun slam at `average`, the dive and the bounce at `skilled`, and the swap, which
only an expert may try.

The mace style reads a set of options of its own, set the same way as any other bot setting and
per bot:

```
/player <name> ai mace.windcharge false
```

| Option | Values | What it does |
|---|---|---|
| `mace.windcharge` | `true`/`false` | the wind charge launch |
| `mace.chain` | `true`/`false` | a second charge on the way down |
| `mace.pearl` | `true`/`false` | the ender pearl entry |
| `mace.elytra` | `true`/`false` | the elytra dive: the wings go on by using the elytra out of the hotbar and come off by using the chest plate it was exchanged with |
| `mace.rocket` | `true`/`false` | the firework rockets the dive climbs on |
| `mace.stunslam` | `true`/`false` | the axe on a raised shield, then the smash inside the window |
| `mace.fallstunslam` | `true`/`false` | the same pair taken inside one fall, the axe on the way down and the mace a tick later |
| `mace.enchants` | `true`/`false` | pick between the Density and the Breach mace by the target's armour |
| `mace.bounce` | `true`/`false` | one more hit off the bounce a Wind Burst mace gives |
| `mace.safeland` | `true`/`false` | the charge that keeps a launch that hit nothing from costing fall damage |
| `mace.swap` | `true`/`false` | change item on the tick of the hit, where a measurement says it is worth anything |
| `mace.breachswap` | `true`/`false` | every ground hit made with the Breach mace in the hand for the swing, over the base damage and the cadence of the charging item |
| `mace.read` | `true`/`false` | step out from under a target that is falling on the bot with a mace |

Every one of them is also gated by the difficulty preset: a beginner knows the launch and the safe
landing, each harder preset adds one more of them, an average one starts reading what the target is
doing, a skilled one dives and changes item on the tick of the hit, and only an expert takes the stun
slam inside a single fall.

### The attribute swap

The game re-reads an item's `ATTACK_DAMAGE` and `ATTACK_SPEED` only once a tick, in `LivingEntity.tick`,
and only clears the swing timer for a changed main-hand item in `Player.tick`. Both happen after the
action pack has run, so a bot that changes hotbar slot and swings in the same tick hits with the new
item's fall bonus, enchantments and Breach on top of the base damage and the charge of the item it was
holding. `carpet.pvp.sim.AttributeSwap` prices that and `MaceSwap` records what the `mace_swap_probe`
and `mace_breach_swap_probe` scenarios measured on the running version.

What the brain does each tick, in order: survival reflexes, then the combat check, then the retreat
check, then target selection, then the chase and the strafing. Turning `combat` off only stops a
chase the brain itself started, so a `nav chase` you set by hand keeps working.

The `smp` style does its drinking, its armour swaps and its mending under its own `smp.splashheal`,
`smp.armor`, `smp.mend`, `smp.totem` and `smp.buff` options, which is why there are no settings of
their own here for them.

`autofood` is the odd one out in how it is applied: the eating itself is navigation's, so the
per-bot value is pushed into navigation as its `autoEat` option every tick. A bot with it off never
eats on its way somewhere, and `fakePlayerNavAutoEat` is the global default behind it.

## Factions

Factions decide which bots and players are friendly, so the AI will not pick a target that shares a
faction or is allied to it. They are saved when the server stops and read back when it starts, in
`<world>/carpet-factions.json`; a missing or unreadable file is an empty registry rather than a
failure. `/bot match teams` and `/bot match join` are built on this — see
[Practice.md](Practice.md#factions).

```
/player <name> faction list
/player <name> faction create <name>
/player <name> faction delete <name>
/player <name> faction join <name>
/player <name> faction leave
/player <name> faction info
/player <name> faction ally <a> <b>
/player <name> faction unally <a> <b>
```

`create`, `delete`, `ally` and `unally` are server-wide, take no player target into account and do
not need `commandPlayer`. `join`, `leave` and `info` act on the selected fake players and do need it.

`join` creates the faction if it does not exist, so `create` is only needed to make an empty one.

## What happens when the server stops

Fake players do not survive a shutdown, and they do not stop it either.

When the server starts closing, the very first thing it does is disconnect every fake player: each
one is shaken off any vehicle or passengers and its connection is closed with the vanilla
"server shutting down" reason. Vanilla's shutdown asks each connection to disconnect and then waits
until no chunk tickets are left; a fake connection ignores that request, so a fake player left in a
level would hold its tickets and the server would never finish stopping.

Because of that, `/player <name> disconnect` is scheduled one tick ahead and checks the player is
still there, so a shutdown that disconnects everybody does not disconnect the same fake player
twice.

Nothing about a fake player is written to disk. When the server comes back there are no fake players,
and any scheduled commands are gone. Inventories saved as kits are on disk; the in-memory
inventory snapshots `/bot kit give` takes are not. A `/auto-setup` session is the one thing that does
save a player's inventory to disk and hand it back — see [AutoSetup.md](AutoSetup.md).

Equipment a fake player is wearing is remembered across a dimension change or a respawn within one
session, and not across a restart.

## Related pages

- [Commands.md](Commands.md) — every command the mod registers
- [Rules.md](Rules.md) — the PvP, bot and fake-player rules and their defaults
- [Bots.md](Bots.md) — the combat AI, its difficulty presets and its settings
- [Menus.md](Menus.md) — the in-game menu, including a kit editor
- [AutoSetup.md](AutoSetup.md) — `/auto-setup`, which saves a player's things to disk
- [Practice.md](Practice.md) — drills, matches, spectating, traces and factions
- [Kits.md](Kits.md) — `/bot kit`, the kit file format and the built-in kits
- [SmpStyle.md](SmpStyle.md) — the SMP combat style, its kit and its options
- [CarpetLogic.md](CarpetLogic.md) — programming bots in the web editor
- [SwordBlocking.md](SwordBlocking.md) — `swordBlockHitting` and 1.8-style block hitting
- [SelfTest.md](SelfTest.md) — the scenarios that cover fake players
- [Paper.md](Paper.md) — the Paper plugin build