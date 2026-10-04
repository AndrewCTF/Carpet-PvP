# `/bot gui`

`/bot gui` opens a menu of chests that a vanilla client draws without any resource pack: the bots of
the server, one page of settings each, the kits, and a kit editor. Everything here does the same
thing as a command you could type instead.

Behind the `commandBot` rule, and only a player can open it.

```
/bot gui
/bot gui saveas <name>
/bot gui discard
```

`/bot gui` opens the main menu. The other two are what the kit editor's chat prompt hands out, and
they are typed rather than clicked.

How a page works: every page is an ordinary chest container the server owns, of 4, 5 or 6 rows. A
button is an item in a slot; clicking it runs an action; nothing else in the chest can be picked up
or shifted, and closing a page clears its chest so nothing is ever returned to you by accident. The
kit editor is the one exception, and it lets items move. A page repaints itself once a server tick
when anything it shows has changed, so a setting changed by command shows up in an open menu.

## The main menu

Title `Bots`, six rows. The top five rows list the bots of the server, up to 45 of them, one head
per bot; a server with more bots than that needs a command. The bottom row is the menu's own.

Clicking a bot's head opens that bot's page. A bot's head shows:

| Line | |
|---|---|
| `health: 12.0 / 20.0` | green at full health, red below it |
| `style: sword` | the combat style |
| `difficulty: average` | |
| `kit: sword` | or `kit: none` |
| `fighting` or `idle` | green and grey |
| `click to open its settings` | |

The bottom row:

| Slot | Item | Button | What it does |
|---|---|---|---|
| 45 | zombie head | `Spawn a bot` | opens the spawn page |
| 47 | chest | `Kits` | opens the kit list |
| 49 | iron sword | `Start a quick fight` | spawns two bots of the current default style and sets them fighting each other, then says `<first> and its opponent are on their way. Watch with /bot gui` |
| 53 | barrier | `Stop all` | sets `combat` off on every bot of the server, or says `No bot of this server is fighting.` if none was |

## The spawn page

Title `Spawn a bot`, four rows. Nothing is spawned until you press the button at the bottom, and it
goes where you are standing.

The first row picks the style, one button per style in the order `sword`, `crystal`, `anchor`,
`ranged`, `mace`, `smp`. Each is a different item — iron sword, netherite sword, mace, end crystal,
respawn anchor, crossbow — and the one chosen is green and marked with `» `. Clicking one sets it.

The third row picks the difficulty, five buttons named `beginner`, `casual`, `average`, `skilled`,
`expert`, again with the chosen one marked. Clicking one sets it.

| Slot | Item | Button | What it does |
|---|---|---|---|
| 27 | barrier | `Back` | back to the main menu |
| 31 | zombie head | `Spawn it here` | spawns the bot, gives it the style's kit, sets `combat`, `autotarget`, `targetbots` and the difficulty, and says `Spawned BotN fighting with sword at difficulty average` |

The bot is named `Bot1`, `Bot2`, and so on, taking the first name that is free. If the profile takes
a moment to resolve, the settings are applied once it has, not immediately.

## A bot's own page

Title `Bot <name>`, six rows, four pages. Opened by clicking a bot's head on the main menu.

The header, slots 0 to 11:

| Slot | Item | Button | What it does |
|---|---|---|---|
| 0 | the bot's head | the bot's name | back to the main menu. Shows health, `fighting` or `idle` |
| 1 | diamond sword | `Style: sword` | moves to the next style in order and applies it |
| 2–6 | wooden, stone, iron, golden and diamond swords | `Difficulty: beginner` and so on | applies that difficulty preset |
| 7 | chest | `Kit: sword` | gives the next kit of the server in turn, or `none` |
| 8 | barrier | `Remove this bot` | takes the bot off the server. Its kit and settings go with it |
| 9 | iron sword | `Duel me` | makes this bot fight you: it turns on its combat, auto-targeting and targeting of players. If you are a bot yourself, it puts the two of you in factions instead |
| 10 | ender eye | `Spectate` | points your camera at the bot. F5 again stops it |
| 11 | lead | `Faction: red` | moves to the next faction of the server, and to `none` past the end. Bots of one faction leave each other alone |
| 16 | arrow | `Previous page` | only drawn when there is a previous page |
| 17 | arrow | `Next page` | only drawn when there is a next page |

Below the header, slots 18 to 53 hold the settings, four pages of them. A switch is one slot: a
green pane for `on`, a red one for `off`, and clicking it flips it. A number is three slots: a red
stone to lower it, a paper showing the value, and an emerald to raise it. Clicking the paper does
nothing; it is there to read.

The step a number moves by is one unless the setting has its own: `skill` and `clickspersecond`
move in halves, `targetrange` and `plannerrange` in halves, `meleerange` by 0.05.

