# Rules this fork added

Carpet rules are server settings. Change one in game with `/carpet <rule> <value>`, list them with
`/carpet`, and set them permanently in the world's `carpet.conf`.

Two rules about using them:

- `/carpet` itself needs `carpetCommandPermissionLevel`, and stops working entirely when `carpet.conf`
  has `locked=true`.
- Changing `carpetCommandPermissionLevel` needs op level 4. Its own description says "can only be set
  via .conf file"; what the code actually enforces is that op level 4, so an op 4 player can change it
  in game like anything else.

This page covers the rules this fork added on top of upstream Carpet for its PvP work: the bot
combat AI, fake-player navigation, gliding and other fake-player behaviour, 1.8-style combat, and
CarpetLogic. Everything else in `CarpetSettings` is upstream's and belongs in upstream's
documentation.

Two things to know about reading the tables:

- **Type** is the Java type of the setting. `bool` is `true`/`false`, `int` and `double` are numbers,
  `perm` is a permission level that also accepts `true`, `false` and `ops`.
- **Default** is the value in a fresh world, before anyone touches `/carpet`.
- Where a rule lists values in its last column, those are the only values it accepts. Anything else
  is refused with "Valid values for this rule are: ...".

The bot rules are the **defaults a fake player inherits when it spawns**. A per-bot override through
`/bot option <name> <rule> <value>` or `/player <name> ai <rule> <value>` wins over them. See
[Bots.md](Bots.md) for the per-bot names and the ranges a per-bot value is clamped to.

## Commands this fork adds

| Rule | Type | Default | Values | What it does |
|---|---|---|---|---|
| `commandBot` | perm | `true` | | `/bot` in all its forms: spawning and driving bots, their settings, their statistics, the menu, drills, matches, spectating, skins, traces, and `/bot kit`. See [Bots.md](Bots.md), [Menus.md](Menus.md), [Practice.md](Practice.md) and [Kits.md](Kits.md). |
| `commandAutoSetup` | perm | `true` | `true`, `false`, `ops` | `/auto-setup`, which sets a player up to fight a bot and takes it all back down again. See [AutoSetup.md](AutoSetup.md). |
| `commandCarpetLogic` | perm | `ops` | | `/carpetlogic`, which runs bot programs and opens the web editor. Also the permission a web editor token's owner is re-checked against. See [CarpetLogic.md](CarpetLogic.md). |

## Fake-player navigation and gliding

| Rule | Type | Default | What it does |
|---|---|---|---|
| `fakePlayerNavigation` | bool | `true` | Master switch for `/player <name> nav ...`, for combat bots closing on a target and for CarpetLogic navigation nodes. With it off, navigation also stops itself mid-run, and no bot can walk to anything. |
| `fakePlayerElytraGlide` | bool | `false` | Master switch for `/player <name> glide ...`, and for the `air` navigation mode. Fake players only. |
| `playerFallDamage` | bool | `true` | Whether real players take fall damage at all. |
| `fakePlayerFallDamage` | bool | `true` | Whether fake players take fall damage. |
| `fakePlayerDropInventoryOnDeath` | bool | `false` | Whether a fake player drops its inventory when it dies in the world. Subject to the `keepInventory` game rule, like any other death. |
| `fakePlayerDataModifiable` | bool | `true` | Whether `/data` may change a fake player's entity data. |

### Navigation options

These are the defaults for every bot. `/player <name> nav options <name> <value>` overrides one of
them for a single bot, and `/player <name> nav options reset` drops the overrides.

