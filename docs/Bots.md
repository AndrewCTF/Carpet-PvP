# PvP bots

A bot is a fake player with a combat AI. `/bot spawn` makes one, `/bot option` and
`/player <name> ai ...` set it up, `/bot duel` makes two of them fight each other, `/bot stop` and
`/bot stats` finish and inspect one.

Every `/bot` subcommand is behind the `commandBot` rule, which is `"true"` by default. See
[Rules.md](Rules.md#commands-this-fork-adds) for the rule and every global default a new bot inherits.

## Spawning a bot

```
/bot spawn <name> <mode> [<difficulty>] [at <x> <y> <z>]
```

| Part | What it means |
|---|---|
| `<name>` | the fake player's name. It has to be free; a name that is online or still logging in is refused |
| `<mode>` | the combat style: `sword`, `crystal`, `anchor`, `ranged`, `mace` or `smp` |
| `[<difficulty>]` | `beginner`, `casual`, `average`, `skilled` or `expert`. Left out, the `botDifficulty` rule decides |
| `[at <x> <y> <z>]` | where to put it. Left out, where the command came from |

The bot spawns at your position, in your dimension, in survival, facing where you face, and it
logs in as a fake player like any other. It is given the kit of its style (see [Kits.md](Kits.md)),
combat and auto-targeting are switched on, it will accept other bots as targets, and the difficulty
preset is applied. So this is enough to get a fight:

```
/bot spawn Bot1 sword average
```

`/bot spawn` returns before the fake player's connection has finished loading, because on an
online-mode server the name has to resolve first. The kit and the settings are applied once it has,
which is why the kit can look a moment late.

### What the difficulty presets change

Five presets, easiest first. `/bot option <name> difficulty <preset>` applies one at any time; it
overwrites the settings below and leaves everything else alone.

| | beginner | casual | average | skilled | expert |
|---|---|---|---|---|---|
| `skill` | 0.15 | 0.35 | 0.60 | 0.80 | 1.00 |
| `reactionDelay`, ticks | 4 | 3 | 2 | 1 | 0 |
| `pingTicks` | 1 | 1 | 0 | 0 | 0 |
| `clicksPerSecond` | 6.0 | 8.0 | 10.0 | 12.0 | 14.0 |
| `missChance`, percent | 45 | 28 | 22 | 8 | 2 |
| `plannerHorizon`, ticks | 6 | 8 | 10 | 10 | 12 |
| `plannerPopulation` | 6 | 6 | 8 | 24 | 48 |
| jump crit (`critical`) | no | yes | yes | yes | yes |
| strafing (`strafe`) | no | no | yes | yes | yes |
| W-tap (`wtap`) | no | no | no | yes | yes |
| shield play (`shieldPlay`) | yes | yes | yes | yes | yes |

Four things change with the level, and they are separate from each other:

- **Reaction.** How many ticks pass between the bot seeing its target and acting on it. The
  difficulty also sets a Fitts-law movement time and a reaction time for the view, both derived
  from `skill`, so a beginner's aim wanders off the target and comes back over several ticks while
  an expert's tracks it.
- **Search.** How deep the fight planner looks (`plannerHorizon`) and how many action sequences it
  keeps (`plannerPopulation`). A beginner gets a 6-tick, 6-line search; an expert a 12-tick,
  48-line one. The scoring weights inside the planner also move: a beginner values its own health
  twice as much as the damage it does, an expert the other way round.
- **Miss rate.** The share of swings thrown early, as `missChance`. 45% of a beginner's swings
  miss on purpose; 2% of an expert's.
- **Techniques.** What the bot is allowed to attempt at all. This is a floor, not a switch: each
  style has a table of the lowest difficulty at which it does a technique, and a bot below that
  floor does not do it however its options are set. The tables are in the style sections below.

`skill` also feeds the view controller, so it is the single number behind how steady the aim looks.

### Which techniques each difficulty unlocks

Per style, from its code:

| Style | beginner | casual | average | skilled | expert |
|---|---|---|---|---|---|
| `sword` | crits off, no strafing | crits | crits, strafing | + W-tap | |
| `mace` | wind charge, safe landing, enchant pick | + chain | + pearl, stun slam | + elytra, bounce | + item swap |
| `crystal` / `anchor` | plain crystals only | | + pearls | + obsidian bridge, double tap | + respawn anchors, hand totem |
| `smp` | eat, totem | | + splash heal, armour swap, cobweb, bucket, guard | + buffs, mending, pearl | |
| `ranged` | | + crossbow | + trident, spear | + tnt cart | |

A cell that is empty means the technique was already there.

## The modes

`/bot spawn <name> <mode>` takes six names. They are the `CombatStyle` values with the melee one
called `sword`.

### `sword`

Melee. The bot closes on its target, keeps itself inside `meleerange`, and plans the fight with the
shared planner: each tick it rolls out a set of candidate action sequences `plannerHorizon` ticks
into the future, scores them on damage dealt, damage taken and distance, and plays the best one.
Only the actions its techniques allow are in the roll, so a bot with `critical` off is never
planned to jump and `wtap` decides whether the roll may put a sprint in. It attacks by swinging at
the target's hitbox, which means it must first be looking at the target and inside the server's
attack range, so it closes before it swings. Shield play raises the shield while the target's own
swing is inside the react window; the `sword` kit carries no shield, so it only shows with a kit
that has one, such as `axe`. Outside `plannerRange` it stops planning and just walks, so a
distant approach is not spent on search.

### `crystal`

Bed fighting. The bot searches the blocks around its target for a cell where a crystal would hurt
and it would not, and works through four stages: bridge an empty base with obsidian, place the
crystal on the face, and for an anchor charge it with glowstone, then set it off. It re-judges the
blast against the real blocks just before it detonates, refuses a blast that would also hit itself,
places one obsidian between itself and a blast it has to take, and steps back out of the explosion
if it still can. Between placements it does not stand still: it plays the sword style with one hit
of poke, which is the knockback the crystals are timed around. Everything it does is on the ground;
it never navigates. Techniques unlock from `average` (pearls after a target that runs away) through
`skilled` (obsidian bridge, sword double tap) to `expert` (anchors, a second totem in the hand).

### `anchor`

The same code as `crystal`, with one difference: it is allowed to place and charge respawn
anchors, which `crystal` is not. The anchor style needs `expert`; below that it behaves exactly
like a `crystal` bot. It plays for the same obsidian holes and the same placements, so the arena
it needs is the one `crystal` needs.

### `ranged`

Projectiles and spacing. The bot picks the best weapon it has for the gap it believes it is standing
at, in bands: a sword inside 4 blocks, a charged spear run in to 7.5, a crossbow inside 8, a trident
up to 18, a bow at anything longer, and the sword again whenever a target is inside its reach. Bow
and trident shots are aimed by solving the projectile ballistics against the target's delayed
position, including the lead for a moving one; the result is a yaw and pitch the bot's own view then
has to turn onto at its own speed, so a shot can and does miss. Between shots it keeps
`ranged.keep` blocks of distance and strafes, and it only closes with the sword once the gap is
small. This style never uses navigation.

A bot that can lay a trap lays one: it plans a rail and a tnt minecart in a cell next to its target
whose blast the target cannot walk out of, puts both down from its own hand at its own click rate,
walks out of the blast, and then sets the cart off with a flaming arrow. **That last step is not
reliable yet** — the trap goes off in some fights and not in others, so treat a tnt minecart as
something this bot does rather than something it does well.

### `mace`

Launches. The bot works in three phases: on the ground it either walks in, or launches itself at
the target with a wind charge, a pearl or an elytra; in the air it steers toward where the target
will be and swings when it has fallen far enough for a smash; then it lands. Before a launch it
runs the entry planner and keeps the best option, and it can break a shield with an axe and then
land the smash inside the window a disabled shield leaves. Which of its two maces it holds depends
on the fall it is about to make and the armour in front of it. A missed launch costs fall damage, so
it throws a second wind charge at its own feet to come down safely. Never navigates except to walk
in on the ground phase.

### `smp`

Survival sword fighting. The bot is the sword style with a survival layer on top: it senses what it
needs, picks one survival move at a time, and carries it out with its hands — switching hotbar
slot, turning the view, holding the use key — before it goes back to fighting. What it can do
depends on the difficulty floor: eating and the offhand totem from the start, splash healing, an
armour swap, a cobweb on a target that is running, a water bucket and shield guard from `average`,
and buffs, mending and pearls from `skilled`. It keeps a strength or speed effect topped up rather
than letting it run out, and it re-totems on the delay `smp.retotem` sets. This style is being
worked on; the two self-test scenarios that cover it today are `smp_heals`, `smp_retotem` and
`smp_buffs`.

## Reading and changing a bot

```
/bot option <name>
/bot option <name> <setting> <value>
```

`/bot option <name>` alone prints the bot's whole configuration on one line. With a setting and a
value it changes that setting and prints what it became. The setting names are the same as the
`/player <name> ai` ones below, plus the per-style ones.

### The common settings

Every one of these is on a `pvp`-category rule that sets the default a new fake player inherits, so
`/carpet <rule> <value>` changes what every bot spawned afterwards starts from. The right-hand
column is the range a per-bot value is clamped to.

| Setting | Type | Default | Range | What it does |
|---|---|---|---|---|
| `combat` | bool | `false` | | Master switch. Off means the bot does not pick a target and does not chase. It only stops a chase the AI started, so a `nav chase` you set by hand keeps running |
| `autotarget` | bool | `true` | | Look for a target without being told |
| `targetplayers` | bool | `true` | | Real players are valid targets |
| `targetmobs` | bool | `false` | | Mobs are valid targets |
| `targetbots` | bool | `true` | | Other fake players are valid targets |
| `revenge` | bool | `true` | | Retaliate against whoever hit the bot in the last 100 ticks, even outside `targetrange` |
| `targetrange` | number | `16.0` | 2–64 | How far away a target may be and still be picked. The nearest valid one wins |
| `retreathealth` | int | `0` | 0–20 | Break off at or below this health. `0` means never, since health is never 0 while alive |
| `autototem` | bool | `true` | | Move a totem of undying into the offhand, swapping out of the main inventory |
| `autoshield` | bool | `false` | | Put a shield in the offhand at 8 health or less. Only when `autototem` is off, so the two do not fight over the slot |
| `autofood` | bool | `true` | | Eat while walking. This is passed to navigation as its own `autoEat` option |
| `autoweapon` | bool | `false` | | Let the sword style pick the best weapon in the hotbar instead of holding the sword |
| `combatstyle` | style | `MELEE` | the six styles | Which style fights |
| `difficulty` | preset | `AVERAGE` | the five presets | Applies the whole preset, as in the table above |
| `prefersword` | bool | `true` | | Prefer a sword to an axe in melee |
| `shieldbreak` | bool | `false` | | Switch to an axe while the target has a shield up |
| `critical` | bool | `true` | | Jump and hit for a crit |
| `strafe` | bool | `true` | | Sidestep while inside melee range |
| `bhop` | bool | `false` | | Sprint-hop while closing on a target more than 3 blocks away |
| `wtap` | bool | `true` | | Release sprint for a tick after a sprint hit, the way a client has to |
| `shieldplay` | bool | `true` | | Raise the shield against a swing that is about to land |
| `meleerange` | number | `3.0` | 2–6 | How close the bot tries to stay |
| `attackcooldown` | int | `0` | 0–40 | Ticks between hits. `0` means as fast as the weapon cooldown allows |
| `skill` | number | `0.6` | 0–1 | Feeds the view profile: reaction time, aim noise, rotation speed and pursuit |
| `reactiondelay` | int | `0` | 0–40 | Ticks the target's state is behind by |
| `pingticks` | int | `1` | 0–20 | Ticks of extra lag on the target's state |
| `clickspersecond` | number | `10.0` | 1–20 | How fast the bot can click, and so how often it can attack or place a block |
| `plannerrange` | number | `8.0` | 2–16 | How close the target has to be before the planner is worth running |
| `plannerhorizon` | int | `12` | 2–40 | How many ticks ahead the fight planner looks |
| `plannerpopulation` | int | `10` | 2–64 | How many action sequences it keeps per plan |
| `misschance` | int | `0` | 0–100 | Percent of swings thrown early on purpose |
| `mistakechance` | int | `0` | 0–100 | Percent of ticks the sword style aims a long way off the target |
| `faction` | name | none | | Which faction the bot is in. `none` or an empty value clears it |

The `smp` style drinks, swaps armour and mends under its own `smp.splashheal`, `smp.armor`,
`smp.mend`, `smp.totem` and `smp.buff` options rather than through settings of their own, and the mace
style's shield break works the same way. Two options are on in code but unreachable with the stock
kits: `mace.elytra`, because the
mace kit carries the elytra in the hotbar rather than in the chest slot the style reads, and
`mace.swap`, because the measurement that enables it only runs inside the self-test.

### The per-style settings

These are named `<style>.<option>`. Each is read only by that style, and each is gated by the
technique table above, so turning one on below its floor changes nothing. `/bot option` types a
value from the form of the default: a `true`/`false` default is read as a switch, a numeric default
as a number.

#### `crystal.*` and `anchor.*`

| Option | Default | What it does |
|---|---|---|
| `crystal.crystals` | `true` | Placing end crystals at all. With it off the style never looks for one and falls back to the sword |
| `crystal.obsidian` | `true` | Filling an empty base cell with obsidian. `skilled` and up |
| `crystal.anchors` | `true` | Placing, charging and setting off a respawn anchor. `anchor` style only, `expert` and up |
| `crystal.pearls` | `true` | Chasing a target that pearls away, and pearling out of a hole. `average` and up |
| `crystal.doubletap` | `true` | A sword hit for knockback before the crystals. `skilled` and up |
| `crystal.hand_totem` | `true` | A second totem in the main hand when two blasts are close together. `expert` and up |
| `crystal.retotem` | `true` | Moving a fresh totem into the offhand after one pops |
| `crystal.retotem_delay` | `20` | Ticks it waits after a pop before it does |
| `crystal.reach` | `4.0` | How close the target has to be for a placement to be considered |

#### `mace.*`

| Option | Default | What it does |
|---|---|---|
| `mace.windcharge` | `true` | Launching with a wind charge at its own feet |
| `mace.chain` | `true` | Throwing a second wind charge when the extra height is worth it. `casual` and up |
| `mace.pearl` | `true` | Entering with a pearl. `average` and up |
| `mace.elytra` | `true` | Diving in with an elytra. `skilled` and up |
| `mace.stunslam` | `true` | Axeing a raised shield down and smashing inside the window. `average` and up |
| `mace.enchants` | `true` | Choosing between its two maces by the damage each does to the armour in front of it |
| `mace.bounce` | `true` | Bouncing a smash off a wind burst. `skilled` and up |
| `mace.safeland` | `true` | Throwing a charge at its own feet to come down without fall damage |
| `mace.swap` | `true` | Swapping the item on the tick of the hit to keep a charged attack cooldown. `expert` and up, and only after `mace_attribute_swap_probe` has measured that this Minecraft version rewards it |

#### `smp.*`

| Option | Default | What it does |
|---|---|---|
| `smp.eat` | `true` | Eating a golden apple. Any difficulty |
| `smp.totem` | `true` | The offhand totem. Any difficulty |
| `smp.splashheal` | `true` | Throwing a splash healing at its own feet. `average` and up |
| `smp.armor` | `true` | Swapping a worn piece of armour for a spare. `average` and up |
| `smp.web` | `true` | Dropping a cobweb on a target that is running away. `average` and up |
| `smp.bucket` | `true` | Water, to put out fire or break a fall. `average` and up |
| `smp.guard` | `true` | Keeping the shield up around a slow action. `average` and up |
| `smp.buff` | `true` | Drinking and throwing strength and speed. `skilled` and up |
| `smp.mend` | `true` | Mending with experience bottles. `skilled` and up |
| `smp.pearl` | `true` | Pearling away to heal. `skilled` and up |
| `smp.retotem` | `20` | Ticks between popping a totem and moving a fresh one in |
| `smp.buffwindow` | `240` | Ticks before a buff runs out that the bot tops it up |
| `smp.peelback` | `12` | The distance in blocks a retreat throw aims for |

#### `ranged.*`

| Option | Default | What it does |
|---|---|---|
| `ranged.bow` | `true` | Shooting with a bow |
| `ranged.crossbow` | `true` | Shooting with a crossbow. `casual` and up |
| `ranged.trident` | `true` | Throwing a trident, and Riptide in water. `average` and up |
| `ranged.spear` | `true` | Charging and thrusting a spear. `average` and up |
| `ranged.tntcart` | `true` | Laying a rail and a tnt minecart beside the target and setting it off with a flaming arrow. `skilled` and up. The trap goes off in some fights and not in others |
| `ranged.keep` | `9.0` | The distance in blocks it tries to keep |
| `ranged.draw` | `-1` | Ticks of draw before a bow shot. Anything below 0 lets the aim solver pick |

The `ranged` kit carries all of it, so a bot spawned with the kit has something for every one of the
techniques; see [Kits.md](Kits.md). A bot on a version without a spear has the spear option turn
itself away, because the kit it was given had no spear in it.

## The other route: `/player <name> ai`

```
/player <target> ai
/player <target> ai show
/player <target> ai reset
/player <target> ai <setting> <value>
```

`ai` on its own and `ai show` print the same line `/bot option <name>` does, once per selected fake
player. `ai <setting> <value>` sets the same setting by the same name, with the same clamping, and
then syncs the bot's faction membership. `ai reset` puts the bot back to the current value of every
global rule and drops its faction. `<target>` is an entity selector, so `@a` works and real players
are skipped.

`/bot option` and `/player ai` are two names for one thing. `/bot option` needs the name of a bot
and does not touch the faction registry; `/player ai` takes any selector.

See [FakePlayers.md](FakePlayers.md#combat-ai-ai) for the `/player` side in full.

## Fighting, stopping, and reading what happened

```
/bot duel <a> <b>
/bot stop <name>
/bot stats <name>
```

`/bot duel` puts the two bots in factions of their own and turns both on: `combat`,
`autotarget` and `targetbots` on, `targetrange` at least 32, and each in `bot_<name>` so the two do
not fight themselves. It returns 2.

`/bot stop <name>` turns the bot's `combat` off and stops any navigation it was doing. It does not
take the bot off the server; `/player <name> kill` does that.

`/bot stats <name>` prints two lines: the counters of everything the bot's body has done, and the
last three fights it has been recorded in. The counters are:

```
clicks=<n> hits=<n> misses=<n>[reach=<n> aim=<n> charge=<n>] hitRate=<n>% crits=<n>
sprintHits=<n> throttled=<n> shieldBreaks=<n> blockTicks=<n>
blasts[<crystals> crystals, <anchors> anchors, <blasts> blown (<anchorsBlown> anchors),
<refusedBlasts> refused, <backedOff> backed off, <blocks> blocks] totems=<n> pearls=<n>
dealt=<n> taken=<n> planner=<calls>/<simulatedTicks> ticks starved=<n>
rotation[onGrid=<bool> maxStep=<n>deg offGrid=<n> first=<n>/<n>]
```

`clicks` is every click the bot's click rate allowed, `hits` the ones that landed, and the three
miss counts say which check each of the others failed: out of reach, pointed away from the target,
or not charged past the gate that crits and sprint hits need. `throttled` is clicks the click rate
refused, which is what a style that wanted to place a block or swing and could not shows up as.
`starved` is ticks the bot had no share of the simulation budget to plan in.
`rotation[onGrid=false]` means the bot's view moved by something that is not a whole mouse click,
which should never happen.

A bot that has not fought yet prints `<name> has not fought yet`.

## Why a bot behaves like a player

Four things do most of that work. They are all settings, and all four are in the table above.

**Perception delay.** The bot reads its own state without delay but only ever sees its target as it
was `reactiondelay + pingticks` ticks ago, out of a 40-tick ring buffer of snapshots. Every decision
the style makes is taken against that delayed copy, so a bot reacts to where the target was, not
where it is. `pingticks` models a laggy connection; `reactiondelay` models the human in front of
it.

**The aim model.** The view is not pointed at the target. It is turned there by a controller that
turns in whole mouse clicks on a grid set by a mouse sensitivity of 0.5, at a maximum rotation per
tick, with a reaction time and a Fitts-law movement time taken from `skill`, with endpoint noise
proportional to how far it is turning, and with a pursuit model that estimates the target's
velocity with a lag and corrects only part of the remaining error each tick. That is why a bot's aim
arrives late and wobbles. When it swings, the hit is tested against the ray along the view the bot
*has*, not the one it was aiming for, so a swing that has not finished turning misses.

**The click rate.** `clicksPerSecond` becomes a minimum number of ticks between clicks, counted once
per tick whichever part of the style asks first. A sword swing, a crystal placement, a block break
and a bow release all draw on the same counter, so a bot cannot place a crystal and swing in the
same tick at a click rate of 10.

**The shared simulation budget.** `botSimBudget` is the number of simulated ticks all the bots of
the server together may plan in one server tick. It is divided by the number of fighters, so a bot
that finds itself in a 32-bot match gets a thirty-secondth of the search one fighter gets. When a
bot's share is too small to search in, it counts a starved tick, keeps whatever placement or
movement it already had and does not swing. Lower `botSimBudget` to cap the cost of a busy server
and raise it to let bots think harder. The `bot_budget` scenario checks both directions: it drives
the budget to 64 and asserts that fighters are starved, then restores it and asserts they plan again.

## What the AI does each tick

1. Survival reflexes, whatever `combat` says: the offhand totem or shield, and the auto-eat switch
   pushed into navigation.
2. If `combat` is off, disengage and stop.
3. Build the body if there is not one yet, and pick the style, replacing it when `combatstyle`
   changed.
4. If health is at or below `retreathealth`, disengage.
5. Pick a target: whoever hit the bot in the last 100 ticks first, then the nearest valid entity
   inside `targetrange` that is not a faction ally.
6. Record what the bot can see of itself and of its target, delayed by `reactiondelay + pingticks`.
7. Hand the tick to the style.

## Bots and CarpetLogic

A bot's combat AI and a CarpetLogic program can both drive it. A program that opens a combat node
takes the fight over; stopping the program gives it back. See [CarpetLogic.md](CarpetLogic.md) for
the nodes that do this.

## Related pages

- [AutoSetup.md](AutoSetup.md) — `/auto-setup` builds an arena and a bot for one fight
- [Practice.md](Practice.md) — drills, matches, spectating, traces
- [Menus.md](Menus.md) — the same settings in an in-game menu
- [Kits.md](Kits.md) — the loadouts a bot spawns with
- [FakePlayers.md](FakePlayers.md) — `/player` in full
- [Commands.md](Commands.md) — every command
- [Rules.md](Rules.md) — the `pvp` rules and their defaults
- [SelfTest.md](SelfTest.md) — the scenarios that cover all of this