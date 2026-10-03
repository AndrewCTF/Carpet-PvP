package carpet.pvp.sim;

/**
 * Status effect, consumable and food tables. Every number is the one the 26.3 game jar uses; the
 * comment on each member names the class and member it was read from.
 */
public final class Effects
{
    /** MobEffects the survival model needs. */
    public enum Effect
    {
        SPEED,
        SLOWNESS,
        STRENGTH,
        WEAKNESS,
        HASTE,
        REGENERATION,
        RESISTANCE,
        FIRE_RESISTANCE,
        ABSORPTION,
        HEALTH_BOOST,
        INSTANT_HEALTH,
        INSTANT_DAMAGE
    }

    /** One status effect: which one, at which amplifier, and how many ticks are left. */
    public static final class Instance
    {
        public final Effect effect;
        /** 0 is level I, so the game adds (amplifier + 1) levels. */
        public final int amplifier;
        public int duration;

        public Instance(Effect effect, int amplifier, int duration)
        {
            this.effect = effect;
            this.amplifier = amplifier;
            this.duration = duration;
        }

        public Instance copy()
        {
            return new Instance(effect, amplifier, duration);
        }
    }

    /** An item the bot can use: how long it takes and what it grants. */
    public static final class Consumable
    {
        public final String name;
        /** Ticks of continuous use; 0 when the item is applied instantly. */
        public final int useTicks;
        public final Instance[] effects;
        /** Foods.* nutrition, the first FoodData.add argument. */
        public final int nutrition;
        /** Foods.* saturationModifier, the second FoodConstants.saturationByModifier argument. */
        public final float saturationModifier;

        Consumable(String name, int useTicks, int nutrition, float saturationModifier, Instance[] effects)
        {
            this.name = name;
            this.useTicks = useTicks;
            this.nutrition = nutrition;
            this.saturationModifier = saturationModifier;
            this.effects = effects;
        }

        /** Amplifier the item grants for the effect, or -1 when it does not grant it. */
        public int amplifierOf(Effect effect)
        {
            for (Instance instance : effects)
            {
                if (instance.effect == effect)
                {
                    return instance.amplifier;
                }
            }
            return -1;
        }

        /** Duration the item grants for the effect, or 0 when it does not grant it. */
        public int durationOf(Effect effect)
        {
            for (Instance instance : effects)
            {
                if (instance.effect == effect)
                {
                    return instance.duration;
                }
            }
            return 0;
        }

        /** Regeneration health the item grants over its whole duration, ignoring the health cap. */
        public float regenerationTotal()
        {
            int amplifier = amplifierOf(Effect.REGENERATION);
            return amplifier < 0 ? 0.0f : regenerationTicks(durationOf(Effect.REGENERATION), amplifier);
        }

        /** AbsorptionMobEffect.onEffectStarted takes the larger of the current amount and 4 * (1 + amplifier). */
        public float absorptionGranted(float current)
        {
            int amplifier = amplifierOf(Effect.ABSORPTION);
            return amplifier < 0 ? 0.0f : Math.max(current, absorptionOnStart(amplifier));
        }

        /** Saturation the food adds: FoodConstants.saturationByModifier is nutrition * modifier * 2.0f. */
        public float saturationGained()
        {
            return saturationByModifier(nutrition, saturationModifier);
        }

        /** Regeneration plus absorption the item hands over, ignoring what is already there. */
        public float totalHealing(float currentAbsorption)
        {
            return regenerationTotal() + absorptionGranted(currentAbsorption);
        }
    }

    private Effects()
    {
    }

    /** MobEffects.SPEED: MOVEMENT_SPEED, ADD_MULTIPLIED_TOTAL, 0.2 per level. */
    public static double speedBonus(int amplifier)
    {
        return 0.2 * (amplifier + 1);
    }

