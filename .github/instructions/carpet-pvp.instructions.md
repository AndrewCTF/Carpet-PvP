---
description: "Use when working on Carpet PvP mod code, adding rules, editing mixins, modifying commands, fake player logic, PvP mechanics, navigation/pathfinding, Scarpet integration, or any Fabric mod development in this project. Covers architecture, conventions, all 109+ rules, commands, mixins, and PvP-specific systems."
applyTo: "src/**"
---

# Carpet PvP — Complete Project Reference

## Identity

- **Mod Name**: Carpet PVP
- **Mod ID**: `carpet`
- **Current Version**: 14.9
- **Minecraft Version**: 1.21.11 (supports `>=1.21.11 <1.23`)
- **Fabric Loader**: `>=0.18.3`
- **Fabric API**: Required (any version)
- **Java**: Source/target 21, toolchain 25
- **License**: MIT
- **Authors**: AndrewCTF, gnembon, TheobaldTheBird, redbuc
- **Repository**: `https://github.com/AndrewCTF/Carpet-PvP`
- **Description**: A fork of Carpet mod with PvP tweaks — 1.8-style combat (spam clicking, sword block-hitting), configurable damage ticks, shield stunning, advanced fake player navigation, elytra gliding control, and all original Carpet functionality.

## Build & Run

- **Build**: `./gradlew build -x test`
- **Run Client**: `./gradlew runClient`
- **Run Server**: `./gradlew runServer`
- **Publish Local**: `./gradlew publishToMavenLocal`
- **Archive Name**: `carpet-pvp`
- **Version Format**: `{minecraft_version}-{mod_version}+v{YYMMDD}`
- **Maven Group**: `carpet`

## Architecture Overview

```
Entry Points (fabric.mod.json)
├── main:   CarpetServer::onGameStarted, PvpInitializer
├── client: CarpetServer::onGameStarted, PvpClientInitializer
└── server: CarpetServer::onGameStarted, CarpetRulePrinter

CarpetServer (Lifecycle)
├── onGameStarted() → SettingsManager + CarpetSettings rules + extensions
├── onServerLoaded() → ScriptServer, loggers, mob AI reset
├── onServerLoadedWorlds() → hopper counters, Scarpet world init
├── tick() → HUD, scripts, scheduled commands
├── registerCarpetCommands() → all Brigadier commands
├── onPlayerLoggedIn/Out() → network, loggers, scripts
└── onServerClosed/DoneClosing() → cleanup

Packages
├── carpet/commands/     → Brigadier command handlers
├── carpet/patches/      → EntityPlayerMPFake (fake players)
├── carpet/pvp/          → PvP-specific initializers & helpers
├── carpet/settings/     → Rule, ParsedRule, SettingsManager, Validator
├── carpet/helpers/      → ActionPack, HopperCounter, Explosion, Redstone
├── carpet/helpers/pathfinding/ → NavAStar, ElytraAStar, BotNavMode
├── carpet/logging/      → Logger, LoggerRegistry, HUDController
├── carpet/logging/logHelpers/  → TNT, Explosion, Packet, Trajectory, Pathfinding
├── carpet/script/       → Scarpet scripting engine (full language + API)
├── carpet/mixins/       → 200+ mixins (combat, blocks, performance, scarpet)
├── carpet/network/      → Carpet protocol + custom payloads
├── carpet/fakes/        → Interfaces for mixin targets
└── carpet/utils/        → Utilities (CarpetRulePrinter, etc.)
```

## Entry Points

### CarpetServer.java

Central lifecycle manager. Key flow:

1. **`onGameStarted()`** — Creates `SettingsManager`, parses `CarpetSettings` rules, calls extension hooks, parses Scarpet function classes, logs "CARPET PVP LOADED". Checks for incompatible mod `worldthreader`.
2. **`onServerLoaded(server)`** — Stores server ref, resets spawn stats, attaches settings managers, creates `CarpetScriptServer`, resets mob AI trackers, initializes loggers.
3. **`onServerLoadedWorlds(server)`** — Resets hopper counters, initializes Scarpet for world, extension hooks.
4. **`tick(server)`** — Updates HUD, ticks script server, ticks scheduled commands, resets fill update state, extension tick hooks.
5. **`registerCarpetCommands(dispatcher, env, access)`** — Registers all commands via Brigadier.
6. **`onServerClosed(server)`** — Closes script server, network handlers, clears scheduled commands, stops loggers/HUDs, clears particle cache.

