# Sword blocking

`swordBlockHitting` brings 1.8-style sword blocking to modern Minecraft versions. Holding the use key
with a sword raises it into a block: damage and knockback taken during the window are reduced.

```
/carpet swordBlockHitting true
```

It is off by default. Turning it on changes swords everywhere on the server, not just for bots: a real
player pressing use with a sword now blocks.

## The rules

| Rule | Type | Default | What it does |
|---|---|---|---|
| `swordBlockHitting` | bool | `false` | Master switch. Nothing below happens while it is off. |
| `swordBlockWindowTicks` | int | `6` | How long the block window lasts after the use key goes down, in ticks. |
| `swordBlockDamageMultiplier` | double | `0.5` | Damage taken while blocking is multiplied by this. 0 to 1. |
| `swordBlockKnockbackMultiplier` | double | `0.5` | Knockback taken while blocking is multiplied by this. 0 to 1. |

At the defaults, a hit that would do 6 damage does 3, and the knockback is halved. Setting the damage
multiplier to `1.0` and the window to `0` gives an animation with no effect.

## What the server does

### Opening the window

The window opens when the server sees a use-item packet from a player holding a sword. There are
three ways in, and all three end in the same place:

| Trigger | Who |
|---|---|
| A `ServerboundUseItemPacket` — pressing use | any player, vanilla client included |
| A `ServerboundUseItemOnPacket` — using the sword on a block | any player |
| `carpet:sword_block_request` — the mod client's own request packet | a client with the mod |

For any of them the server:

1. checks `swordBlockHitting` is on and the hand holds something in the `minecraft:swords` tag,
2. sets the block window to `swordBlockWindowTicks`,
3. starts the player using the item if they were not already,
4. tells the clients within 64 blocks that this player is now blocking, with the number of ticks left.

A sword is also made usable at all by this rule: `ItemStack.use` on a sword starts using it and
returns `CONSUME`, and its use duration becomes 72000 ticks instead of nothing. So right-clicking
with a sword raises it and it stays raised until you let go — no client mod needed for that.

### The window ticking down

The counter is ticked in `Player.tick`. When it reaches zero the server clears it and sends a
`carpet:sword_block` packet with zero ticks, so the clients stop drawing the block pose.

### Damage

While the player is using a sword, `Player.hurtServer` intercepts any incoming damage:

- the amount is multiplied by `swordBlockDamageMultiplier`,
- the player's invulnerability is raised to at least 5 ticks.

Nothing else changes. Armour, resistance and enchantments still apply to the reduced amount, and the
damage type is untouched.

### Knockback

`LivingEntity.getKnockback` is replaced with the same calculation the game already does — the attack
knockback attribute, modified by the weapon's knockback enchantments — and the result is multiplied by
`swordBlockKnockbackMultiplier` when the **victim** is inside a block window.

## What a client sees

### A client with the mod

This is the full experience.

**Other players, including fake players.** The server sends `carpet:sword_block` to every player in
the level within 64 blocks whenever a window opens or closes. The client stores the remaining ticks
per entity id and forces the `BLOCK` arm pose while the window is open and the player is not already
holding the sword up for real. Fake players have no client of their own, so this is the only way their
block is ever visible — the pose appears for everyone watching.

**Your own first-person view.** While you hold a sword and are either pressing the use key or inside a
block window, the sword is rolled about 60° towards the centre of the screen and dropped slightly, with
the motion easing in and out over the window. It only affects the main hand.

**The mod client also sends `carpet:sword_block_request`** on the rising edge of the use key when a
sword is held. That is a second route to the same server-side window, for the cases where the normal
use packet is not what you want to rely on.

### A vanilla client

The gameplay works. The server does all the damage and knockback maths, and 26.x clients send the
use-item packet on any use press, so a vanilla player pressing use with a sword opens a real block
window and gets the real damage reduction.

What it does not have is the mod's rendering:

- The block pose does not appear. Vanilla decides the arm pose from the **client's own** copy of the
  item's use animation, and a vanilla client still says a sword has none. The player keeps the normal
  item pose in the hand.
- The first-person sword is not rolled.
- The `carpet:sword_block` packet is ignored, so a **fake** player holding a sword up never looks like
  it is blocking to a vanilla client, even though the damage reduction applies to it.

So: put the mod on the clients that need to *see* blocking. Gameplay does not need it.

## Doing it with a bot

```
/bot kit give Bot1 sword
/player Bot1 use continuous
```

A fake player holding a sword with `use` running is `isUsingItem()`, so it gets the damage reduction.
This is what the self-test scenario `sword_block` checks: with the rule on, the blocking player loses
`swordBlockDamageMultiplier` of a fixed 4-health hit; with the rule off, they lose all of it, and an
idle player loses all of it either way.

Two things a fake player does **not** get, because the block window is only ever opened by a packet a
real client can send:

- the knockback reduction, which is keyed on the window rather than on `isUsingItem()`,
- the animation, since nobody is told about it.

To see the full behaviour in-game, use a real client with the mod.

CarpetLogic has a node for this too: `Combat/SwordBlock`, which needs `swordBlockHitting` to be on.

```
/carpetlogic programs run preset_blockhit Bot1
```

See [CarpetLogic.md](CarpetLogic.md).

## Turning the rest of 1.8 on

`swordBlockHitting` is the blocking half. Two more rules cover the other half of 1.8 combat:

| Rule | Default | What it does |
|---|---|---|
| `spamClickCombat` | `false` | Removes the attack cooldown so spam clicking works. With it off, `/player <name> attack` skips any swing below 90% attack strength, and a crit bot cannot crit faster than the cooldown allows. |
| `shieldStunning` | `false` | Lets damage through straight after a shield is disabled, instead of the player being invulnerable for 20 ticks. This is what makes a shield-break axe hit followed by a sword hit land. |

`damageTickOverrides` is a separate set of seven rules for tuning how many ticks of invulnerability each
kind of hit gives. They are listed in [Rules.md](Rules.md#18-style-combat).

## Related pages

- [Rules.md](Rules.md) — every rule this fork added
- [FakePlayers.md](FakePlayers.md) — `/player <name> use`, crits and the combat AI
- [SelfTest.md](SelfTest.md) — the `sword_block` scenario that covers this
- [CarpetLogic.md](CarpetLogic.md) — the `Combat/SwordBlock` node