| Rule | Type | Default | Values | What it does |
|---|---|---|---|---|
| `fakePlayerNavBreakBlocks` | bool | `false` | | let navigation break blocks in the way |
| `fakePlayerNavPlaceBlocks` | bool | `false` | | let navigation place blocks to bridge a gap |
| `fakePlayerNavAutoTool` | bool | `true` | | switch to the right tool before breaking |
| `fakePlayerNavAutoEat` | bool | `true` | | eat when hungry |
| `fakePlayerNavAutoEatBelow` | int | `10` | `6`, `10`, `14` | hunger level at which it eats |
| `fakePlayerNavAvoidLava` | bool | `true` | | route around lava |
| `fakePlayerNavAvoidFire` | bool | `true` | | route around fire |
| `fakePlayerNavAvoidCobwebs` | bool | `true` | | route around cobwebs |
| `fakePlayerNavBreakCobwebs` | bool | `true` | | break cobwebs in the way |
| `fakePlayerNavAvoidPowderSnow` | bool | `true` | | route around powder snow |
| `fakePlayerNavAllowParkour` | bool | `true` | | jump gaps of up to four blocks |
| `fakePlayerNavAllowPillar` | bool | `false` | | place a block at the feet to climb |
| `fakePlayerNavAllowBreakThrough` | bool | `false` | | mine through an obstacle |
| `fakePlayerNavAllowDescendMine` | bool | `false` | | mine downwards |
| `fakePlayerNavAllowSprint` | bool | `true` | | sprint while walking |
| `fakePlayerNavMobAvoidance` | bool | `false` | | route around hostile mobs |
| `fakePlayerNavMobAvoidanceRadius` | int | `8` | `4`, `8`, `12`, `16` | how far to keep away from mobs |
| `fakePlayerNavMaxFallHeight` | int | `4` | `3`, `4`, `8`, `16` | the biggest fall it will take |
| `fakePlayerNavAvoidSoulSand` | bool | `false` | | penalise soul sand |
| `fakePlayerNavAllowOpenDoors` | bool | `true` | | open doors |
| `fakePlayerNavAllowOpenFenceGates` | bool | `true` | | open fence gates |
| `fakePlayerNavAllowSwimming` | bool | `false` | | swim underwater instead of floating |
| `fakePlayerNavSearchBudget` | int | `1500` | `500`, `1500`, `3000`, `6000` | node expansions one bot may pathfind in a single tick |
| `fakePlayerNavSearchBudgetTotal` | int | `12000` | `4000`, `12000`, `24000`, `50000` | node expansions all the bots together may pathfind in a single tick |

## Bot combat AI

All of these are in the `pvp` category.

### Master and targeting

| Rule | Type | Default | What it does |
|---|---|---|---|
| `botCombat` | bool | `false` | Master switch for the combat AI. Off means bots do not pick targets and do not chase. It only stops a chase the AI started, so a `nav chase` you set by hand keeps running. |
| `botAutoTarget` | bool | `true` | Look for a target without being told. |
| `botTargetPlayers` | bool | `true` | Real players are valid targets. |
| `botTargetMobs` | bool | `false` | Mobs are valid targets. |
| `botTargetBots` | bool | `true` | Other fake players are valid targets. |
| `botRevenge` | bool | `true` | Retaliate against whoever hit the bot in the last 100 ticks, even out of range of the normal scan. |
| `botTargetRange` | double | `16.0` | How far away a target may be and still be picked. Non-negative; a per-bot value is clamped to 2–64. |
| `botRetreatHealth` | int | `0` | Break off the fight at or below this health. `0` means never, since health is never 0 while alive. Per-bot 0–20. |

### Survival reflexes

| Rule | Type | Default | What it does |
|---|---|---|---|
| `botAutoTotem` | bool | `true` | Move a totem of undying into the offhand when there is none, swapping it out of the main inventory. |
| `botAutoShield` | bool | `false` | Put a shield in the offhand at 8 health or less. Only when `botAutoTotem` is off, so the two never fight over the slot. |
| `botAutoFood` | bool | `true` | Automatic eating. The per-bot value is pushed into navigation as its own `autoEat` option, so a bot with it off never eats on its way somewhere; `fakePlayerNavAutoEat` is the global default behind it. |
| `botAutoWeapon` | bool | `false` | Whether the sword style may pick the best weapon in its hotbar instead of holding the sword. |