### PvpInitializer.java (main entrypoint)

Server-side PvP initialization:
- Registers `SwordBlockRequestPayload` C2S codec for sword blocking
- `ServerPlayNetworking` receiver handles client block-hitting requests
- Applies `PlayerSwordBlockInterface` to set sword block ticks on right-click with sword
- Broadcasts `SwordBlockPayload` to nearby clients within 64 blocks
- Registers `AttackBlockCallback.EVENT` for left-click block attacks
- Implements damage penalty for hitting tool-required blocks without correct tool (when `punishWrongToolHits` is enabled)

### PvpClientInitializer.java (client entrypoint)

Client-side PvP visuals:
- Registers `SwordBlockPayload` S2C codec
- Handles incoming block-hit payloads → calls `SwordBlockVisuals.activate()`
- Monitors `ClientTickEvents.END_CLIENT_TICK` for right-click detection
- Sends C2S `SwordBlockRequestPayload` when sword is held and right-click pressed
- Edge-trigger detection for right-click (checks main hand, then offhand)

## All CarpetSettings Rules (109+)

### Combat & PvP

| Rule | Type | Default | Description |
|------|------|---------|-------------|
| `spamClickCombat` | bool | false | Removes attack cooldown for 1.8-style spam clicking |
| `swordBlockHitting` | bool | false | Enables 1.8-style sword block hitting on right-click |
| `swordBlockWindowTicks` | int | 6 | Block-hitting window length in ticks |
| `swordBlockDamageMultiplier` | double | 0.5 | Damage multiplier while block-hitting (0.0–1.0) |
| `swordBlockKnockbackMultiplier` | double | 0.5 | Knockback multiplier while block-hitting (0.0–1.0) |
| `shieldStunning` | bool | false | Enables shield-stunning for immediate post-shield damage |
| `damageTickOverrides` | bool | false | Override damage invulnerability ticks by attack type/weapon |
| `damageTickSword` | int | 10 | Invulnerability ticks for sword melee |
| `damageTickAxe` | int | 10 | Invulnerability ticks for axe melee |
| `damageTickTrident` | int | 10 | Invulnerability ticks for trident melee |
| `damageTickMeleeOther` | int | 10 | Invulnerability ticks for other melee |
| `damageTickProjectile` | int | 10 | Invulnerability ticks for projectile damage |
| `damageTickExplosion` | int | 10 | Invulnerability ticks for explosion damage |
| `damageTickOther` | int | 10 | Invulnerability ticks for other damage types |

### Fake Player Navigation

| Rule | Type | Default | Description |
|------|------|---------|-------------|
| `fakePlayerNavigation` | bool | false | Enables built-in navigation/pathfinding via `/player nav` |
| `fakePlayerElytraGlide` | bool | false | Enables precise elytra gliding controls via `/player glide` |
| `fakePlayerNavBreakBlocks` | bool | false | Allow breaking blocks in navigation path |
| `fakePlayerNavPlaceBlocks` | bool | false | Allow placing blocks to bridge gaps |
| `fakePlayerNavAutoTool` | bool | true | Auto-select best tool when breaking |
| `fakePlayerNavAutoEat` | bool | true | Auto-eat food when hungry |
| `fakePlayerNavAutoEatBelow` | int | 10 | Hunger threshold for auto-eat (0–20) |
| `fakePlayerNavAvoidLava` | bool | true | Avoid lava while navigating |
| `fakePlayerNavAvoidFire` | bool | true | Avoid fire while navigating |
| `fakePlayerNavAvoidCobwebs` | bool | true | Avoid cobwebs while navigating |
| `fakePlayerNavBreakCobwebs` | bool | true | Allow breaking cobwebs when navigating |
| `fakePlayerNavAvoidPowderSnow` | bool | true | Avoid powder snow while navigating |
| `fakePlayerNavAllowParkour` | bool | true | Allow gap-jumping during navigation |
| `fakePlayerNavAllowPillar` | bool | false | Allow pillar-jumping (place blocks at feet) |
| `fakePlayerNavAllowBreakThrough` | bool | false | Allow mining through obstacles |
| `fakePlayerNavAllowDescendMine` | bool | false | Allow downward mining during navigation |
| `fakePlayerNavAllowSprint` | bool | true | Allow sprinting during navigation |
| `fakePlayerNavMobAvoidance` | bool | false | Enable mob avoidance during navigation |
| `fakePlayerNavMobAvoidanceRadius` | int | 8 | Radius for mob avoidance (4–32) |
| `fakePlayerNavMaxFallHeight` | int | 4 | Maximum safe fall height in blocks |
| `fakePlayerNavAvoidSoulSand` | bool | false | Penalize soul sand paths |
| `fakePlayerNavAllowOpenDoors` | bool | true | Allow opening doors during navigation |
| `fakePlayerNavAllowOpenFenceGates` | bool | true | Allow opening fence gates |
| `fakePlayerNavAllowSwimming` | bool | false | Allow underwater swimming |

