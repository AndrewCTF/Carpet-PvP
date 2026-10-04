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
| `swordBlockWindowTicks` | int | `6` | How long the block window lasts after the use key goes down, in ticks. It governs the knockback reduction and the pose clients draw, not the damage reduction. |
| `swordBlockDamageMultiplier` | double | `0.5` | Damage taken while blocking is multiplied by this. 0 to 1. |
| `swordBlockKnockbackMultiplier` | double | `0.5` | Knockback taken while blocking is multiplied by this. 0 to 1. |

At the defaults, a hit that would do 6 damage does 3, and the knockback is halved.

The two halves are governed differently, which is worth knowing before tuning them. The damage
reduction follows the sword being *in use*, whatever the window is; the knockback reduction and the
pose follow the window counter and need it to be above zero. So setting the window to `0` and
leaving the damage multiplier at `0.5` leaves a player who is holding use with a sword taking half
damage and being pushed as far as anyone else. To get an animation with no effect, set both
multipliers to `1.0`.

## What the server does

### Opening the window

The window opens when the server sees a use-item packet from a player holding a sword. There are
four ways in, and all four end in the same place:

| Trigger | Who |
|---|---|
| A `ServerboundUseItemPacket` — pressing use | any player, vanilla client included |
| A `ServerboundUseItemOnPacket` — using the sword on a block | any player |
| `carpet:sword_block_request` — the mod client's own request packet | a client with the mod |
| The `use` action of a fake player | a fake player, once the sword is in use |

For any of them the server:

1. checks `swordBlockHitting` is on and the hand holds something in the `minecraft:swords` tag,
2. sets the block window to `swordBlockWindowTicks`,
3. starts the player using the item if they were not already,
4. tells the clients within 64 blocks that this player is now blocking, with the number of ticks left.

Step 4 is skipped entirely when `superSecretSetting` is on, so nobody is told about any block window
while the damage and knockback rules keep applying.

Step 4 only happens when the window was closed before, so topping the window up while a sword stays
raised does not repeat the packet every tick.

A sword is also made usable at all by this rule: `ItemStack.use` on a sword starts using it and
returns `CONSUME`, its use duration becomes 72000 ticks instead of nothing, and its use animation is
forced to `BLOCK`. That last one is what makes the server-side using state a real block rather than a
made-up flag, and it is why a vanilla client's own arm-pose code still says a sword has no use
animation: that mixin is not on the vanilla client. So right-clicking with a sword raises it and it
stays raised until you let go — no client mod needed for that.

### The window ticking down

The counter is ticked in `Player.tick`. When it reaches zero the server clears it and sends a
`carpet:sword_block` packet with zero ticks, so the clients stop drawing the block pose. That is what
`swordBlockWindowTicks` is for: it is how long the block lasts after the use key comes up. A fake
player's `use` action tops the window back up every tick the sword is in use, so its block ends
`swordBlockWindowTicks` after the use stops, the same as a client's.

### Damage

While the player is using a sword, `Player.hurtServer` intercepts any incoming damage:

- the amount is multiplied by `swordBlockDamageMultiplier`,
- the player's invulnerability is raised to at least 5 ticks.

Nothing else changes. Armour, resistance and enchantments still apply to the reduced amount, and the
damage type is untouched.

### Knockback

The knockback a hit deals is scaled by `swordBlockKnockbackMultiplier` when the **victim** is inside a
block window.

There are two paths the game takes and both are covered. Which one a melee hit uses depends on the
Minecraft version, which is why the mixin that scales it is written twice:

- On 26.1 and up, a melee, projectile or explosion hit is pushed by `LivingEntity.dealDefaultKnockback`,
  which works out its own 0.4 and calls `knockback` with it. The multiplier is applied to that
  strength, so this is what a sword hit from another player uses.
- On 1.21.11 the same thing happens inside `hurtServer`, where the knockback argument is scaled.
  There is no `dealDefaultKnockback` to hook on that version.
