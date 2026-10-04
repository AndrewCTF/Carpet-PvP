# The SMP duel

`smp_duel` measures the claim the whole SMP style rests on: a bot that heals, re-totems and keeps its
buffs up beats a sword bot that does none of that in the same kit, and a clear majority of the time.

It does not pass. This page is what it measures, what the rounds came out at, and what is left to find.

## What the scenario does

Eight rounds. Both fighters are expert bots in the `smp` kit, totems and shield included, on the same
ground with the same difficulty, and they alternate sides so neither always starts in front. They are put
back on their marks every second and a half, because a knockback that carries one of them out of reach
ends a round on the walk back rather than on the healing. A round is decided on a death, which is what
`EntityPlayerMPFake.diedTick()` records: a fake player is put back on one health and respawns a tick
later rather than staying dead, so nothing else about it says the fight was over. Nothing is counted for
the first ticks of a round, until both fighters are back on their feet.

Rounds may run 2400 ticks, which is what it takes to bring off a bot with two totems through a full set
of Protection IV netherite, where a landed netherite sword hit is worth about a heart.

## What the rounds came out at

On 26.3, eight rounds, in the order the changes below were made:

| Setup | SMP bot | Sword bot | Out of time |
|---|---|---|---|
| shields up on both sides, 1400 tick rounds | 2 | 1 | 5 |
| shields up on both sides, 2400 tick rounds | 1 | 4 | 3 |
| neither raises a shield, 2400 tick rounds | 4 | 4 | 0 |

The middle row is what the scenario measures with the kit as it is handed out, and it is a duel of
blocks: both bots hold a shield up in front of an incoming hit, a sword hit from the front is stopped
entirely by a shield, and neither can break the other's because the kit has no axe in it. With the
shield play off, every round ends in a death and the two are level.

## What the SMP bot does with the rounds it loses

* It heals constantly — four gaps, three or four splashes and six buffs a round — and spends the window
  not hitting for: a gap is thirty two ticks and a splash throw is up to fifteen of aiming.
* It never pearls away, or gives up trying: `gave up PEARL: there was no throw that would have landed
  where it needed one`. The ballistics ladder now reaches as far as a sixth of the wanted distance, so
  a close range retreat throws, but it does not come up often.
* It never drops a cobweb or a bucket: see [SmpStyle.md](SmpStyle.md) for why the game refuses both.

## What was changed to get here

* `SurvivalPolicy`: what holding an item back is worth now falls as the bag fills, is capped at what
  the item returns, and is nothing at all while the forecast says the bot dies inside the window. Before
  that the reserve was worth *more* the fuller the bag was, and eight strong splashes cost more to spend
  than one gained, so the bot fought on to the last of it.
* `SmpAim.aheadPitch` aims a splash at the furthest spot ahead of the bot that a splash still covers,
  which is the shallowest throw and so the cheapest, where it used to aim at a spot half a block ahead
  and look steeply down for it.
* `SmpAim.PEEL_DISTANCE` reaches a sixth of the wanted distance, so a retreat from inside the target's
  reach throws instead of giving up.
* The duel scenario itself now counts deaths rather than the lowest health either side reached, waits
  for both fighters to be back on their feet before it counts anything, and reports the rounds.

## `smp_pearl_retreat`

Also rewritten and also not registered: a fake player's pearl behaves like a real player's, which a
throw from a standing start across three chunks on 26.3 settles (the bot ends up forty blocks away, which
is the throw), and the old scenario's "the pearl vanishes a third of the way" was its own four block
search box rather than anything the game does. The rewritten scenario reads the landing off the thrower
on the tick it is put somewhere else, but it does not pass reliably: with nothing to heal with the bot
either heals its way back to whole, runs out of the target's reach before the model decides to run, or is
starved of the simulation budget while it does, and on one version the throw lands well short of the
solve. It needs the retreat branch of `SmpStyle` to fire sooner, which is play work rather than scenario
work.

## What is left

The gap that decides it is offence lost while healing, and the two knobs for it are the model's
`COST_WEIGHT` (what a tick spent not hitting for is worth) and the throwing time in `SmpHands`. Raising
`COST_WEIGHT` to 0.2 does make the bot prefer a five tick splash to a thirty two tick apple, which is
what a player does, but it takes four of the unit tests with it that are about the eat rather than about
the throw, so it wants reworking rather than a number. Healing behind a shield while retreating, rather
than standing still for it, is the other half and is `SmpStyle` and `SmpHands` work.
