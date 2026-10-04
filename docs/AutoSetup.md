# `/auto-setup`

`/auto-setup` sets a player up to fight a bot with one command: it builds an arena next to them,
saves what they are carrying, hands out the kit of a mode, puts a bot in front of them and starts a
round. After every round it offers the next one. `/auto-setup stop` takes all of it back down.

Everything is behind the `commandAutoSetup` rule, which is `"true"` by default and takes `true`,
`false` or `ops`. Only a player can run it, not a command block. See
[Rules.md](Rules.md#commands-this-fork-adds) for the rule.

## The menu

```
/auto-setup
```

On its own it prints two rows of buttons and a hint: the five modes, then the five difficulties,
with whatever session you are in highlighted. Every button runs the command it stands for, so one
click is enough.

| Row | Buttons |
|---|---|
| modes | `[Sword]`, `[SMP]`, `[Mace]`, `[Crystal]`, `[Ranged]`, each running `/auto-setup <mode>` |
| difficulties | `[beginner]`, `[casual]`, `[average]`, `[skilled]`, `[expert]`, each running `/auto-setup <mode> <difficulty>` |

The hint line below them reads `y  the arena is built next to you, y  /auto-setup stop takes it
away again`. The stray `y` in front of each half is a formatting bug in the mod and is left as it
is here so the page matches what a player sees.

## Starting a session

```
/auto-setup <mode>
/auto-setup <mode> <difficulty>
```

`<mode>` is one of `sword`, `smp`, `mace`, `crystal`, `ranged`. `<difficulty>` is optional; left out,
the `botDifficulty` rule decides, and inside a session the difficulty you are already at is kept.
Both tab-complete.

What one of these does, in order:

1. **Ends any session you already had.** If you ask for the mode you are already fighting, this is a
   rematch instead: the same session carries on, so the score survives. Any other mode ends the old
   session first, which gives your things back and then starts again.
2. **Turns on what the bot needs.** `fakePlayerNavigation`, so the bot can walk to you. Only the
   rules it actually changed are remembered, and the old values are put back at the end.
3. **Saves you to disk before touching anything.** Your inventory, armour, offhand, selected hotbar
   slot, your exact position, your rotation, your dimension and your game mode, in
   `<world>/carpet-autosetup/<your name>.json`. The file is written before the arena goes down and
   again after, so a crash in between still leaves a file behind.
4. **Builds the arena** twenty blocks east of you.
5. **Gives you the kit** of the mode, clears your inventory first, and puts you in survival.
6. **Spawns a bot** called `AS_<your name>` with the same kit, facing you, and switches its combat
   on after a three-second countdown.

### The arenas

Every arena is a floor seventeen blocks across with its centre twenty blocks east of where you were
standing, laid at the height of the top non-leaf block of the single column at that point. The
fighters stand six blocks apart on it, you on the west side facing east, the bot on the east side
facing west.

| Mode | Floor | Edge | On top |
|---|---|---|---|
| `sword` | smooth stone | oak fence | nothing |
| `smp` | smooth stone | oak fence | nothing |
| `ranged` | smooth stone | oak fence | nothing |
| `crystal` | obsidian, with five holes left as the ground had them | obsidian | nothing |
| `mace` | smooth stone | none | four stone-brick pillars, 4, 6, 8 and 10 high, at the corners of a 10×10 square |

The arena never clears anything above the floor, and it is one block thick, so build it somewhere
with room over it. The `mace` arena does not make the open sky its comment mentions; it is whatever
the terrain above already is.

Every block the arena writes over is remembered with the block that was there, so `/auto-setup stop`
puts the world back exactly. A position that already holds the right block, or that holds a block
entity such as a chest, is not written at all and so is not remembered.

### The round

The countdown is three seconds, printed as `3`, `2`, `1...`, and it waits for the bot to be on the
server before it starts, so an online-mode server that has to resolve the name does not lose ticks
off the front of it.

A round ends when one of the two goes down, or when a totem pops in `crystal` mode — in that mode a
totem counts as a loss for whoever was saved, which is what makes the mode a fight rather than a
waiting game. In every other mode a totem is just a totem and the round carries on. When the round
ends you get one line:

```
You won (the bot died)   Score 2-1
```

or `The bot won`, with the reason in brackets: `you died`, `the bot left`, `a totem saved you`,
`a totem saved the bot`, `the bot died`.

Then the round menu, one line:

| Button | Runs |
|---|---|
| `[Rematch]` | `/auto-setup <mode>` — the same session, so the score and the difficulty carry over |
| `[Easier]` | `/auto-setup <mode> <one difficulty down>`, clamped at `beginner` |
| `[Harder]` | `/auto-setup <mode> <one difficulty up>`, clamped at `expert` |
| `[Change mode]` | `/auto-setup` — the menu again |
| `[Stop]` | `/auto-setup stop` |

Between rounds both fighters are put back to full health, cleared of fire and effects, fed and given
the kit again, and moved to their marks. You keep your own score for as long as you keep the mode.

## Stopping

```
/auto-setup stop
```

This disconnects the bot, puts every arena block back, puts the rules it changed back, gives you your
inventory and your selected hotbar slot, restores your game mode and teleports you to the exact
position and rotation you were standing in when you started. Then it says so:

```
The session is over: your things, your place and your game mode are back, and the arena is gone.
```

If any of that cannot be done right now, the session file is kept and you are told your things are
being kept.

The same thing happens, without you typing anything, when you log out and when the server stops. A
second player may have their own session at the same time; each has its own file, its own arena and
its own bot.

## If the server dies

The session file is written before anything is changed, so a crash leaves it behind. On the next
start the mod reads every file in `<world>/carpet-autosetup/`, holds the state in memory, and gives
it back the next time that player logs in:

```
The server did not survive your last /auto-setup session, so your things, your place and your game
mode are back, and the arena is gone.
```

The file is then deleted. A file that cannot be read is logged as an error and left alone. A file
whose name does not end in `.json` is ignored.

Two gaps worth knowing: a file is only picked up at server start, so a session whose restore failed
at logout or at `/auto-setup stop` waits for the next start rather than the next login, and starting
a new session overwrites a file whose restore had failed.

The same applies inside one arena. Two players who both have sessions running share the
`fakePlayerNavigation` rule, and only the first of them records having turned it on, so the first to
stop puts it back while the other is still fighting. The bot walks in a straight line instead, so
the session still works.

## What is and is not saved

| Saved | Not saved |
|---|---|
| The 36 main inventory slots | Health |
| Helmet, chestplate, leggings, boots, offhand | Food level |
| The selected hotbar slot | Potion effects |
| The exact position, rotation and dimension | Fire |
| The game mode | Experience |
| The blocks the arena overwrote | The cursor item, the ender chest, advancements |

The file is `<world>/carpet-autosetup/<name>.json`, written pretty-printed with keys `version`,
`player`, `mode`, `difficulty` and `saved`. `saved.spot` holds the position, `saved.gamemode` the
game mode, `saved.slots` the 36 slots plus five equipment strings, `saved.rules` the rules that were
changed as `name=oldvalue`, and `saved.blocks` every block the arena wrote as `x, y, z, state`. The
`mode` and `difficulty` keys are written and read but nothing acts on them.

## Which kit each mode gives

| Mode | Kit |
|---|---|
| `sword` | `sword` |
| `smp` | `smp` |
| `mace` | `mace` |
| `crystal` | `crystal` |
| `ranged` | `ranged` |

Both you and the bot get it, every round. See [Kits.md](Kits.md) for what is in each one.

## Self-test coverage

`autosetup_roundtrip`, `autosetup_each_mode`, `autosetup_crash_safe` and `autosetup_rules_restored`
cover this page. See [SelfTest.md](SelfTest.md). `autosetup_each_mode` runs `sword`, `smp`, `mace`
and `crystal`; `ranged` is not in that list.

## Related pages

- [Bots.md](Bots.md) — the bot that gets spawned, and every setting it has
- [Menus.md](Menus.md) — spawning a bot from the menu instead
- [Practice.md](Practice.md) — drills and matches
- [Kits.md](Kits.md) — the loadouts
- [Commands.md](Commands.md) — every command
- [Rules.md](Rules.md) — the rules this page touches
- [SelfTest.md](SelfTest.md) — the scenarios