### Fake Player & Player

| Rule | Type | Default | Description |
|------|------|---------|-------------|
| `commandPlayer` | string | "ops" | Enables `/player` command permission |
| `allowSpawningOfflinePlayers` | bool | true | Spawn offline players in online-mode servers |
| `allowListingFakePlayers` | bool | false | Allow listing fake players on multiplayer screen |
| `fakePlayerFallDamage` | bool | true | Enable fall damage for fake players |
| `playerFallDamage` | bool | true | Enable fall damage for real players |
| `fakePlayerDropInventoryOnDeath` | bool | false | Fake players drop inventory on death |
| `punishWrongToolHits` | bool | false | Damage players hitting tool-required blocks with wrong tool |

### TNT & Explosives

| Rule | Type | Default | Description |
|------|------|---------|-------------|
| `explosionNoBlockDamage` | bool | false | Explosions won't destroy blocks |
| `xpFromExplosions` | bool | false | XP drops from explosion-barring blocks |
| `tntPrimerMomentumRemoved` | bool | false | Remove random TNT momentum when primed |
| `optimizedTNT` | bool | false | TNT causes less lag when exploding |
| `tntRandomRange` | double | -1 | Fixed TNT random explosion range (-1 = vanilla) |
| `hardcodeTNTangle` | double | -1.0 | Fixed horizontal angle on TNT (0–2π, -1 = vanilla) |
| `mergeTNT` | bool | false | Merge stationary primed TNT entities |
| `tntDoNotUpdate` | bool | false | TNT doesn't update when placed against power source |

### Renewable Resources & Spawning

| Rule | Type | Default | Description |
|------|------|---------|-------------|
| `renewableSponges` | bool | false | Guardians turn to Elder Guardians when struck by lightning |
| `huskSpawningInTemples` | bool | false | Only husks spawn in desert temples |
| `shulkerSpawningInEndCities` | bool | false | Shulkers respawn in end cities |
| `piglinsSpawningInBastions` | bool | false | Piglins respawn in bastion remnants |
| `renewableBlackstone` | bool | false | Basalt generator converts to blackstone without soul sand |
| `renewableDeepslate` | bool | false | Lava/water generate deepslate below Y0 |
| `renewableCoral` | enum | FALSE | Coral structures grow with bonemeal (FALSE/EXPANDED/TRUE) |
| `desertShrubs` | bool | false | Saplings turn into dead shrubs in hot climates |
| `silverFishDropGravel` | bool | false | Silverfish drop gravel when breaking out |

### Commands

| Rule | Type | Default | Description |
|------|------|---------|-------------|
| `commandSpawn` | string | "ops" | Enables `/spawn` command |
| `commandTick` | string | "ops" | Enables `/tick` command |
| `commandProfile` | string | "true" | Enables `/profile` command |
| `commandLog` | string | "true" | Enables `/log` command |
| `commandDistance` | string | "true" | Enables `/distance` command |
| `commandInfo` | string | "true" | Enables `/info` command |
| `commandPerimeterInfo` | string | "true" | Enables `/perimeterinfo` command |
| `commandDraw` | string | "ops" | Enables `/draw` commands |
| `commandScript` | string | "true" | Enables `/script` command |
| `commandScriptACE` | string | "ops" | Scarpet code execution permission level |
| `commandTrackAI` | string | "ops" | Track mobs AI via `/track` command |
| `hopperCounters` | bool | false | Hoppers count items passing through wool |
| `carpets` | bool | false | Placing carpets issues carpet commands for non-ops |

### Server Configuration

