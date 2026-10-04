# PvP kits

Kits are named loadouts. `/bot kit give <players> <kit>` puts one on a player: the inventory is
cleared and refilled, so a `/bot kit restore` later hands back what they were carrying.

Five kits ship with the mod, one per PvP mode. A server can make its own with `/bot kit save`.

## The kit command

| Command | What it does |
|---|---|
| `/bot kit list` | every kit name that can be given, plus any kit that failed to load and why |
| `/bot kit reload` | read the world's kit folder again, picking up files added or edited by hand |
| `/bot kit give <players> <kit>` | clear the players' inventories and hand out the kit |
| `/bot kit save <name>` | save the calling player's inventory as a kit |
| `/bot kit delete <name>` | delete a custom kit |
| `/bot kit restore` | give the calling player back their own inventory |
| `/bot kit restore <players>` | the same for other players |

`<players>` is an entity argument, so selectors work:

```
/bot kit give @a[tag=kit] sword
/bot kit give Bot1,Bot2 crystal
```

Permissions:

- `commandBot` has to allow the sender. It is `"true"` by default, so every player has `/bot`.
- A player who is not op may only touch their own inventory and fake players. Naming a real player
  they do not own is refused.
- `/bot kit save` needs a real player to save from. The console gets
  "Only a player can save their inventory as a kit".
- `/bot kit restore` with no players from the console gets
  "Name the players to restore, for example /bot kit restore @a".

## Built-in kits

### `sword`

| Slot | Item | Count | Enchantments |
|---|---|---|---|
| 0 (main hand) | `minecraft:diamond_sword` | 1 | Sharpness 2 |
| 1 | `minecraft:golden_apple` | 4 | |
| 2 | `minecraft:cooked_beef` | 16 | |
| head | `minecraft:diamond_helmet` | 1 | Protection 4 |
| chest | `minecraft:diamond_chestplate` | 1 | Protection 4 |
| legs | `minecraft:diamond_leggings` | 1 | Protection 4 |
| feet | `minecraft:diamond_boots` | 1 | Protection 4 |
| offhand | `minecraft:shield` | 1 | |

### `axe`

| Slot | Item | Count | Enchantments |
|---|---|---|---|
| 0 (main hand) | `minecraft:diamond_sword` | 1 | Sharpness 2 |
| 1 | `minecraft:diamond_axe` | 1 | Sharpness 2 |
| 2 | `minecraft:golden_apple` | 4 | |
| 3 | `minecraft:cooked_beef` | 16 | |
| head | `minecraft:diamond_helmet` | 1 | Protection 4 |
| chest | `minecraft:diamond_chestplate` | 1 | Protection 4 |
| legs | `minecraft:diamond_leggings` | 1 | Protection 4 |
| feet | `minecraft:diamond_boots` | 1 | Protection 4 |
| offhand | `minecraft:shield` | 1 | |

### `smp`

| Slot | Item | Count | Enchantments |
|---|---|---|---|
| 0 (main hand) | `minecraft:netherite_sword` | 1 | Sharpness 5 |
| 1 | `minecraft:golden_apple` | 5 | |
| 2 | `minecraft:splash_potion` | 8 | potion `minecraft:strong_healing` |
| 3 | `minecraft:splash_potion` | 5 | potion `minecraft:strong_strength` |
| 4 | `minecraft:splash_potion` | 3 | potion `minecraft:strong_swiftness` |
| 5 | `minecraft:ender_pearl` | 3 | |
| 6 | `minecraft:experience_bottle` | 16 | |
| 7 | `minecraft:cobweb` | 3 | |
| 8 | `minecraft:bucket` | 1 | |
| 9 | `minecraft:totem_of_undying` | 1 | |
| 10 | `minecraft:totem_of_undying` | 1 | |
| head | `minecraft:netherite_helmet` | 1 | Protection 4, Unbreaking 3 |
| chest | `minecraft:netherite_chestplate` | 1 | Protection 4, Unbreaking 3 |
| legs | `minecraft:netherite_leggings` | 1 | Protection 4, Unbreaking 3 |
| feet | `minecraft:netherite_boots` | 1 | Protection 4, Unbreaking 3 |
| offhand | `minecraft:shield` | 1 | |

### `mace`

