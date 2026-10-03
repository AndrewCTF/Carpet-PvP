package carpet.pvp.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Scored survival decision for a long fight. Every option is projected forward over the same
 * horizon, turned into a score, and the best one wins; {@link Decision#explain()} prints the table
 * so a caller can log why it chose. The action costs come from the jar where the jar has them
 * (32 ticks to eat or drink) and from the model otherwise.
 */
public final class SurvivalPolicy
{
    /** Everything the bot can decide to do. */
    public enum Action
    {
        KEEP_FIGHTING,
        EAT_GOLDEN_APPLE,
        EAT_ENCHANTED_GOLDEN_APPLE,
        THROW_HEALING_POTION,
        DRINK_BUFF,
        THROW_BUFF,
        SWAP_ARMOR,
        MEND_ARMOR,
        RETREAT,
        RE_ENGAGE
    }

    /** The buff the bot is carrying potions of. */
    public enum Buff
    {
        STRENGTH,
        SPEED,
        FIRE_RESISTANCE
    }

    /** One scored option. */
    public static final class Option
    {
        public final Action action;
        public final double score;
        public final String reason;

        Option(Action action, double score, String reason)
        {
            this.action = action;
            this.score = score;
            this.reason = reason;
        }

        @Override
        public String toString()
        {
            return String.format("%-24s %8.2f  %s", action, score, reason);
        }
    }

    /** The chosen action, every score behind it, and the totem bookkeeping. */
    public static final class Decision
    {
        public final Option chosen;
        public final Option[] options;
        /** The forecast no action was applied to, for logging. */
        public final HealthForecast forecast;
        /** True when a single incoming hit would kill, so nothing slow may be started. */
        public final boolean lethalBurst;
        /** True when a totem is carried, is not in the offhand yet, and a burst would kill. */
        public final boolean totemToOffhand;
        /** Ticks left before a popped totem may be replaced, from the retotem delay parameter. */
        public final int ticksToRetotem;

        Decision(Option chosen, Option[] options, HealthForecast forecast, boolean lethalBurst,
                 boolean totemToOffhand, int ticksToRetotem)
        {
            this.chosen = chosen;
            this.options = options;
            this.forecast = forecast;
            this.lethalBurst = lethalBurst;
            this.totemToOffhand = totemToOffhand;
            this.ticksToRetotem = ticksToRetotem;
        }

        /** The score an option got, or 0 when it was not offered. */
        public double scoreOf(Action action)
        {
            for (Option option : options)
            {
                if (option.action == action)
                {
                    return option.score;
                }
            }
            return 0.0;
        }

        /** Whether the option was offered at all. */
        public boolean offered(Action action)
        {
            for (Option option : options)
            {
                if (option.action == action)
                {
                    return true;
                }
            }
            return false;
        }

        /** The whole table, best first. */
        public String explain()
        {
            StringBuilder text = new StringBuilder();
            text.append("trough=").append(String.format("%.2f", forecast.minEffective()))
                    .append(" lethal=").append(forecast.ticksToLethal())
                    .append(" toTotem=").append(totemToOffhand ? "yes" : "no")
                    .append(" retotem=").append(ticksToRetotem).append('\n');
            for (Option option : options)
            {
                text.append(option).append('\n');
            }
            return text.toString();
        }
    }

    /**
     * Ticks a competent player waits after popping a totem before moving a fresh one into the
     * offhand again: one second.
     */
    public static final int DEFAULT_RETOTEM_DELAY = 20;

    /** Ticks to move an item onto another hotbar slot and ready it, the model's own estimate. */
    public static final int HOTBAR_SWAP_TICKS = 3;
    /** Ticks to swing at the opponent's feet with a primed potion, the model's own estimate. */
    public static final int THROW_AIM_TICKS = 2;
    /** Ticks to swap one armor piece in, the model's own estimate. */
    public static final int ARMOR_SWAP_TICKS = 2;
    /** Ticks for a thrown ender pearl to land and teleport the thrower. */
    public static final int PEARL_TRAVEL_TICKS = 8;

    /** Effective health the bot tries to keep in hand at the end of the horizon. */
    public static final float SAFETY_BUFFER = 6.0f;
    /** Enemy health the bot tries to knock down over the horizon. */
    public static final float ENEMY_BUFFER = 10.0f;
    /** Weight of the bot's own effective health buffer. */
    public static final double HEADROOM_WEIGHT = 1.0;
    /** Weight of the enemy's projected health. */
    public static final double PRESSURE_WEIGHT = 0.25;
    /** Penalty per tick the bot spends not swinging. */
    public static final double COST_WEIGHT = 0.1;
    /** Penalty per heart the bot is below the buffer while an action runs. */
    public static final double EXPOSURE_WEIGHT = 0.5;
    /** Applied once for an option whose projection runs out of health, plus the shortfall. */
    public static final double DYING_PENALTY = 1000.0;
    /**
     * What holding one consumable back is worth. A gap is worth more when few are left, so a bot
     * with two gaps does not spend one for a small gain and a bot with six does.
     */
    public static final double RESERVE_WEIGHT = 9.0;
    /** Value of not losing an armor point inside the horizon. */
    public static final double AVOIDED_BREAK = 1.0;
    /** Value of one point of restored durability. */
    public static final double MEND_WEIGHT = 0.02;
    /** Remaining durability under which a carried spare piece is worth equipping. */
    public static final int SWAP_ARMOR_THRESHOLD = 20;

    /** The state the policy scores against. */
    public static final class Inputs
    {
        public float health = 20.0f;
        /** Attributes.MAX_HEALTH, 20 by default, plus any Health Boost. */
        public float maxHealth = 20.0f;
        public float absorption;
        public Effects.Instance[] effects = new Effects.Instance[0];
        public Effects.Food food = new Effects.Food();
        /** Exhaustion added per tick, such as every landed swing at EXHAUSTION_ATTACK. */
        public float exhaustionPerTick;
        /** Health lost per tick once armour, Resistance and protection are applied, while trading. */
        public float incomingDamagePerTick;
        /** Health dealt to the enemy per tick, including the buffs already running. */
        public float outgoingDamagePerTick;
        /** Attributes.ATTACK_SPEED, used to spread a Strength bonus over the horizon. */
        public double attackSpeed = 1.6;
        public float armorValue;
        public float armorToughness;
        public float epf;
        /** Breach level on the opponent's weapon, which CombatMath.armorAbsorb subtracts. */
        public int breachLevel;
        /** Amplifier of the bot's own Resistance, or -1 when it has none. */
        public int resistanceAmplifier = -1;
        /** Raw damage of the opponent's next hit, before any of the bot's defences. */
        public float incomingHitDamage = 7.0f;
        /** Ticks between the bot learning of a hit and it landing. */
        public int reactionTicks = 6;
        public double distance = 4.0;
        /** The enemy's estimated effective health. */
        public float enemyHealth = 20.0f;
        public int horizonTicks = 60;
        public int goldenApples;
        public int enchantedGoldenApples;
        public int healingPotions;
        /** 0 for Potions.HEALING, 1 for Potions.STRONG_HEALING. */
        public int healingPotionAmplifier;
        public Buff buff = Buff.STRENGTH;
        public int buffPotions;
        public int buffPotionAmplifier;
        public int buffPotionTicks = Effects.POTION_STRENGTH_TICKS;
        public int experienceBottles;
        public int pearls;
        public int totems;
        public boolean totemInOffhand;
        /** Ticks since the last totem popped, or a large number when none has. */
        public int ticksSinceTotemPop = 1000;
        /** Ticks to wait after a pop before moving a fresh totem into the offhand. */
        public int retotemDelayTicks = DEFAULT_RETOTEM_DELAY;
        /** Durability already spent per piece, indexed by Durability.BOOTS, LEGS, CHEST, HEAD. */
        public int[] armorDamage = new int[Durability.PIECE_COUNT];
        /** Fresh spare per piece, or -1 when none is carried. */
        public int[] spareArmor = new int[Durability.PIECE_COUNT];
        /** Mending level per piece, 0 when the piece has none. */
        public int[] mending = new int[Durability.PIECE_COUNT];
        /** Material multiplier of the worn armor, Durability.NETHERITE_MULTIPLIER and friends. */
        public int armorMultiplier = Durability.NETHERITE_MULTIPLIER;
        public int unbreakingLevel;
        public int eatingCooldown;
        public int potionCooldown;
        public int armorCooldown;
        public int pearlCooldown;
        public int mendingCooldown;
        /** Whether the opponent is in reach and hitting the bot right now. */
        public boolean beingHit;
        /** Ticks to cross the current distance back into melee range. */
        public int travelTicks = 6;
        /** Ticks out of reach a pearl buys before the opponent can hit again. */
        public int breakTicks = PEARL_TRAVEL_TICKS + 6;

        public Inputs()
        {
            for (int piece = 0; piece < Durability.PIECE_COUNT; piece++)
            {
                spareArmor[piece] = -1;
            }
        }

        /** Remaining durability of a worn piece. */
        public int armorRemaining(int piece)
        {
            return Durability.maxDamage(piece, armorMultiplier) - armorDamage[piece];
        }
    }

    /**
     * Scores every action the state allows and returns the best one.
     */
    public Decision decide(Inputs in)
    {
        int horizon = Math.max(1, in.horizonTicks);
        int incomingDelay = in.beingHit || in.distance <= DuelSim.REACH ? 0 : in.travelTicks;
        HealthForecast baseline = HealthForecast.project(in.health, in.maxHealth, in.absorption, in.effects,
                in.food, in.exhaustionPerTick, in.incomingDamagePerTick, incomingDelay, horizon);
        List<Option> options = new ArrayList<>();
        if (in.beingHit || in.distance <= DuelSim.REACH)
        {
            options.add(score(in, Action.KEEP_FIGHTING, "trade at " + in.incomingDamagePerTick + " hp/tick",
                    baseline, 0, 0.0f, 0, 0.0f, 0,
                    in.enemyHealth - in.outgoingDamagePerTick * horizon));
        }

        if (in.eatingCooldown <= 0)
        {
            food(options, in, Action.EAT_GOLDEN_APPLE, Effects.GOLDEN_APPLE, in.goldenApples, horizon, baseline);
            food(options, in, Action.EAT_ENCHANTED_GOLDEN_APPLE, Effects.ENCHANTED_GOLDEN_APPLE,
                    in.enchantedGoldenApples, horizon, baseline);
        }
        if (in.healingPotions > 0 && in.potionCooldown <= 0)
        {
            int delay = HOTBAR_SWAP_TICKS + THROW_AIM_TICKS;
            float heal = Effects.instantHeal(1.0, in.healingPotionAmplifier);
            options.add(bump(score(in, Action.THROW_HEALING_POTION,
                    "splash healing at own feet for " + heal + " after " + delay + " ticks",
                    baseline, delay, 0.0f, 0, heal, delay,
                    in.enemyHealth - in.outgoingDamagePerTick * (horizon - delay)),
                    -reserve(in.healingPotions)));
        }
        if (in.buffPotions > 0 && in.potionCooldown <= 0)
        {
            buff(options, in, horizon, baseline, false);
            buff(options, in, horizon, baseline, true);
        }
        armor(options, in, horizon, baseline);
        if (in.pearls > 0 && in.pearlCooldown <= 0 && baseline.ticksToLethal() > 0)
        {
            int breakTicks = in.breakTicks;
            HealthForecast escaped = HealthForecast.project(in.health, in.maxHealth, in.absorption, in.effects,
                    in.food, in.exhaustionPerTick, in.incomingDamagePerTick, breakTicks, horizon);
            options.add(bump(score(in, Action.RETREAT,
                    "pearl away, " + in.pearls + " left, out of reach for " + breakTicks + " ticks",
                    escaped, 0, 0.0f, 0, 0.0f, HOTBAR_SWAP_TICKS, in.enemyHealth), -reserve(in.pearls)));
        }
        if (!in.beingHit && in.distance > DuelSim.REACH)
        {
            HealthForecast closing = HealthForecast.project(in.health, in.maxHealth, in.absorption, in.effects,
                    in.food, in.exhaustionPerTick, in.incomingDamagePerTick, in.travelTicks, horizon);
            options.add(score(in, Action.RE_ENGAGE,
                    "close " + in.travelTicks + " ticks and resume trading",
                    closing, 0, 0.0f, 0, 0.0f, in.travelTicks,
                    in.enemyHealth - in.outgoingDamagePerTick * (horizon - in.travelTicks)));
        }

        Option best = options.get(0);
        for (Option option : options)
        {
            if (option.score > best.score)
            {
                best = option;
            }
        }
        boolean lethalBurst = isLethalBurst(in, in.incomingHitDamage);
        int ticksToRetotem = Math.max(0, in.retotemDelayTicks - in.ticksSinceTotemPop);
        boolean totemToOffhand = in.totems > 0 && !in.totemInOffhand && lethalBurst;
        return new Decision(best, options.toArray(new Option[0]), baseline, lethalBurst, totemToOffhand, ticksToRetotem);
    }

    /**
     * Health lost to one hit of raw damage: armour and protection first, then the Resistance
     * multiplier that LivingEntity.getDamageAfterMagicAbsorb applies afterwards.
     */
    public float hitLoss(Inputs in, float rawDamage)
    {
        float afterDefences = CombatMath.damageAfterDefences(rawDamage, in.armorValue, in.armorToughness,
                in.breachLevel, in.epf);
        return Effects.resistanceFactor(in.resistanceAmplifier) * afterDefences;
    }

    /** True when a single hit of raw damage would take the bot below zero, totems aside. */
    public boolean isLethalBurst(Inputs in, float rawDamage)
    {
        return in.health + in.absorption - hitLoss(in, rawDamage) <= 0.0f;
    }

    private void food(List<Option> options, Inputs in, Action action, Effects.Consumable item, int carried,
                      int horizon, HealthForecast baseline)
    {
        if (carried <= 0)
        {
            return;
        }
        int regenAmplifier = item.amplifierOf(Effects.Effect.REGENERATION);
        int regenDuration = item.durationOf(Effects.Effect.REGENERATION);
        float regenTotal = item.regenerationTotal();
        float absorption = item.absorptionGranted(in.absorption);
        options.add(bump(score(in, action,
                "eat for " + regenTotal + " regen + " + absorption + " absorption over " + item.useTicks + " ticks",
                baseline, item.useTicks, regenTotal, regenDuration, absorption, item.useTicks,
                in.enemyHealth - in.outgoingDamagePerTick * (horizon - item.useTicks)), -reserve(carried)));
    }

    private void buff(List<Option> options, Inputs in, int horizon, HealthForecast baseline, boolean thrown)
    {
        int cost = thrown ? HOTBAR_SWAP_TICKS + THROW_AIM_TICKS : Effects.CONSUME_TICKS;
        int duration = in.buffPotionTicks;
        if (thrown)
        {
            duration = Effects.splashDuration(Effects.splashPower(in.distance * in.distance), duration, 1.0);
            if (duration <= 0)
            {
                return;
            }
        }
        String name = in.buff == Buff.STRENGTH ? "Strength" : in.buff == Buff.SPEED ? "Swiftness" : "Fire Resistance";
        double extraDamage = in.buff == Buff.STRENGTH
                ? Effects.strengthBonus(in.buffPotionAmplifier) * (horizon - cost) / CombatMath.fullChargeTicks(in.attackSpeed)
                : 0.0;
        Action action = thrown ? Action.THROW_BUFF : Action.DRINK_BUFF;
        String verb = thrown
                ? "throw " + name + " amplifier " + in.buffPotionAmplifier + " for " + duration + " ticks"
                : "drink " + name + " amplifier " + in.buffPotionAmplifier + " for " + duration + " ticks";
        options.add(bump(score(in, action, verb, baseline, cost, 0.0f, 0, 0.0f, cost,
                in.enemyHealth - in.outgoingDamagePerTick * (horizon - cost) - extraDamage),
                -reserve(in.buffPotions)));
    }

    private void armor(List<Option> options, Inputs in, int horizon, HealthForecast baseline)
    {
        if (in.armorCooldown > 0)
        {
            return;
        }
        float hitsPerTick = 1.0f / (float) CombatMath.fullChargeTicks(in.attackSpeed);
        float loss = (float) Durability.expectedLoss(in.incomingHitDamage, in.unbreakingLevel);
        int worst = -1;
        int worstRemaining = Integer.MAX_VALUE;
        for (int piece = 0; piece < Durability.PIECE_COUNT; piece++)
        {
            if (in.spareArmor[piece] < 0)
            {
                continue;
            }
            int remaining = in.armorRemaining(piece);
            if (remaining < worstRemaining)
            {
                worstRemaining = remaining;
                worst = piece;
            }
        }
        if (worst >= 0 && worstRemaining < SWAP_ARMOR_THRESHOLD)
        {
            double value = (in.spareArmor[worst] - worstRemaining) * MEND_WEIGHT / SWAP_ARMOR_THRESHOLD;
            if (loss * hitsPerTick * horizon >= worstRemaining)
            {
                value += AVOIDED_BREAK;
            }
            options.add(bump(score(in, Action.SWAP_ARMOR,
                    "swap piece " + pieceName(worst) + " with " + worstRemaining + " durability left",
                    baseline, 0, 0.0f, 0, 0.0f, ARMOR_SWAP_TICKS,
                    in.enemyHealth - in.outgoingDamagePerTick * (horizon - ARMOR_SWAP_TICKS)), value));
        }
        int mendable = -1;
        int mendNeed = 0;
        for (int piece = 0; piece < Durability.PIECE_COUNT; piece++)
        {
            if (in.mending[piece] <= 0 || in.armorDamage[piece] <= 0)
            {
                continue;
            }
            if (in.armorDamage[piece] > mendNeed)
            {
                mendNeed = in.armorDamage[piece];
                mendable = piece;
            }
        }
        if (mendable >= 0 && in.experienceBottles > 0 && in.mendingCooldown <= 0
                && baseline.minEffective() > SAFETY_BUFFER)
        {
            int bottles = Durability.bottlesFor(mendNeed);
            if (bottles <= in.experienceBottles)
            {
                double value = Math.min(AVOIDED_BREAK, bottles
                        * Durability.repairCapacity(Effects.EXPERIENCE_BOTTLE_MIN_XP) * MEND_WEIGHT);
                options.add(bump(score(in, Action.MEND_ARMOR,
                        bottles + " bottles restore piece " + pieceName(mendable) + " by " + mendNeed,
                        baseline, 0, 0.0f, 0, 0.0f, HOTBAR_SWAP_TICKS,
                        in.enemyHealth - in.outgoingDamagePerTick * (horizon - HOTBAR_SWAP_TICKS)), value));
            }
        }
    }

    /**
     * Scores one action. The health left after the action resolves decides how good it is, and the
     * health the bot is stuck with while the action runs decides how exposed it is. Splitting the two
     * is what lets a gap that heals 8 and one that heals 32 differ, even though both take 32 ticks.
     */
    private Option score(Inputs in, Action action, String reason, HealthForecast projection,
                         int delayTicks, float regenTotal, int regenDuration, float absorptionLump,
                         int costTicks, double enemyAfter)
    {
        float before = projection.effectiveAt(0);
        float trough = Float.MAX_VALUE;
        for (int tick = delayTicks; tick <= projection.ticks(); tick++)
        {
            float effective = projection.effectiveAt(tick) + absorptionLump;
            if (regenDuration > 0)
            {
                effective += regenTotal * Math.min(1.0, (tick - delayTicks) / (double) regenDuration);
            }
            trough = Math.min(trough, effective);
        }
        for (int tick = 0; tick < delayTicks; tick++)
        {
            before = Math.min(before, projection.effectiveAt(tick));
        }
        double score = HEADROOM_WEIGHT * (trough - SAFETY_BUFFER) + PRESSURE_WEIGHT * (enemyAfter - ENEMY_BUFFER)
                - COST_WEIGHT * costTicks
                - EXPOSURE_WEIGHT * Math.max(0.0, SAFETY_BUFFER - before);
        if (trough <= 0.0f || before <= 0.0f)
        {
            score = PRESSURE_WEIGHT * (enemyAfter - ENEMY_BUFFER) - COST_WEIGHT * costTicks
                    - DYING_PENALTY + Math.min(trough, before);
        }
        return new Option(action, score, reason);
    }

    /** What holding one more of a consumable back is worth; more when few are carried. */
    private static double reserve(int carried)
    {
        return RESERVE_WEIGHT * carried / (carried + 1.0);
    }

    /** Adds a bonus that is not a health projection, such as the value of not breaking a piece. */
    private static Option bump(Option option, double bonus)
    {
        return new Option(option.action, option.score + bonus, option.reason);
    }

    private static String pieceName(int piece)
    {
        return piece == Durability.BOOTS ? "boots" : piece == Durability.LEGS ? "leggings"
                : piece == Durability.CHEST ? "chestplate" : "helmet";
    }
}