# The SMP style

`smp` is a sword bot that knows the survival half of the game. The melee is the sword style used as it
is, because a netherite pot is still a sword fight; what this style adds is everything that decides a
long one: the golden apples, the splash at its own feet, the strength and the speed, the totem in the
offhand and the wait after it pops, the spare piece of armour, the bottles that mend it, the pearl that
buys room to heal in, the cobweb and the water.

```
/bot spawn Bot1 smp
/bot option Bot1 combatstyle smp
```

The kit is the `smp` one: a Sharpness V netherite sword, five golden apples, eight strong healing
splashes, five strong strength and three strong swiftness, three pearls, sixteen bottles, three cobwebs,
a water bucket, two totems, a full set of netherite armour in Protection IV with Unbreaking III and
Mending, and a shield in the offhand.

## How a decision is made

Every tick the style reads what the bot can perceive of the target and of itself, asks
`SurvivalPolicy` what to do about it, and then carries out whatever that is through its hands.

`SurvivalPolicy` scores every action the state allows over the same window — a golden apple, a splash
at the feet, a strength or a swiftness top-up, an armour swap, a mend, a pearl away — against the
action of simply carrying on trading. What decides is the health the bot is left on at the lowest point
of the window, what the window costs it in not hitting for, and, a little, how much the fight is
winning:

| Term | Weight | What it is |
|---|---|---|
| headroom | 1.0 per point | how far the lowest point of the window is above the six hearts it wants to keep |
| exposure | 0.5 per point | the same, for the part of the window before the action has landed |
| cost | 0.1 per tick | what spending those ticks not hitting for is worth |
| pressure | 0.25 per point | how far the enemy is left on at the end of the window |
| dying | 1000 | once for an action whose window runs out of health, plus the shortfall |

Nothing is held back for later while the forecast says the bot is going to die inside the window: the
reserve an item costs is what holding it back is worth, and it is never more than what the item returns.
The last item in the bag is worth the whole of `SurvivalPolicy.RESERVE_WEIGHT`, the second of two half
of it, and the eighth of a full kit rather less again, so what a heal costs to be hoarded falls away as
the bag fills and a bot that is carrying its kit heals earlier rather than later. A level score goes to
carrying on, so an item that gains nothing over trading is never spent on nothing.

The ballistics, the difficulty gates and the mapping from an answer to a thing a bot can hold are pure
arithmetic over the same inputs, so `SmpAimTest`, `SmpGatesTest`, `SmpPlanTest` and `SurvivalPolicyTest`
check what the bot would do without a game running.

## What the hands may do

Everything goes through the body: the hotbar change is the body's, which takes effect a tick later; the
view is the body's look controller, so turning onto the spot a throw has to land on costs the reaction
time and the mouse steps it would cost a player; the item use is the action pack's own `USE` action,
which is what a right click is. A bot can never do any of them sooner or more precisely than a player
holding the same items in the same slots, and it has one thing in flight at a time.

A throw that needs a spot other than where the bot already looks is aimed first, up to twenty ticks of
human view speed, and it is thrown the tick the view is on the spot and within four degrees of it. A
splash at the bot's own feet is the exception the task text points at: the furthest spot ahead of itself
a splash still covers is used, so the throw is shallow and costs the bot a fraction of the fight.

## What the game will not let it do

Three things a player cannot do either, which the options therefore have to be honest about.

**The shield around a heal.** A player holds one hand on the item it is eating or drinking and cannot
hold a shield in the same one, so the game only lets the offhand be used on the ticks the item is not.
`smp.guard` puts the shield up on exactly those ticks: while the view is still turning onto the spot a
throw has to land on, and again the tick the item has been spent. That is the whole of it: the bot is
unshielded for as long as the thirty two ticks of a gap take.

**The water bucket.** `BucketItem.use` needs a block face, and the bot looks straight down to find one:
the water goes onto the block it is standing on, at its own feet, which is what a player does to put out
a fire they are standing in or to break a fall. It is no use on a bot that is neither on fire nor falling
more than three blocks, which is when the style asks for it.

**The cobweb.** `BlockItem.useOn` needs a block face to place against, and a cobweb dropped at a player's
feet has none: the face it would go on is the body. The style aims the web at the target, so the game
refuses the placement and the web is never dropped. In practice an SMP bot does not use cobwebs at all;
`smp.web` is armed for ground where there is something to place against, and off everywhere else.

## Options

Set with `/bot option <name> <option> <value>` per bot. Each one is also gated by the difficulty
preset, which is a floor rather than a switch: a beginner eats an apple and little else, an expert
pearls away, tops its buffs up and drops a cobweb.

| Option | Values | Default | What it does |
|---|---|---|---|
| `smp.eat` | `true`/`false` | `true` | the golden apple and the enchanted one |
| `smp.splashheal` | `true`/`false` | `true` | the splash healing at its own feet, from `average` |
| `smp.buff` | `true`/`false` | `true` | drinking and throwing strength and swiftness, from `skilled` |
| `smp.totem` | `true`/`false` | `true` | the totem in the offhand and the wait after a pop |
| `smp.armor` | `true`/`false` | `true` | the spare piece of armour, from `average` |
| `smp.mend` | `true`/`false` | `true` | the experience bottles, from `skilled` |
| `smp.pearl` | `true`/`false` | `true` | the pearl that buys room to heal in, from `skilled` |
| `smp.web` | `true`/`false` | `true` | the cobweb, from `average`: see above, the game refuses most of them |
| `smp.bucket` | `true`/`false` | `true` | the water bucket, from `average` |
| `smp.guard` | `true`/`false` | `true` | the shield between uses, from `average` |
| `smp.retotem` | ticks | `20` | how long it waits after a totem pops before a fresh one goes in |
| `smp.buffwindow` | ticks | `240` | how long before a buff runs out it is topped up |
| `smp.peelback` | blocks | `12` | how far a retreat throw aims for |

`/bot stats` reports what a bot's hands have done: apples, splashes, buffs, bottles, pearls, cobwebs,
buckets, armour swaps and totems.

## What the self-test covers

| Scenario | What it asserts |
|---|---|
| `smp_heals` | a bot put down to a few hearts heals with what it is carrying and goes back to hitting |
| `smp_retotem` | a lethal hit pops the totem and a fresh one is in the offhand after the configured wait |
| `smp_buffs` | strength handed out with a command is replaced before it runs out |
| `smp_pearl_retreat` | written but not registered, see [SmpDuel.md](SmpDuel.md) |
| `smp_duel` | not registered: see [SmpDuel.md](SmpDuel.md) |

## Related pages

- [FakePlayers.md](FakePlayers.md) — the combat AI and every other style
- [Kits.md](Kits.md) — the kits and what they carry
- [SelfTest.md](SelfTest.md) — what the headless run checks