| Slot | Item | Count | Enchantments |
|---|---|---|---|
| 0 (main hand) | `minecraft:mace` | 1 | Density 5, Wind Burst 3 |
| 1 | `minecraft:mace` | 1 | Breach 4 |
| 2 | `minecraft:wind_charge` | 16 | |
| 3 | `minecraft:golden_apple` | 4 | |
| 4 | `minecraft:ender_pearl` | 3 | |
| 5 | `minecraft:firework_rocket` | 24 | |
| 6 | `minecraft:netherite_sword` | 1 | Sharpness 3 |
| 7 | `minecraft:netherite_axe` | 1 | Sharpness 2 |
| 8 | `minecraft:elytra` | 1 | |
| 9 | `minecraft:totem_of_undying` | 1 | |
| 10 | `minecraft:totem_of_undying` | 1 | |
| head | `minecraft:netherite_helmet` | 1 | Protection 4, Unbreaking 3 |
| chest | `minecraft:netherite_chestplate` | 1 | Protection 4, Unbreaking 3 |
| legs | `minecraft:netherite_leggings` | 1 | Protection 4, Unbreaking 3 |
| feet | `minecraft:netherite_boots` | 1 | Protection 4, Unbreaking 3 |
| offhand | `minecraft:shield` | 1 | |

### `crystal`

| Slot | Item | Count | Enchantments |
|---|---|---|---|
| 0 (main hand) | `minecraft:netherite_sword` | 1 | Sharpness 4 |
| 1 | `minecraft:end_crystal` | 8 | |
| 2 | `minecraft:obsidian` | 16 | |
| 3 | `minecraft:respawn_anchor` | 16 | |
| 4 | `minecraft:glowstone` | 16 | |
| 5 | `minecraft:ender_pearl` | 3 | |
| 6 | `minecraft:golden_apple` | 4 | |
| 7 | `minecraft:experience_bottle` | 16 | |
| 8 | `minecraft:netherite_pickaxe` | 1 | Fortune 3 |
| 9 | `minecraft:totem_of_undying` | 1 | |
| 10 | `minecraft:totem_of_undying` | 1 | |
| 11 | `minecraft:totem_of_undying` | 1 | |
| head | `minecraft:netherite_helmet` | 1 | Blast Protection 4, Unbreaking 3 |
| chest | `minecraft:netherite_chestplate` | 1 | Blast Protection 4, Unbreaking 3 |
| legs | `minecraft:netherite_leggings` | 1 | Blast Protection 4, Unbreaking 3 |
| feet | `minecraft:netherite_boots` | 1 | Protection 4, Unbreaking 3 |
| offhand | `minecraft:totem_of_undying` | 1 | |

Slot 0 is always the main inventory index 0, so the first weapon listed is the one the player ends
up holding: `apply()` sets the selected slot to 0 after the kit goes on.

## The kit file format

There are two shapes of kit file. Both are accepted everywhere a kit is read from; which one a file
is written in is up to whoever wrote it.

### Hand-written kits: item descriptions

This is the shape the five built-in kits use, in `src/main/resources/assets/carpet/kits/`:

```json
{
  "name": "sword",
  "items": [
    { "item": "minecraft:diamond_sword", "slot": 0,
      "enchantments": [ { "id": "minecraft:sharpness", "level": 2 } ] },
    { "item": "minecraft:golden_apple", "count": 4, "slot": 1 },
    { "item": "minecraft:splash_potion", "count": 8, "slot": 2, "potion": "minecraft:strong_healing" },
    { "item": "minecraft:shield", "slot": "offhand" }
  ]
}
```

Top level:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `name` | string | no | the kit's name. Defaults to the file's name without `.json`. |
| `items` | array | yes | the entries, applied in the order they are listed. |

Each entry:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `item` | string | required | the item id, e.g. `minecraft:diamond_sword` |
| `count` | number ≥ 1 | `1` | stack size |
| `slot` | number 0–35, or one of `head`, `chest`, `legs`, `feet`, `offhand` | first free slot | where the entry goes |
| `enchantments` | array | empty | enchantments applied to the stack |
| `potion` | string | none | potion id stored in the item's `potion_contents`, for potions |

Each enchantment:

| Field | Type | Meaning |
|---|---|---|
| `id` | string | enchantment id, e.g. `minecraft:sharpness` |
| `level` | number ≥ 1 | the level |

Rules the parser enforces:

- `items` has to be there and has to be an array; every element has to be an object.
- `item` and `count` must be a string and a number of the right kind.
- An enchantment level below 1 is refused, and so is a `count` below 1.
- An unknown slot name, or an inventory index outside 0–35, is refused.
- Item, potion and enchantment ids are resolved when the stack is built, not when the file is read,
  so a kit file can be parsed without a running game. An id that is not in the registry then fails
  with `no such item: <id>` when the kit is handed out.