    /** MobEffects.SLOWNESS: MOVEMENT_SPEED, ADD_MULTIPLIED_TOTAL, -0.15 per level. */
    public static double slownessPenalty(int amplifier)
    {
        return -0.15 * (amplifier + 1);
    }

    /** MobEffects.STRENGTH: ATTACK_DAMAGE, ADD_VALUE, 3.0 per level. */
    public static double strengthBonus(int amplifier)
    {
        return 3.0 * (amplifier + 1);
    }

    /** MobEffects.WEAKNESS: ATTACK_DAMAGE, ADD_VALUE, -4.0 per level. */
    public static double weaknessPenalty(int amplifier)
    {
        return -4.0 * (amplifier + 1);
    }

    /** MobEffects.HASTE: ATTACK_SPEED, ADD_MULTIPLIED_TOTAL, 0.1 per level. */
    public static double hasteBonus(int amplifier)
    {
        return 0.1 * (amplifier + 1);
    }

    /** MobEffects.HEALTH_BOOST: MAX_HEALTH, ADD_VALUE, 4.0 per level on the Attributes.MAX_HEALTH default of 20. */
    public static double maxHealthBonus(int amplifier)
    {
        return 4.0 * (amplifier + 1);
    }

    /** AbsorptionMobEffect.onEffectStarted: setAbsorptionAmount(max(current, 4 * (1 + amplifier))). */
    public static float absorptionOnStart(int amplifier)
    {
        return 4.0f * (amplifier + 1);
    }

    /** MobEffects.ABSORPTION: MAX_ABSORPTION, ADD_VALUE, 4.0 per level; Attributes.MAX_ABSORPTION defaults to 0. */
    public static double maxAbsorptionBonus(int amplifier)
    {
        return 4.0 * (amplifier + 1);
    }

    /**
     * The damage multiplier Resistance leaves, from LivingEntity.getDamageAfterMagicAbsorb:
     * {@code damage = max(damage * (25 - 5 * (amplifier + 1)) / 25, 0)}, applied after armour and
     * before protection. Resistance V therefore gives 0.
     */
    public static float resistanceFactor(int amplifier)
    {
        if (amplifier < 0)
        {
            return 1.0f;
        }
        int kept = 25 - 5 * (amplifier + 1);
        return kept <= 0 ? 0.0f : kept / 25.0f;
    }

    /** RegenerationMobEffect.shouldApplyEffectTickThisTick fires when {@code duration % (50 >> amplifier) == 0}. */
    public static int regenerationInterval(int amplifier)
    {
        int shift = 50 >> Math.max(0, amplifier);
        return shift > 0 ? shift : 1;
    }

    /** Health Regeneration returns per tick at the given amplifier. */
    public static double regenerationPerTick(int amplifier)
    {
        return 1.0 / regenerationInterval(amplifier);
    }

    /**
     * Ticks a fresh Regeneration of the given duration actually fires, counting the multiples of
     * {@link #regenerationInterval} in [1, duration] that MobEffectInstance.tickServer tests.
     */
    public static int regenerationTicks(int duration, int amplifier)
    {
        int interval = regenerationInterval(amplifier);
        return duration <= 0 ? 0 : duration / interval;
    }

    /** HealOrHarmMobEffect.applyInstantaneousEffect heal branch: {@code (int) (power * (4 << amplifier) + 0.5)}. */
    public static int instantHeal(double power, int amplifier)
    {
        return (int) (power * (4 << Math.max(0, amplifier)) + 0.5);
    }

    /** HealOrHarmMobEffect.applyInstantaneousEffect harm branch: {@code (int) (power * (6 << amplifier) + 0.5)}. */
    public static int instantHarm(double power, int amplifier)
    {
        return (int) (power * (6 << Math.max(0, amplifier)) + 0.5);
    }

    /**
     * ThrownSplashPotion.onHitAsPotion: {@code power = 1 - sqrt(distanceSqr) / 4}, and an entity further
     * than 4 blocks away (distanceSqr >= 16) is not affected at all.
     */
    public static double splashPower(double distanceSqr)
    {
        return distanceSqr < 16.0 ? 1.0 - Math.sqrt(distanceSqr) / 4.0 : 0.0;
    }

