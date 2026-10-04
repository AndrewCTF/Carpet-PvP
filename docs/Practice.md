# Drills, matches and looking at a fight

The practice half of `/bot`: drills a player runs against a bot, matches between bots, looking
through a bot's eyes, another account's skin on a bot, the trace of what a bot's last fight was made
of, and the factions that decide who fights whom.

Every subcommand is behind the `commandBot` rule, which is `"true"` by default.

## Drills

A drill is a short exercise a player runs against a bot, scored on what it measures rather than on
who won. The bot fights back only where the drill wants it to.

```
/bot drill list
/bot drill <name>
/bot drill stop
```

`/bot drill list` prints one line:

```
Drills: aim: hit a bot that strafes at 2.5 blocks without fighting back | stunslam: axe a blocking
bot down and mace it inside the disabled window | pearlcatch: chase a bot that throws a pearl at you
and runs | retotem: re-totem as fast as a bot can pop one, every 16 ticks | crystaltiming: put a
crystal under a bot pacing on obsidian, before it steps off
```

`/bot drill <name>` starts one. The bot is spawned in front of you with the kit of its style, with
its own combat and auto-totem and auto-shield switched off, so it does exactly what the drill says.
While it runs, the action bar carries the drill's score. When it is over you get one line and the
bot is taken off the server:

```
Drill aim over: 9 of 12 swings hit (75%) in 234 ticks
```

`/bot drill stop` ends it early. Only a player can start or stop a drill, and a player can only run
one at a time. Your inventory is snapshotted when the drill starts, so `/bot kit restore` gives it
back if you never gave a kit in the first place; a drill that finds a kit on you says
`you still have a kit on: /bot kit restore yourself first`.

### The five drills

| Name | What it asks | Style | Time limit |
|---|---|---|---|
| `aim` | Hit a bot that strafes at 2.5 blocks. It never swings back | `sword` | 400 ticks (20 s) |
| `stunslam` | Axe a raised shield down, then land a mace swing inside the window the disabled shield leaves | `sword` | 600 ticks (30 s) |
| `pearlcatch` | Land a hit on a bot after it throws a pearl at you and turns to run | `mace` | 300 ticks (15 s) |
| `retotem` | Get a fresh totem into your offhand every time one is consumed | `smp` | 800 ticks (40 s) |
| `crystaltiming` | Put a crystal under a bot pacing on obsidian, before it steps off | `crystal` | 400 ticks (20 s) |

What each needs in your inventory, and what it reports:

**`aim`** needs a sword, an axe or a mace. The bot walks to hold 2.5 blocks and flips direction
every 14 ticks. The score is `N swings, M hits, P%`. The summary is `M of N swings hit (P%) in T
ticks`. It ends after twenty swings or at the time limit, whichever comes first.

**`stunslam`** needs an axe and a mace. The bot holds its shield up and never moves. A vertical
velocity over 0.35 inside the disabled window counts as a slam. The score is `S of A shields
slammed`. The summary is `S of A shields down were slammed inside the window, T ticks after the
axe hit`. It ends after three slams or at the time limit.

**`pearlcatch`** needs a sword, an axe or a mace. After twenty ticks the bot is handed a pearl,
throws it at you and runs twelve blocks. The score goes `get ready`, then `run it down`, then
`caught in T ticks`. The summary is `caught with N hits, T ticks after the pearl went out`, or
`not caught within T ticks`, or `the bot never got its pearl away`. It ends on the first hit or at
the time limit.

**`retotem`** needs a totem in your offhand and a spare in your inventory. The bot swings every 16
ticks while in reach. Your offhand going from a totem to nothing is a pop; getting one back is a
success. The score is `P totems popped, re-totem T ticks`, or `re-totem never`. The summary is
`P totems popped, S re-totemed, T ticks on average`, or `you died after P totem(s), re-toteming S
time(s)`. It is the only drill that ends because you died, and after three pops.

**`crystaltiming`** needs an end crystal, and lays a 5×5 pad of obsidian under you, one block down,
which it puts back itself at the end. The bot stands on it, waits twenty ticks and then paces. The
moment it first leaves where it stood is the moment the crystal has to land. The score goes `wait
for it to move`, then `put a crystal under it`, then `detonated in T ticks`. The summary is
`1 of 1 crystals landed, T ticks on average`, or `the bot never left its spot`. It measures one
crystal per run.

