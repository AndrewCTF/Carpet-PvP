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
combat AI, fake-player navigation and gliding, 1.8-style combat, and CarpetLogic. Everything else in
`CarpetSettings` is upstream's and belongs in upstream's documentation.

Two things to know about reading the tables:

- **Type** is the Java type of the setting. `bool` is `true`/`false`, `int` and `double` are numbers,
  `perm` is a permission level that also accepts `true`, `false` and `ops`.
- **Default** is the value in a fresh world, before anyone touches `/carpet`.
- Where a rule lists values in its last column, those are the only values it accepts. Anything else
  is refused with "Valid values for this rule are: ...".

## Fake-player navigation

`fakePlayerNavigation` has to be on before any `/player <name> nav ...` command does anything, and
only fake players can use it.

| Rule | Type | Default | What it does |
|---|---|---|---|
| `fakePlayerNavigation` | bool | `false` | Master switch for `/player <name> nav ...`. With it off, navigation also stops itself mid-run. |
| `fakePlayerElytraGlide` | bool | `false` | Master switch for `/player <name> glide ...`, and for the `air` navigation mode. Fake players only. |
| `playerFallDamage` | bool | `true` | Whether real players take fall damage at all. |
| `fakePlayerFallDamage` | bool | `true` | Whether fake players take fall damage. |

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

## Bot combat AI

These are the `pvp` category, and they are the **defaults a fake player inherits when it spawns**.
`/player <name> ai reset` puts a bot back to whatever these say at that moment; `/player <name> ai
<setting> <value>` overrides one for that bot. See [FakePlayers.md](FakePlayers.md#combat-ai-ai) for
the per-bot names and the ranges a per-bot value is clamped to.

| Rule | Type | Default | What it does |
|---|---|---|---|
| `botCombat` | bool | `false` | Master switch for the combat AI. Off means bots do not pick targets and do not chase. |
| `botAutoTarget` | bool | `true` | Look for a target without being told. |
| `botTargetPlayers` | bool | `true` | Real players are valid targets. |
| `botTargetMobs` | bool | `false` | Mobs are valid targets. |
| `botTargetBots` | bool | `true` | Other fake players are valid targets. |
| `botRevenge` | bool | `true` | Retaliate against whoever hit the bot in the last 100 ticks, even out of range of the normal scan. |
| `botTargetRange` | double | `16.0` | How far away a target may be and still be picked. Non-negative; a per-bot value is clamped to 2–64. |
| `botRetreatHealth` | int | `0` | Break off the fight at or below this health. `0` means never, since health is never 0 while alive. Per-bot 0–20. |
| `botAutoTotem` | bool | `true` | Move a totem of undying into the offhand when there is none. |
| `botAutoShield` | bool | `false` | Put a shield in the offhand when health is 8 or less — only when `botAutoTotem` is off, so the two never fight over the slot. |
| `botAutoFood` | bool | `true` | Automatic eating. In practice this is navigation's own `fakePlayerNavAutoEat`, not this rule. |
| `botAutoPotion` | bool | `false` | Planned. Stored per bot, not acted on. |
| `botAutoArmor` | bool | `false` | Planned. Stored per bot, not acted on. |
| `botAutoWeapon` | bool | `false` | Planned. Stored per bot, not acted on. |
| `botAutoRepair` | bool | `false` | Planned. Stored per bot, not acted on. |
| `botCombatStyle` | string | `MELEE` | `MELEE`, `CRYSTAL`, `ANCHOR`, `RANGED`, `MACE`. Stored per bot, not acted on yet. |
| `botPreferSword` | bool | `true` | Stored per bot, not acted on yet. |
| `botShieldBreak` | bool | `false` | Planned. Stored per bot, not acted on. |
| `botCritical` | bool | `true` | Chase with jump-and-hit crits instead of flat hits. |
| `botStrafe` | bool | `true` | Sidestep while inside `meleerange` + 1, changing direction every 10 to 25 ticks. |
| `botBhop` | bool | `false` | Planned. Stored per bot, not acted on. |
| `botMeleeRange` | double | `3.0` | How close the bot tries to stay. Per-bot 2–6. |
| `botAttackCooldown` | int | `0` | Ticks between hits. `0` means as fast as the weapon cooldown allows. Per-bot 0–40. |
| `botMissChance` | int | `0` | Percent chance of deliberately missing a swing. Per-bot 0–100. |
| `botMistakeChance` | int | `0` | Planned. Stored per bot, not acted on. |
| `botReactionDelay` | int | `0` | Ticks to wait after picking a new target before engaging it. Per-bot 0–40. |

`botCombat` only switches off a chase the AI itself started. A `nav chase` you set by hand keeps
running.

## 1.8-style combat

| Rule | Type | Default | What it does |
|---|---|---|---|
| `swordBlockHitting` | bool | `false` | Master switch for sword blocking. See [SwordBlocking.md](SwordBlocking.md). |
| `swordBlockWindowTicks` | int | `6` | How long the block window lasts after the use key goes down, in ticks. Non-negative. It governs both the damage and knockback reduction and the pose clients draw. |
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

## Kits

| Rule | Type | Default | What it does |
|---|---|---|---|
| `commandBot` | perm | `true` | Whether `/bot kit ...` works, and for whom. See [Kits.md](Kits.md). |

## CarpetLogic

| Rule | Type | Default | What it does |
|---|---|---|---|
| `commandCarpetLogic` | perm | `ops` | Whether `/carpetlogic` works, and for whom. Also the permission a web editor token's owner is re-checked against. |
| `carpetLogicPort` | int | `9876` | Port the web editor listens on. 1 to 65535, applied at server start. Suggests `9876`. |
| `carpetLogicBindAddress` | string | `127.0.0.1` | Interface the web editor listens on, applied at server start. `127.0.0.1` keeps it on the server's own machine; `0.0.0.0` opens it up. Suggests those two. |
| `carpetLogicSessionHours` | int | `24` | How long a link from `/carpetlogic open` stays valid. Non-negative, so `0` expires a token the moment it is used. Suggests `1` and `24`. |
| `carpetLogicUpdateInterval` | int | `5` | Ticks between bot status pushes on the editor's event stream. 1 to 1024. Suggests `1`, `5` and `20`. |
| `carpetLogicViewerMode` | bool | `false` | The editor can look at bots and programs but not change or run anything. Refuses every API request that is not a `GET`. |
| `carpetLogicMaxPrograms` | int | `4` | How many bot programs may run at once. Non-negative, so `0` stops any program from starting. |

See [CarpetLogic.md](CarpetLogic.md) for what these do to the security model.

## Related pages

- [Commands.md](Commands.md) — every command the mod registers
- [FakePlayers.md](FakePlayers.md) — spawning and driving fake players
- [Kits.md](Kits.md) — `/bot kit` and the kit files
- [SwordBlocking.md](SwordBlocking.md) — `swordBlockHitting` in detail
- [CarpetLogic.md](CarpetLogic.md) — the bot programming layer