    /**
     * ThrownSplashPotion.onHitAsPotion duration scaling: {@code (int) (power * duration * scale + 0.5)},
     * and the effect is skipped when MobEffectInstance.endsWithin(20) is true.
     */
    public static int splashDuration(double power, int duration, double scale)
    {
        int scaled = (int) (power * duration * scale + 0.5);
        return scaled <= 20 ? 0 : scaled;
    }

    /** Potions.STRENGTH and Potions.LONG_STRENGTH durations at amplifier 0. */
    public static final int POTION_STRENGTH_TICKS = 3600;
    public static final int POTION_LONG_STRENGTH_TICKS = 9600;
    /** Potions.STRONG_STRENGTH: 1800 ticks at amplifier 1. */
    public static final int POTION_STRONG_STRENGTH_TICKS = 1800;
    /** Potions.SWIFTNESS and Potions.LONG_SWIFTNESS durations at amplifier 0. */
    public static final int POTION_SWIFTNESS_TICKS = 3600;
    public static final int POTION_LONG_SWIFTNESS_TICKS = 9600;
    /** Potions.STRONG_SWIFTNESS: 1800 ticks at amplifier 1. */
    public static final int POTION_STRONG_SWIFTNESS_TICKS = 1800;
    /** Potions.FIRE_RESISTANCE and Potions.LONG_FIRE_RESISTANCE durations. */
    public static final int POTION_FIRE_RESISTANCE_TICKS = 3600;
    public static final int POTION_LONG_FIRE_RESISTANCE_TICKS = 9600;
    /** Potions.REGENERATION, LONG_REGENERATION 1800, STRONG_REGENERATION 450 at amplifier 1. */
    public static final int POTION_REGENERATION_TICKS = 900;

    /** Consumables.defaultFood and Consumables.defaultDrink both take 1.6 s, so 32 ticks. */
    public static final int CONSUME_TICKS = (int) (1.6f * 20.0f);

    /** Foods.GOLDEN_APPLE: 4 nutrition, 1.2 saturation modifier, always edible. */
    public static final Consumable GOLDEN_APPLE = new Consumable("golden_apple", CONSUME_TICKS, 4, 1.2f, new Instance[] {
        new Instance(Effect.REGENERATION, 1, 100),
        new Instance(Effect.ABSORPTION, 0, 2400)
    });

    /**
     * Consumables.ENCHANTED_GOLDEN_APPLE: 4 nutrition, 1.2 saturation modifier, always edible,
     * Regeneration II 400, Resistance I 6000, Fire Resistance I 6000 and Absorption IV 2400.
     */
    public static final Consumable ENCHANTED_GOLDEN_APPLE = new Consumable("enchanted_golden_apple", CONSUME_TICKS, 4, 1.2f,
            new Instance[] {
                new Instance(Effect.REGENERATION, 1, 400),
                new Instance(Effect.RESISTANCE, 0, 6000),
                new Instance(Effect.FIRE_RESISTANCE, 0, 6000),
                new Instance(Effect.ABSORPTION, 3, 2400)
            });

    /** Potions.HEALING: Instant Health for one tick at amplifier 0; drinkable in 32 ticks. */
    public static final Consumable HEALING_POTION = new Consumable("potion_healing", CONSUME_TICKS, 0, 0.0f,
            new Instance[] {new Instance(Effect.INSTANT_HEALTH, 0, 1)});

    /** Potions.STRONG_HEALING: Instant Health for one tick at amplifier 1; drinkable in 32 ticks. */
    public static final Consumable STRONG_HEALING_POTION = new Consumable("potion_strong_healing", CONSUME_TICKS, 0, 0.0f,
            new Instance[] {new Instance(Effect.INSTANT_HEALTH, 1, 1)});