| Rule | Type | Default | Description |
|------|------|---------|-------------|
| `antiCheatDisabled` | bool | false | Prevent rubberbanding when moving too fast |
| `quasiConnectivity` | int | 1 | Pistons check power above them at this range |
| `flippinCactus` | bool | false | Hold cactus to flip/rotate blocks |
| `viewDistance` | int | 0 | Override server view distance (0–32, 0 = default) |
| `simulationDistance` | int | 0 | Override simulation distance (0–32, 0 = default) |
| `pushLimit` | int | 12 | Piston push limit (1–1024) |
| `railPowerLimit` | int | 9 | Powered rail power range |
| `forceloadLimit` | int | 256 | Forceload chunk limit |
| `maxEntityCollisions` | int | 0 | Max entity collision limits (0 = vanilla) |
| `pingPlayerListLimit` | int | 12 | Multiplayer menu sample limit |
| `customMOTD` | string | "_" | Custom MOTD message ("_" = server default) |
| `tickSyncedWorldBorders` | bool | false | World borders move by game time not real time |

### Creative & Client

| Rule | Type | Default | Description |
|------|------|---------|-------------|
| `creativeNoClip` | bool | false | Creative mode noclip |
| `creativeFlySpeed` | double | 1.0 | Creative flight speed multiplier |
| `creativeFlyDrag` | double | 0.09 | Creative flight air drag |
| `fogOff` | bool | false | Remove fog in nether/end |
| `cleanLogs` | bool | false | Remove obnoxious log messages |
| `smoothClientAnimations` | bool | true | Smooth animations with low TPS |
| `structureBlockLimit` | int | 48 | Structure block axis limit |
| `structureBlockIgnored` | string | "minecraft:structure_void" | Block ignored by structure block |
| `structureBlockOutlineDistance` | int | 96 | Structure outline render distance |

### Bug Fixes & Miscellaneous

| Rule | Type | Default | Description |
|------|------|---------|-------------|
| `ctrlQCraftingFix` | bool | false | Ctrl+Q works from crafting result slot |
| `persistentParrots` | bool | false | Parrots stay until proper damage |
| `xpNoCooldown` | bool | false | XP absorbed instantly |
| `stackableShulkerBoxes` | string | "false" | Empty shulkers can stack |
| `movableAmethyst` | bool | false | Budding amethyst can be moved by pistons |
| `movableBlockEntities` | bool | false | Pistons can push block entities |
| `chainStone` | enum | FALSE | Chains stick together (TRUE/FALSE/STICK_TO_ALL) |
| `fillUpdates` | bool | true | Fill/clone/setblock cause block updates |
| `interactionUpdates` | bool | true | Placing blocks cause block updates |
| `liquidDamageDisabled` | bool | false | Disable breaking from flowing liquids |
| `rotatorBlock` | bool | false | Cactus in dispensers rotates blocks |
| `missingTools` | bool | false | Glass breaks faster with pickaxes |
| `moreBlueSkulls` | bool | false | Wither shoots more blue skulls |
| `fastRedstoneDust` | bool | false | Redstone dust lag optimization |
| `placementRotationFix` | bool | false | Fix block placement rotation on fast rotation |
| `lagFreeSpawning` | bool | false | Spawning uses less CPU/memory |
| `thickFungusGrowth` | enum | FALSE | Nether fungi grow with 3x3 base (FALSE/RANDOM/ALL) |
| `lightningKillsDropsFix` | bool | false | Lightning doesn't kill drops |
| `creativePlayersLoadChunks` | bool | true | Creative players load chunks |
| `updateSuppressionBlock` | int | -1 | Activator rail stack updates (-1 = disabled) |
| `sculkSensorRange` | int | 8 | Sculk sensor range in blocks |
| `summonNaturalLightning` | bool | false | Summoned lightning has natural effects |

### Scarpet

| Rule | Type | Default | Description |
|------|------|---------|-------------|
| `scarpetItemUseEvents` | bool | true | Scarpet intercepts item use events |
| `defaultLoggers` | string | "none" | Default loggers for new players |
| `scriptsAutoload` | bool | true | Scarpet scripts autoload on start |
| `scriptsDebugging` | bool | false | Enable scripts debugging messages |
| `scriptsOptimization` | bool | true | Enable scripts optimization |
| `scriptsAppStore` | string | "gnembon/scarpet/contents/programs" | Online scarpet apps repo link |
| `perfPermissionLevel` | int | 4 | `/perf` command permission level |
| `language` | string | "en_us" | Sets Carpet language |
| `carpetCommandPermissionLevel` | string | "ops" | Carpet command permission level |

## Commands

### `/player <name> ...` (PlayerCommand.java)

Primary bot control interface. Requires `commandPlayer` rule.

**Lifecycle**: `spawn [at <x y z>] [facing <yaw> <pitch>] [in <dimension>] [in <gamemode>]`, `kill`, `disconnect`, `shadow`, `stop`