- `LivingEntity.getKnockback` — the attack knockback attribute, modified by the weapon's knockback
  enchantments — is scaled the same way on every version. Since 26.x the attack knockback attribute is
  0 for players, so on those versions this path is what a mace's stab and a mob's ram use, and
  nothing else.

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
the motion easing in and out. It only affects the main hand, and the roll goes the other way for a
left-handed player. The ease is computed against a six-tick window, so lengthening
`swordBlockWindowTicks` lengthens the hold but not the ease.

**The mod client also sends `carpet:sword_block_request`** on the rising edge of the use key when a
sword is held — main hand first, then off hand, and not at all if neither holds a sword. That is a
second route to the same server-side window, for the cases where the normal use packet is not what
you want to rely on.

The remaining ticks are kept in a plain map on the client, keyed by entity id, and the local player's
own view reads the same map.

### A vanilla client

The gameplay works. The server does all the damage and knockback maths, and 26.x clients send the
use-item packet on any use press, so a vanilla player pressing use with a sword opens a real block
window and gets the real damage reduction.

What it does not have is the mod's rendering:

- The block pose does not appear for another player. Vanilla decides the arm pose from the **client's
  own** copy of the item's use animation, and a vanilla client still says a sword has none. The player
  keeps the normal item pose in the hand. The local player's own arm does go up, because the server
  puts them in the using state that vanilla already knows how to draw.
- The first-person sword is not rolled.
- The `carpet:sword_block` packet is ignored, so a **fake** player holding a sword up never looks like
  it is blocking to a vanilla client, even though the damage and knockback reduction both apply to it.

So: put the mod on the clients that need to *see* blocking. Gameplay does not need it.

## Doing it with a bot

```
/bot kit give Bot1 sword
/player Bot1 use continuous
```

A fake player holding a sword with `use` running gets the whole thing: the `USE` action opens the
block window exactly as the use-item packet a client sends does, and keeps it open for as long as the
item stays in use. That gives it the damage reduction, the knockback reduction and the
`carpet:sword_block` broadcast, so it blocks in front of a real player the same way a real player
does. The self-test scenario `sword_block` checks this: with the rule on, the blocking player loses
`swordBlockDamageMultiplier` of a fixed 4-health hit and is pushed to between a quarter and three
quarters of what an idle player is pushed by, and with the rule off they lose all of the damage and
are pushed as far as anyone.

The one thing a fake player still cannot do is *let go* of a block mid-window the way a client can:
releasing use closes the window after `swordBlockWindowTicks`, exactly as a real player's does.

CarpetLogic has a node for this too: `Combat/SwordBlock`, which needs `swordBlockHitting` to be on.

```
/carpetlogic programs run preset_blockhit Bot1
```

See [CarpetLogic.md](CarpetLogic.md). A `SWORD_BLOCK` step is skipped while a combat node of a
program owns the bot, so a program that is fighting does not also try to block by hand.

## Turning the rest of 1.8 on

`swordBlockHitting` is the blocking half. Two more rules cover the other half of 1.8 combat:

| Rule | Default | What it does |
|---|---|---|
| `spamClickCombat` | `false` | Removes the attack cooldown so spam clicking works. With it off, `/player <name> attack` skips any swing below 90% attack strength, and a crit bot cannot crit faster than the cooldown allows. |
| `shieldStunning` | `false` | Lets damage through straight after a shield is disabled, instead of the player being invulnerable for 20 ticks. This is what makes a shield-break axe hit followed by a sword hit land. |

`damageTickOverrides` is a separate set of seven rules for tuning how many ticks of invulnerability each
kind of hit gives. They are listed in [Rules.md](Rules.md#18-style-combat).

## Related pages

- [Rules.md](Rules.md#18-style-combat) — every rule this fork added
- [FakePlayers.md](FakePlayers.md) — `/player <name> use`, crits and the combat AI
- [Bots.md](Bots.md) — the bots that block and break shields
- [Practice.md](Practice.md) — the `stunslam` drill, which is this mechanic in reverse
- [SelfTest.md](SelfTest.md) — the `sword_block` scenario that covers this
- [CarpetLogic.md](CarpetLogic.md) — the `Combat/SwordBlock` node
- [Paper.md](Paper.md) — the Paper plugin build, where the mixins differ