    /** Potions.STRENGTH and LONG_STRENGTH, drunk in 32 ticks. */
    public static final Consumable STRENGTH_POTION = new Consumable("potion_strength", CONSUME_TICKS, 0, 0.0f,
            new Instance[] {new Instance(Effect.STRENGTH, 0, POTION_STRENGTH_TICKS)});

    /** Potions.STRONG_STRENGTH: amplifier 1 for 1800 ticks. */
    public static final Consumable STRONG_STRENGTH_POTION = new Consumable("potion_strong_strength", CONSUME_TICKS, 0, 0.0f,
            new Instance[] {new Instance(Effect.STRENGTH, 1, POTION_STRONG_STRENGTH_TICKS)});

    /** Potions.SWIFTNESS and LONG_SWIFTNESS, drunk in 32 ticks. */
    public static final Consumable SWIFTNESS_POTION = new Consumable("potion_swiftness", CONSUME_TICKS, 0, 0.0f,
            new Instance[] {new Instance(Effect.SPEED, 0, POTION_SWIFTNESS_TICKS)});

    /** Potions.FIRE_RESISTANCE and LONG_FIRE_RESISTANCE, drunk in 32 ticks. */
    public static final Consumable FIRE_RESISTANCE_POTION = new Consumable("potion_fire_resistance", CONSUME_TICKS, 0, 0.0f,
            new Instance[] {new Instance(Effect.FIRE_RESISTANCE, 0, POTION_FIRE_RESISTANCE_TICKS)});

    /**
     * DeathProtection.TOTEM_OF_UNDYING, applied by LivingEntity.checkTotemDeathProtection with no use
     * time: it sets health to 1.0f, clears every status effect and then applies these.
     */
    public static final Consumable TOTEM = new Consumable("totem_of_undying", 0, 0, 0.0f, new Instance[] {
        new Instance(Effect.REGENERATION, 1, 900),
        new Instance(Effect.ABSORPTION, 1, 100),
        new Instance(Effect.FIRE_RESISTANCE, 0, 800)
    });

    /** LivingEntity.checkTotemDeathProtection sets health to exactly this before applying DeathProtection effects. */
    public static final float TOTEM_HEALTH = 1.0f;

    /** DeathProtection.TOTEM_OF_UNDYING begins with ClearAllStatusEffectsConsumeEffect.INSTANCE. */
    public static final boolean TOTEM_CLEARS_EFFECTS = true;

    /** ThrownExperienceBottle.onHit: ExperienceOrb.awardWithDirection with {@code random.nextInt(5) + 5} points. */
    public static final int EXPERIENCE_BOTTLE_MIN_XP = 5;
    public static final int EXPERIENCE_BOTTLE_MAX_XP = 9;