**Movement**: `move <forward|backward|left|right> [sneaking|sprinting] [for <ticks>]`, `sneak`/`unsneak`, `sprint`/`unsprint`, `jump [once|continuous|interval <ticks>]`

**Looking**: `look <north|south|east|west|up|down|at <pos>|<yaw> <pitch>>`, `turn <left|right|back|<yawDelta> <pitchDelta>>`

**Combat**: `attack [crit] [once|continuous|interval <ticks>]`, `swing [once|continuous|interval]`

**Interaction**: `use [once|continuous|interval]`, `swapHands`, `drop/dropStack [all|mainhand|offhand|<slot>]`, `hotbar <1..9>`

**Equipment**: `equip <armor_type>`, `equip <slot> <item>`, `unequip <slot>`, `equipment`

**Item Cooldowns**: `itemCd [<item> [reset|set [<ticks>]]]`

**Navigation** (requires `fakePlayerNavigation`): `nav goto|follow|come|mine|patrol|options|status|stop`

**Elytra** (requires `fakePlayerElytraGlide`): `glide start|stop|freeze|arrival|launch`

### Other Commands

| Command | Rule | Purpose |
|---------|------|---------|
| `/counter [<color>] [reset\|realtime]` | `hopperCounters` | Query/reset hopper counters |
| `/distance <from\|to> [<pos>]` | `commandDistance` | Measure distances |
| `/info block <pos> [grep <regexp>]` | `commandInfo` | Block info with regex filtering |
| `/log <logger> [<option>] [player]` | `commandLog` | Subscribe to event loggers |
| `/profile [health\|entities] [ticks]` | `commandProfile` | Tick profiler (20–24000 ticks) |
| `/spawn <tracking\|test\|mocking\|rates\|mobcaps>` | `commandSpawn` | Mob spawn simulation/tracking |
| `/draw <shape> ...` | `commandDraw` | Draw shapes (sphere, ball, diamond, pyramid, cone, cylinder, cuboid) |
| `/track <entity_type> [clear\|<aspect>]` | `commandTrackAI` | Mob AI tracking |
| `/perimeterinfo [center] [mob]` | `commandPerimeterInfo` | Spawn area analysis |
| `/schedule command <ticks> <command>` | always | Schedule delayed commands |

## Fake Players (EntityPlayerMPFake.java)

Server-side players controlled via `/player`. Key capabilities:

- **Lifecycle**: `createFake()` spawns with GameProfile resolution; `createShadow()` clones a real player; auto-respawn on death; graceful disconnect
- **Equipment**: Auto-equip armor, sync to all clients, persistent state across dimension changes, equipment caching to prevent redundant syncs
- **Combat**: Server-side damage handling with shield mechanics, sword block-hitting with configurable damage/KB multipliers, critical strike detection with post-landing delay
- **Movement**: Forced rotation sync (head/body/eye), step height of 0.6F, position reset every 10 ticks to prevent drift
- **Dimensions**: Equipment state saved before teleportation, restored after dimension change
- **Health**: Initial 20.0F, configurable fall damage, absorption/fire/food reset on respawn
- **Network**: IP returns "127.0.0.1", listing controlled by `allowListingFakePlayers`, all model layers shown

## Navigation & Pathfinding

Located in `carpet/helpers/pathfinding/`:

- **NavAStarPathfinder** — A* ground pathfinding with configurable avoidance (lava, fire, cobwebs, powder snow, soul sand), support for breaking/placing blocks, door/fence-gate opening, parkour, pillar-jumping, mining through obstacles
- **ElytraAStarPathfinder** — A* elytra flight path planning with arrival actions (stop, freeze, descend, land, circle) and launch options (assist, pitch, speed, forwardTicks)
- **BotNavMode** — Enum for navigation modes (goto, follow, come, mine, patrol)

## Mixins (200+ in carpet.mixins.json)

### Combat & PvP
- `Player_attackCooldown_18CombatMixin` — 1.8 attack cooldown removal
- `Player_attackResetTicker_18CombatMixin` — Attack reset timing
- `Player_swordBlockStateMixin` — Sword blocking state tracking
- `ItemStack_swordBlockAnimationMixin` — Sword block animation
- `Player_shieldStunMixin` — Shield stunning mechanic
- `Player_fakePlayersMixin` — Fake player interaction
- `Player_fakePlayerCritMixin` — Bot critical strikes