### What it fights with

| Rule | Type | Default | Values | What it does |
|---|---|---|---|---|
| `botCombatStyle` | string | `MELEE` | `MELEE`, `CRYSTAL`, `ANCHOR`, `RANGED`, `MACE`, `SMP` | Which style every bot spawned after this inherits. |
| `botDifficulty` | string | `AVERAGE` | `BEGINNER`, `CASUAL`, `AVERAGE`, `SKILLED`, `EXPERT` | Which difficulty preset `/bot spawn` and `/auto-setup` start from. See [Bots.md](Bots.md#what-the-difficulty-presets-change). |
| `botPreferSword` | bool | `true` | | Prefer a sword to an axe in melee. |
| `botShieldBreak` | bool | `false` | | Switch to an axe while the target has a shield up. |
| `botCritical` | bool | `true` | | Chase with jump-and-hit crits. |
| `botStrafe` | bool | `true` | | Sidestep while inside melee range. |
| `botBhop` | bool | `false` | | Sprint-hop while closing on a target more than three blocks away. |
| `botWTap` | bool | `true` | | Release sprint for a tick after a sprint hit, which is what a client has to do to get its sprint back. |
| `botShieldPlay` | bool | `true` | | Raise the shield against a swing that is about to land. |
| `botMeleeRange` | double | `3.0` | | How close the bot tries to stay. Per-bot 2–6. |
| `botAttackCooldown` | int | `0` | | Ticks between hits. `0` means as fast as the weapon cooldown allows. Per-bot 0–40. |

### The human model

These are what make a bot move and aim like a person rather than like a turret. See
[Bots.md](Bots.md#why-a-bot-behaves-like-a-player).

| Rule | Type | Default | What it does |
|---|---|---|---|
| `botSkill` | double | `0.6` | Skill from 0 to 1. Feeds the view controller: reaction time, aim noise, rotation speed and how much of the error while pursuing a target it corrects each tick. A probability, so 0–1. |
| `botReactionDelay` | int | `0` | Ticks between the bot seeing a target and acting on it. Per-bot 0–40. |
| `botPingTicks` | int | `1` | Ticks the target's state is behind by, as on a slow connection. Per-bot 0–20. |
| `botClicksPerSecond` | double | `10.0` | How fast the bot can click, and so how often it can swing or place a block. Per-bot 1–20. |
| `botPlannerRange` | double | `8.0` | How close the target has to be before the fight planner is worth running. Per-bot 2–16. |
| `botPlannerHorizon` | int | `12` | How many ticks ahead the fight planner looks. Per-bot 2–40. |
| `botPlannerPopulation` | int | `10` | How many action sequences the fight planner keeps per plan. Per-bot 2–64. |
| `botSimBudget` | int | `20000` | Simulated ticks all the bots of the server together may plan in one server tick, split between the fighters. Lower it to cap the cost of a busy server, raise it to let bots think harder. A bot with too small a share counts a starved tick, keeps what it was doing and does not swing. |

### Realism

| Rule | Type | Default | What it does |
|---|---|---|---|
| `botMissChance` | int | `0` | Percent chance of deliberately missing a swing. Per-bot 0–100. |
| `botMistakeChance` | int | `0` | Percent chance of aiming a long way off the target that tick. Per-bot 0–100. |

## 1.8-style combat

| Rule | Type | Default | What it does |
|---|---|---|---|
| `swordBlockHitting` | bool | `false` | Master switch for sword blocking. See [SwordBlocking.md](SwordBlocking.md). |
| `swordBlockWindowTicks` | int | `6` | How long the block window lasts after the use key goes down, in ticks. Non-negative. It governs the knockback reduction and the pose clients draw; the damage reduction follows the sword being in use, not the window. |
| `swordBlockDamageMultiplier` | double | `0.5` | Damage taken while blocking is multiplied by this. 0 to 1. |
| `swordBlockKnockbackMultiplier` | double | `0.5` | Knockback taken while blocking is multiplied by this. 0 to 1. |
| `spamClickCombat` | bool | `false` | Remove the attack cooldown so 1.8-style spam clicking works. With it off, a fake player will not swing below 90% attack strength. |
| `shieldStunning` | bool | `false` | Allow damage straight after a shield is disabled, instead of the player being invulnerable for 20 ticks. |
| `damageTickOverrides` | bool | `false` | Master switch for the seven `damageTick*` rules below. |
| `damageTickSword` | int | `10` | Invulnerability ticks after a sword hit. |
| `damageTickAxe` | int | `10` | Invulnerability ticks after an axe hit. |
| `damageTickTrident` | int | `10` | Invulnerability ticks after a trident hit. |
| `damageTickMeleeOther` | int | `10` | Invulnerability ticks after any other melee hit. |
| `damageTickProjectile` | int | `10` | Invulnerability ticks after projectile damage. |
| `damageTickExplosion` | int | `10` | Invulnerability ticks after explosion damage. |
| `damageTickOther` | int | `10` | Invulnerability ticks after anything else. |

The `damageTick*` values are only read while `damageTickOverrides` is on. Each of them is applied
after a hit that actually landed, and only ever raises the remaining invulnerability, never lowers
it.

## CarpetLogic

| Rule | Type | Default | What it does |
|---|---|---|---|
| `carpetLogicPort` | int | `9876` | Port the web editor listens on. 1 to 65535, applied at server start. Suggests `9876`. The `-Dcarpet.logicPort=<n>` system property overrides it, which is how the self-test asks the operating system for a free port. |
| `carpetLogicBindAddress` | string | `127.0.0.1` | Interface the web editor listens on, applied at server start. `127.0.0.1` keeps it on the server's own machine; `0.0.0.0` opens it up. Suggests those two. |
| `carpetLogicSessionHours` | int | `24` | How long a link from `/carpetlogic open` stays valid. Non-negative, so `0` expires a token the moment it is used. Suggests `1` and `24`. |
| `carpetLogicUpdateInterval` | int | `5` | Ticks between bot status pushes on the editor's event stream. 1 to 1024. Suggests `1`, `5` and `20`. |
| `carpetLogicViewerMode` | bool | `false` | The editor can look at bots and programs but not change or run anything. Refuses every API request that is not a `GET`, except an admin changing a setting. |
| `carpetLogicAdminLogin` | bool | `false` | Offers an admin sign-in on the web editor: a player who may use `/carpet` sets a web password through a link from `/carpetlogic password`, signs in with their name and that password, and can then change rules from the editor's Settings panel. Off, the sign-in and its routes do not exist and the panel is read only. The editor speaks plain HTTP, so put it behind a reverse proxy with TLS before offering this beyond localhost. See [The admin sign-in](CarpetLogic.md#the-admin-sign-in). |
| `carpetLogicMaxPrograms` | int | `4` | How many bot programs may run at once. Non-negative, so `0` stops any program from starting. |

See [CarpetLogic.md](CarpetLogic.md) for what these do to the security model.

## Related pages

- [Commands.md](Commands.md) — every command the mod registers
- [Bots.md](Bots.md) — the combat AI these rules are the defaults for
- [FakePlayers.md](FakePlayers.md) — spawning and driving fake players
- [AutoSetup.md](AutoSetup.md) — `/auto-setup`, which turns `fakePlayerNavigation` on while it runs
- [Menus.md](Menus.md) — the in-game menu
- [Practice.md](Practice.md) — drills, matches and factions
- [Kits.md](Kits.md) — `/bot kit` and the kit files
- [SwordBlocking.md](SwordBlocking.md) — `swordBlockHitting` in detail
- [CarpetLogic.md](CarpetLogic.md) — the bot programming layer
- [SelfTest.md](SelfTest.md) — the scenarios that cover all of this