    /** FoodConstants.MAX_FOOD. */
    public static final int MAX_FOOD = 20;
    /** FoodConstants.MAX_SATURATION, the clamp in FoodData.add. */
    public static final float MAX_SATURATION = 20.0f;
    /** FoodConstants.START_SATURATION, the FoodData constructor value. */
    public static final float START_SATURATION = 5.0f;
    /** FoodConstants.EXHAUSTION_DROP, the threshold in FoodData.tick. */
    public static final float EXHAUSTION_DROP = 4.0f;
    /** FoodConstants.HEALTH_TICK_COUNT, the timer of the unsaturated and starvation branches. */
    public static final int HEALTH_TICK_COUNT = 80;
    /** FoodConstants.HEALTH_TICK_COUNT_SATURATED, the timer of the saturated branch. */
    public static final int HEALTH_TICK_COUNT_SATURATED = 10;
    /** FoodConstants.HEAL_LEVEL, the food level the unsaturated branch needs. */
    public static final int HEAL_LEVEL = 18;
    /** FoodConstants.STARVE_LEVEL, the food level below which starvation damage starts. */
    public static final int STARVE_LEVEL = 0;
    /** FoodConstants.EXHAUSTION_HEAL, the amount the saturated branch heals divided by 6 and also spends. */
    public static final float EXHAUSTION_HEAL = 6.0f;
    /** FoodConstants.EXHAUSTION_ATTACK, spent by Player.attack on every landed swing. */
    public static final float EXHAUSTION_ATTACK = 0.1f;
    /** FoodConstants.EXHAUSTION_MINE, spent per block broken. */
    public static final float EXHAUSTION_MINE = 0.005f;
    /** FoodConstants.EXHAUSTION_JUMP. */
    public static final float EXHAUSTION_JUMP = 0.05f;
    /** FoodConstants.EXHAUSTION_SPRINT_JUMP. */
    public static final float EXHAUSTION_SPRINT_JUMP = 0.2f;
    /** FoodConstants.EXHAUSTION_SWIM. */
    public static final float EXHAUSTION_SWIM = 0.01f;
    /** FoodConstants.EXHAUSTION_WALK and FoodConstants.EXHAUSTION_CROUCH: walking and crouching cost nothing. */
    public static final float EXHAUSTION_WALK = 0.0f;
    /** The 1.0f FoodData.tick passes to ServerPlayer.heal in its unsaturated branch. */
    public static final float UNSATURATED_HEAL = 1.0f;

    /** FoodConstants.saturationByModifier: {@code nutrition * saturationModifier * 2.0f}. */
    public static float saturationByModifier(int nutrition, float saturationModifier)
    {
        return nutrition * saturationModifier * 2.0f;
    }

    /**
     * The food and exhaustion counters FoodData.tick steps. step() returns the health the game would
     * add this tick: the saturated branch heals min(saturation, 6.0f) / 6.0f, the unsaturated branch
     * heals 1.0f, and starvation returns -1.0f for the 1.0f of starve damage.
     */
    public static final class Food
    {
        public int food = MAX_FOOD;
        public float saturation = START_SATURATION;
        public float exhaustion;
        public int tickTimer;

        public Food()
        {
        }

        public Food(int food, float saturation)
        {
            this.food = food;
            this.saturation = saturation;
        }

        public Food copy()
        {
            Food copy = new Food(food, saturation);
            copy.exhaustion = exhaustion;
            copy.tickTimer = tickTimer;
            return copy;
        }

        /**
         * One tick of FoodData.tick: spend exhaustion 4.0f, then run the regen or starvation timer.
         * The caller supplies the isHurt gate, which only decides whether regen healing is wasted.
         */
        public float step()
        {
            float healed = 0.0f;
            if (exhaustion > EXHAUSTION_DROP)
            {
                exhaustion -= EXHAUSTION_DROP;
                if (saturation > 0.0f)
                {
                    saturation = Math.max(0.0f, saturation - 1.0f);
                }
                else
                {
                    food = Math.max(STARVE_LEVEL, food - 1);
                }
            }
            if (saturation > 0.0f && food >= MAX_FOOD)
            {
                if (++tickTimer >= HEALTH_TICK_COUNT_SATURATED)
                {
                    float spent = Math.min(saturation, EXHAUSTION_HEAL);
                    healed = spent / EXHAUSTION_HEAL;
                    exhaustion += spent;
                    tickTimer = 0;
                }
            }
            else if (food >= HEAL_LEVEL)
            {
                if (++tickTimer >= HEALTH_TICK_COUNT)
                {
                    healed = UNSATURATED_HEAL;
                    exhaustion += EXHAUSTION_HEAL;
                    tickTimer = 0;
                }
            }
            else if (food <= STARVE_LEVEL)
            {
                if (++tickTimer >= HEALTH_TICK_COUNT)
                {
                    healed = -UNSATURATED_HEAL;
                    tickTimer = 0;
                }
            }
            else
            {
                tickTimer = 0;
            }
            return healed;
        }
    }
}