### Block & Physics
- `TntBlock_noUpdateMixin`, `PrimedTntMixin` — TNT behavior
- `PistonBaseBlock_qcMixin` — Quasi-connectivity
- `PistonBaseBlock_rotatorBlockMixin` — Block rotation
- `CoralPlantBlock_renewableCoralMixin`, `CoralFanBlock_renewableCoralMixin` — Renewable coral
- `BuddingAmethystBlock_movableAmethystMixin` — Movable amethyst
- `LiquidBlock_renewableBlackstoneMixin`, `LavaFluid_renewableDeepslateMixin` — Renewable materials

### Performance
- `RedstoneWireBlock_fastMixin` — Fast redstone evaluation
- `MinecraftServer_tickspeedMixin` — Tickspeed control
- `ServerLevel_tickMixin` — Level ticking optimizations

### Scarpet
- `ServerPlayer_scarpetEventMixin` — Event firing
- `Level_scarpetPlopMixin` — Structure placement
- `CommandNode_scarpetCommandsMixin` — Command integration

## Logging System

- **LoggerRegistry** — Central registration, built-in loggers
- **HUDController** — HUD overlay management
- **Log Helpers**: `TNTLogHelper`, `ExplosionLogHelper`, `PacketCounter`, `TrajectoryLogHelper`, `PathfindingVisualizer`

## Scarpet Scripting

Full scripting language with:
- `CarpetScriptServer` — Server-level script management
- `CarpetScriptHost` — Per-script execution host
- `CarpetExpression` — Expression evaluation
- `CarpetEventServer` — Event system
- API modules: Entities, Blocks, Inventories, Scoreboard, Auxiliary
- Scripts directory: `src/main/resources/assets/carpet/scripts/`
- App store: configurable via `scriptsAppStore` rule

## Extension System

- **Register**: `CarpetServer.manageExtension(extension)`
- **Hooks**: `onGameStarted()`, `onServerLoaded()`, `onServerLoadedWorlds()`, `tick()`, `onServerClosed()`
- **Settings**: Extensions can provide their own `SettingsManager` with custom rules
- **Loggers**: Extensions register loggers via `CarpetServer.registerExtensionLoggers()`

## Settings System

- `@Rule` annotation on fields in `CarpetSettings.java`
- `SettingsManager` parses rules, handles validation, provides command interface
- `ParsedRule` — Parsed rule with metadata, category, validators
- `Validator` — Validates rule values before applying
- `RuleCategory` — Categories: FEATURE, COMMAND, CREATIVE, SURVIVAL, CLIENT, TNT, BUGFIX, OPTIMIZATION, EXPERIMENTAL, SCARPET, DISPENSER
- `Condition` — Conditional rule activation

## Access Widener

Key widened targets (in `carpet.accesswidener`):
- `ChunkMap$DistanceManager`, `ThreadedLevelLightEngine$TaskType`
- `WorldBorder$BorderExtent`, `WorldBorder$StaticBorderExtent`
- `MinecraftServer$ReloadableResources`, `Biome$ClimateSettings`
- `SculkSensorBlockEntity$VibrationUser`, `Player`
- Methods: `WorldBorder.getListeners()`, `DefaultRedstoneWireEvaluator.calculateTargetStrength()`
- Fields: `BlockBehaviour.UPDATE_SHAPE_ORDER`

## Test Coverage

Located in `src/test/java/carpet/`:
- `ComprehensiveTestSuite` — Orchestrates all test suites
- `ArmorSetDefinitionTest` — Equipment slot mapping validation
- `EquipmentValidationTest` — Equipment parameter validation
- `PlayerCommandIntegrationTest` — Command integration tests
- `ScarpetEquipmentIntegrationTest` — Scarpet function integration
- `VanillaCommandIntegrationTest` — Vanilla command interaction
- `ManualTestingScenarios` — Structured manual test scenarios (visibility, protection, persistence, performance)

## Conventions

- Mixins registered in `carpet.mixins.json`; naming: `TargetClass_featureMixin`
- Commands use Brigadier, registered in `CarpetServer.registerCarpetCommands`
- New rules: add `@Rule`-annotated field to `CarpetSettings.java` with category, description, validators
- Fake player behavior managed through `EntityPlayerActionPack`
- PvP features use custom payloads (`SwordBlockRequestPayload`, `SwordBlockPayload`) via Fabric Networking API
- Mappings: Official Mojang mappings via `loom.officialMojangMappings()`
- Loom: `fabric-loom` 1.14-SNAPSHOT with `loom-quiltflower` decompiler