### Self-test coverage

`drill_aim_scores` runs `aim` with a diamond sword and checks it ends with a hit and the sword back.
`drill_skips_without_needs` checks `stunslam`, `retotem` and an unknown name are all refused when
the player is empty-handed, and that a refused drill changes nothing. See [SelfTest.md](SelfTest.md).

## Matches

A match is a fight between bots. You start it where you are standing and watch.

```
/bot match ffa <count> <mode> [<difficulty>]
/bot match teams <size> <mode> [<difficulty>]
/bot match join <team>
/bot match stop
```

`<count>` and `<size>` are 1 to 16, and both have to end up as 2 to 16 bots a side. `<mode>` is one
of the six styles, `<difficulty>` optional and defaulting to the `botDifficulty` rule.

| | |
|---|---|
| `ffa <count>` | `count` bots, each its own side |
| `teams <size>` | `size` bots on the `red` team and `size` on `blue`, so `size * 2` bots |

Every bot is spawned on a ring six blocks around you, at your feet level, in your dimension, facing
you, with the kit of the mode. `teams` calls them `Red1`… and `Blue1`… and `ffa` calls them
`Free1`…; a name that is taken gets a number. Match bots all target each other and also real
players, keep a target range of 48 blocks, and have their own totem and shield reflexes switched off
— a bot that keeps its own totem up never goes down, so the match would never end.

You are not moved and not given a kit. Because match bots target players, standing close is a valid
way to join in, which is what `join` makes deliberate:

```
/bot match join red
```

That puts you in the `red` faction for the rest of the match, so the red bots leave you alone and the
blue ones fight you. It only works on a `teams` match, and a bot cannot join.

The status line, printed when a match starts and repeated whenever one is already running:

```
ffa match of 4 bots fighting with MELEE at skilled, 4 still standing, 1200 of 1200 ticks left
```

The style is printed as the enum name, so `MELEE` where the command took `sword`.

A match ends when one side is left standing, or after 1200 ticks (60 seconds). If the time runs out
with more than one side up, the side of whoever dealt the most damage wins; the losing side is named
from whoever is still standing on it. Then the server is told:

```
The match is over: red won with 45.5 damage to 12.0 after 300 ticks
```

and if everybody died at once, `The match ended with nobody left standing`. A match with a winner is
written to the fight history, which keeps the last fifty.

`/bot match stop` takes the bots off the server without deciding a winner, and so writes nothing to
the history.

The history is in memory only, and is not a file. It is what `/bot stats` prints and what the web
editor's match tab shows. Each entry is a winner, a loser, how long the fight took and both damage
totals.

### Self-test coverage

`match_ffa` starts four bots on four sides and checks every one of them deals and takes damage.
`match_teams` starts two sides and checks no bot ever hit a team-mate.

## Looking through a bot's eyes

```
/bot spectate <name>
/bot spectate stop
```

`<name>` can be any player, not only a bot. Your game mode, position, rotation and dimension are
saved, you are put in spectator and your camera is put on the target:

```
Looking through Bot1; /bot spectate stop gives you your eyes back
```

`/bot spectate stop` gives all of it back, including a teleport to the exact spot in the exact
dimension. Spectating somebody else while already spectating stops the first one. Nothing watches
the target, so the camera stays where it is if they die. Your saved spot is dropped when you leave
the server.

## Putting another account's skin on a bot

```
/bot skin <bot> <player>
```

This needs an online-mode server: on an offline one it refuses with `a skin belongs to an account,
and this server is offline`.

Which skin a client draws is not something the server sends; the client looks the profile up itself.
So the bot is taken off the server and put back with the other account's profile id and the bot's
own name, and everything it was doing is restored: position, rotation, health, game mode, its kit,
its combat style and its faction. The account has to exist and must not be connected to the server
at the same time, since two players cannot share an account id.

```
Looking up Steve
Bot1 wears the skin of Bot1 (00000000-0000-0000-0000-000000000000)
```

