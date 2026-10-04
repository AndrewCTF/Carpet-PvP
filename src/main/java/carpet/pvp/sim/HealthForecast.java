package carpet.pvp.sim;

/**
 * Projection of effective health, meaning health plus absorption, over the next n ticks. Status
 * effects tick down and regenerate, natural regeneration heals, and a constant incoming damage
 * rate eats into absorption first and health second, the order LivingEntity.actuallyHurt uses.
 */
public final class HealthForecast
{
    private final float[] health;
    private final float[] absorption;
    private final int ticks;

    private HealthForecast(int ticks)
    {
        this.ticks = ticks;
        this.health = new float[ticks + 1];
        this.absorption = new float[ticks + 1];
    }

    /**
     * Projects {@code ticks} ticks. Each tick runs the active effects first, then one FoodData.tick,
     * then the incoming damage, which is the health the defender would actually lose after armour,
     * Resistance and protection.
     *
     * @param maxHealth Attributes.MAX_HEALTH, 20 by default, plus any Health Boost
     * @param effects active effects; their durations are not modified
     * @param exhaustionPerTick exhaustion added per tick, such as attacks while fighting
     * @param incomingDamage health per tick that survives absorption, clamped at zero
     * @param damageDelayTicks ticks before the incoming damage starts, while the bot is out of reach
     */
    public static HealthForecast project(float health, float maxHealth, float absorption,
                                         Effects.Instance[] effects, Effects.Food food,
                                         float exhaustionPerTick, float incomingDamage, int ticks)
    {
        return project(health, maxHealth, absorption, effects, food, exhaustionPerTick, incomingDamage, 0, ticks);
    }

    /**
     * Projects {@code ticks} ticks with the damage held off for {@code damageDelayTicks}, which models
     * the bot being out of the opponent's reach for the first few ticks of the window.
     */
    public static HealthForecast project(float health, float maxHealth, float absorption,
                                         Effects.Instance[] effects, Effects.Food food,
                                         float exhaustionPerTick, float incomingDamage,
                                         int damageDelayTicks, int ticks)
    {
        if (ticks < 0)
        {
            throw new IllegalArgumentException("negative horizon");
        }
        HealthForecast forecast = new HealthForecast(ticks);
        forecast.health[0] = health;
        forecast.absorption[0] = absorption;
        float[] remaining = new float[effects.length];
        for (int i = 0; i < effects.length; i++)
        {
            remaining[i] = effects[i].duration;
        }
        Effects.Food state = food == null ? new Effects.Food() : food.copy();
        float currentHealth = health;
        float currentAbsorption = absorption;
        for (int tick = 1; tick <= ticks; tick++)
        {
            for (int i = 0; i < effects.length; i++)
            {
                Effects.Instance instance = effects[i];
                if (remaining[i] <= 0.0f)
                {
                    continue;
                }
                if (instance.effect == Effects.Effect.REGENERATION && currentHealth < maxHealth
                        && remaining[i] % Effects.regenerationInterval(instance.amplifier) == 0.0f)
                {
                    currentHealth = Math.min(maxHealth, currentHealth + 1.0f);
                }
                remaining[i] -= 1.0f;
            }
            state.exhaustion += exhaustionPerTick;
            float healed = state.step();
            // heal() caps at max health; nothing caps the low end, so the forecast can go negative.
            currentHealth = Math.min(maxHealth, currentHealth + healed);
            if (incomingDamage > 0.0f && tick > damageDelayTicks)
            {
                float absorbed = Math.min(currentAbsorption, incomingDamage);
                currentAbsorption -= absorbed;
                currentHealth -= incomingDamage - absorbed;
            }
            forecast.health[tick] = currentHealth;
            forecast.absorption[tick] = currentAbsorption;
        }
        return forecast;
    }

    /** Horizon length in ticks. */
    public int ticks()
    {
        return ticks;
    }

    /** Health after the given tick, which may be negative once the forecast has run out. */
    public float healthAt(int tick)
    {
        return health[clamp(tick)];
    }

    /** Absorption hearts after the given tick. */
    public float absorptionAt(int tick)
    {
        return absorption[clamp(tick)];
    }

    /** Effective health, health plus absorption, after the given tick. */
    public float effectiveAt(int tick)
    {
        int i = clamp(tick);
        return health[i] + absorption[i];
    }

    /** The lowest effective health over the whole horizon, which includes tick 0. */
    public float minEffective()
    {
        float worst = health[0] + absorption[0];
        for (int tick = 1; tick <= ticks; tick++)
        {
            worst = Math.min(worst, health[tick] + absorption[tick]);
        }
        return worst;
    }

    /** First tick whose health has fallen to zero or below, or -1 when the bot survives the horizon. */
    public int ticksToLethal()
    {
        for (int tick = 1; tick <= ticks; tick++)
        {
            if (health[tick] <= 0.0f)
            {
                return tick;
            }
        }
        return -1;
    }

    /** First tick whose effective health drops to the threshold or below, or -1 when it never does. */
    public int ticksUntilBelow(float effectiveThreshold)
    {
        for (int tick = 1; tick <= ticks; tick++)
        {
            if (health[tick] + absorption[tick] <= effectiveThreshold)
            {
                return tick;
            }
        }
        return -1;
    }

    private int clamp(int tick)
    {
        return tick < 0 ? 0 : tick > ticks ? ticks : tick;
    }
}