`slot` distinguishes the two kinds of position by type: a number is a main-inventory index, a
string is the name of a position. That is why `"chest"` (the chestplate) and `0` are both fixed
slots while a missing `slot` is automatic.

### Saved kits: finished stacks

`/bot kit save <name>` does not write item descriptions. It stores the finished stacks, so a custom
name, damage, a written book, or any other data component survives the trip through JSON:

```json
{
  "name": "mykit",
  "items": [
    { "slot": 0, "stack": { "id": "minecraft:diamond_sword", "count": 1, "components": { ... } } },
    { "slot": "offhand", "stack": { "id": "minecraft:shield", "count": 1 } }
  ]
}
```

`stack` is Minecraft's own item codec, so it carries every component the item has. `slot` is
written the same way as above. A saved kit always has an explicit slot for every entry, because
`capture()` records where each stack actually was.

## Which shape a file is read as

A file in `<world>/carpet-kits/` may be written in **either** shape. The two are told apart by their
entries: an entry that carries a `stack` is a saved kit and keeps the finished stack it was written
with, and any other entry is read as a hand-written item description, the same way the five built-in
kits in `src/main/resources/assets/carpet/kits/` are. So the description form above can be dropped
into the world's folder and it works, and so can a file `/bot kit save` wrote out.

The shapes can even be mixed inside one file, entry by entry.

## Where custom kits live

```
<world>/carpet-kits/<name>.json
```

One file per kit, named after the kit. `/bot kit save` creates the folder if it is not there.

- `/bot kit save` writes the file immediately and updates the in-memory copy as it writes, so there
  is nothing to reload afterwards.
- The folder is read when the store is built, which happens the first time anything asks for it on a
  given server, and then cached for that server's lifetime.
- A file dropped in or edited by hand is picked up by `/bot kit reload`, which re-reads the folder
  and drops the problems of any kit that has gone. A fresh server does that for you at startup.
- Only files ending in `.json` are read. The name is the file name without the extension.

Names are restricted because a name doubles as a file name: 1 to 64 characters, letters, digits,
`_` and `-` only. `/bot kit save` says so and refuses anything else.

A custom kit with the same name as a built-in one wins — `get()` checks custom kits first — so you
can replace `sword` on your own server. `customNames()` and the `/bot kit delete` suggestions only
ever list custom kits, so a built-in name cannot be deleted.

`/bot kit list` also reports kits that would not load, with the reason:

```
Kits: sword, axe, smp, mace, crystal, mykit
Kit mykit cannot be used: cannot read the kit: a kit needs an 'items' array
```

The command's return value is 1 when every kit loaded and 0 when any did not. A kit with a problem is
still listed by name; it just cannot be given out.

## The inventory save and restore guarantee

`/bot kit give` takes the player's inventory away. Here is exactly what is kept and what is not.

**What is snapshotted**, the first time a kit is given to a player:

| Part of the inventory | Kept |
|---|---|
| All 36 main inventory slots (0–35), including the hotbar | yes |
| Helmet, chestplate, leggings, boots | yes |
| Offhand | yes |
| The selected hotbar slot | yes |
| Enchantments, damage, custom names, every data component | yes, by copying the stack |

**The guarantee:**

1. Every slot of the kit is worked out before the player is touched. A kit that does not fit in the
   inventory is refused with the player's own items still in place.
2. The snapshot is taken before anything is cleared.
3. **The first snapshot wins.** Giving a second kit does not overwrite what the player owned before
   the first one, so `restore` always returns them to where they started.
4. `/bot kit restore` puts every slot back and restores the selected hotbar slot, then removes the
   snapshot. A second `restore` reports "has no stored inventory to restore".
5. Snapshots live in memory for the server session. They are not written to disk: a player who logs
   out and back in still has the kit on and keeps their snapshot for that session, but a restart
   clears it. A kit has to be saved as a kit if it should outlive the session.
6. Clearing an inventory only empties it. Items a player drops or a mob picks up during a kit are
   gone with everything else — the snapshot is the only way back.

Give and restore work on fake players and real players alike. `give` reports per player, and only
succeeds overall when every target got the kit:

```
Gave kit sword to Bot1
Could not give kit sword to Steve: kit sword does not fit in the inventory
```