The order of the settings, and what they do, is in [Bots.md](Bots.md#the-common-settings) and
[Bots.md](Bots.md#the-per-style-settings). Three of them are not on this page: the style, the
difficulty and the faction are in the header, where they are bigger buttons.

Each one carries a line saying what it does, and the ones with a hand-written note are the common
settings; the per-style ones fall back to a note that says `Set with /bot option <bot> <key>
<value>.`, and their labels name the style they belong to: `ranged.bow` reads as `Ranged: bow`,
`smp.retotem` as `SMP: retotem` and `crystal.retotem_delay` as `Crystal: retotem delay`.

A setting at the end of its range simply stops moving when you click, because the value is clamped.

The page repaints as soon as anything it shows changes, including a change made by command or by
another player.

## The kit list

Title `Kits`, five rows. One chest per kit, up to 36, in the order `sword`, `axe`, `smp`, `mace`,
`crystal`, `ranged` and then any kit of your own in alphabetical order. A kit of the mod's is grey
and marked with `» `; one of yours is yellow. The lore line says how many entries it has, or
`cannot be used: <reason>` if the file did not load. Clicking one opens it in the editor.

| Slot | Item | Button | What it does |
|---|---|---|---|
| 37 | paper | `Start an empty kit` | opens the editor with nothing in it, for a loadout of your own |
| 39 | amethyst shard | `Read the kits again` | re-reads the world's kit folder, for kits written outside the game |
| 41 | barrier | `Back` | the main menu |

## The kit editor

Title `Kit <name>`, or `New kit` when it was started empty, six rows. This is the only menu where
items move: you can pick things up, put them down and shift-click them, exactly as in a chest.

The layout is the one a player wears, in 41 slots:

| Slots | |
|---|---|
| 0–8 | the hotbar, shown as `Hotbar slot 1` to `Hotbar slot 9` |
| 9–35 | the rest of the inventory, `Inventory slot 1` to `Inventory slot 27` |
| 36–40 | `head`, `chest`, `legs`, `feet`, `offhand` |

An empty slot holds a grey stained glass pane with that name on it. Clicking a pane with something
on your cursor puts it there and empties your cursor. The panes are markers: they are never saved
into a kit, and a shift-click on one does nothing. Clicking one with an empty hand, on the other
hand, goes through to the ordinary chest behaviour and can lift the pane onto your cursor, from
where it still never reaches a saved kit.

Anything else about an item — an enchantment, a stack count, a custom name, a written book — survives,
because what gets written is the finished item.

| Slot | Item | Button | What it does |
|---|---|---|---|
| 45 | writable book | `Save` | writes this layout under the kit's own name |
| 47 | name tag | `Save as` | asks in chat for a name of your own |
| 49 | barrier | `Delete` | deletes a kit of yours. A kit of the mod's cannot be deleted, and says so |
| 51 | arrow | `Back` | the kit list |

`Save` on a kit of the mod's turns that name into one of yours, which is how you replace `sword` on
your own server.

### Saving under a name of your own

`Save as` takes a snapshot of the layout and then prints four lines in chat:

```
Your layout is held. Save it with /bot gui saveas <name>, or click:
[ save it as <suggestion> ]
[ throw it away ]
```

The suggestion is clickable and runs `/bot gui saveas <suggestion>`. The suggestions are your own
name in lower case with anything that is not a letter, a digit or `_` or `-` turned into `_`, plus
`_kit`, and then that name with `2`, `3` and so on until eight unused ones are found. The
`/bot gui saveas <name>` in the first line is plain text and has to be typed.

So either click the suggestion, or type it:

```
/bot gui saveas steve_kit
```

The name may hold letters, digits, `_` and `-`, up to 64 characters, because it doubles as a file
name. It writes `<world>/carpet-kits/<name>.json` and says
`Saved kit steve_kit with 8 entries.` The layout is dropped once it is saved.

`throw it away`, or the command, drops the held layout without writing anything:

```
/bot gui discard
```

which answers `Threw the kit you were editing away`, or `You have no kit open in the editor` if
there was none. Closing the editor without saving keeps the layout, so you can come back to it; the
held layout goes when you leave the server.

Two things to know about this: the snapshot is taken when you press `Save as`, so edits you make
afterwards are not in what the command writes, and discarding while the editor is still open puts
the layout back when it closes.

## Self-test coverage

`gui_toggle_option`, `gui_cycle_style`, `gui_spawn`, `gui_no_item_theft`,
`gui_kit_editor_roundtrip` and `gui_quick_fight` cover this page. `gui_no_item_theft` walks every
click type over every button of the main, spawn and bot pages and checks the viewer's inventory never
changes. `gui_kit_editor_roundtrip` builds a layout, saves it with `/bot gui saveas`, hands it out
with `/bot kit give` and checks it comes back slot for slot. `gui_quick_fight` presses the quick
fight button and checks the two bots it puts on the server end up fighting each other. See
[SelfTest.md](SelfTest.md).

## Related pages

- [Bots.md](Bots.md) — what every setting on the bot page does
- [Kits.md](Kits.md) — the kit file format and the built-in kits
- [AutoSetup.md](AutoSetup.md) — the other one-command way to start a fight
- [Practice.md](Practice.md) — drills, matches and spectating
- [Commands.md](Commands.md) — every command
- [Rules.md](Rules.md) — the rules these commands are behind