The second line names the bot twice, because the profile it prints carries the bot's name with the
other account's id. Lookups that fail are broadcast to the server: `no account called <name> could
be found`, or `<holder> is that account and is on this server`.

## Traces

```
/bot trace <name>
```

A trace is the record of one fight, one event at a time. There are four kinds:

| Kind | What it is |
|---|---|
| `HIT` | a click that passed the reach and aim checks and landed, with the damage and whether it was a crit |
| `MISS` | a click swung at nothing, or at something out of reach |
| `TAKEN` | health the bot lost, with the attacker named |
| `ITEM` | an item the bot put to use, named by its item id |

The summary is one line:

```
300 ticks, 42 hits (9 crits), 7 misses, 55 hits taken, 210.5 dealt, 440.0 taken, 12 item uses
```

`/bot trace <name>` prefers the fight the bot is having now and says `its fight right now`; if there
is none it prints the last one and says `its last fight`. A bot that has not fought says
`<name> has not fought`.

A trace is written when the fight ends, which is a hundred ticks after the bot last recorded
anything, or at server shutdown. It goes to

```
<world>/carpet-traces/<bot name>.json
```

one file per bot, overwritten each time that bot's fight ends, holding the summary and the full
event list. The summary is counted from the events, so the two cannot drift apart. If a fight
outgrows 4096 events the oldest are dropped and `droppedEvents` says how many. A fight that recorded
nothing is not a fight and nothing is written for it. If a write fails the reason is remembered and
the next `/bot trace` prints it instead of trying again.

### Self-test coverage

`trace_records_fight` reads the file back after a fight and checks it holds exactly as many `HIT`
events as the bot's own counters, with the same damage totals.

## Factions

A faction is a name a set of fighters share. Bots of one faction, or of two factions that are
allied, never target each other. Membership is by entity UUID, so a real player can be in one too.
This is what `/bot duel`, `/bot match teams` and `/bot match join` are built on.

```
/player <target> faction list
/player <target> faction create <name>
/player <target> faction delete <name>
/player <target> faction join <name>
/player <target> faction leave
/player <target> faction info
/player <target> faction ally <a> <b>
/player <target> faction unally <a> <b>
```

| Command | What it does |
|---|---|
| `list` | every faction on the server |
| `create <name>` | makes one. Refuses if it already exists |
| `delete <name>` | removes it, its members' membership and its alliances in both directions |
| `join <name>` | puts every selected fake player in it, creating it if it is not there |
| `leave` | takes every selected fake player out of whatever faction they were in |
| `info` | one bot's faction, its member count and its allies |
| `ally <a> <b>` | makes two factions friendly. Both must exist and must differ |
| `unally <a> <b>` | undoes that |

`create`, `delete`, `ally` and `unally` are server-wide and take no player into account.
`join` and `leave` only do anything to fake players; naming a real player is skipped silently.

```
Faction 'red': 3 member(s), allies=blue
```

### Persistence

Factions are saved when the server stops and read back when it starts:

```
<world>/carpet-factions.json
```

which holds the faction names, the members by UUID and the alliances. A missing or unreadable file
is an empty registry rather than a failure, so a world that has never had a match still starts.
The `FactionManager` class comment still says cross-restart persistence is a planned follow-up; the
file is what is actually wired up.

The connection to a bot's own settings is the `faction` field in its configuration, which
`/bot option <name> faction <name>` and `/player <name> ai faction <name>` both set. Setting it
through `/player ai` and through the menu also updates the registry; `/bot option` does not, so set
it twice or use `/player` for it. `/player <name> ai reset` drops the membership.

### Self-test coverage

`faction_persistence` writes the file, clears the registry, reads it back and checks the names, the
membership and the alliance all survive.

## Related pages

- [Bots.md](Bots.md) — the bots these practice against, and every setting they have
- [AutoSetup.md](AutoSetup.md) — one command for a single fight
- [Menus.md](Menus.md) — the same things from the menu
- [Kits.md](Kits.md) — the loadouts a drill or match hands out
- [FakePlayers.md](FakePlayers.md#factions) — `/player` in full
- [CarpetLogic.md](CarpetLogic.md) — the match list in the web editor
- [Commands.md](Commands.md) — every command
- [SelfTest.md](SelfTest.md) — the scenarios behind all of this