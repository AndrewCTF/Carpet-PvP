package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EffectsTest
{
    @Test
    void goldenAppleTotals()
    {
        // Consumables.GOLDEN_APPLE = defaultFood() with Regeneration II 100 and Absorption I 2400.
        assertEquals(32, Effects.GOLDEN_APPLE.useTicks);
        // RegenerationMobEffect fires every 50 >> 1 = 25 ticks, so 100 / 25 = 4 health.
        assertEquals(4.0f, Effects.GOLDEN_APPLE.regenerationTotal(), 1e-5f);
        // AbsorptionMobEffect.onEffectStarted: max(current, 4 * (1 + 0)) = 4.
        assertEquals(4.0f, Effects.GOLDEN_APPLE.absorptionGranted(0.0f), 1e-5f);
        assertEquals(8.0f, Effects.GOLDEN_APPLE.absorptionGranted(8.0f), 1e-5f);
        // Foods.GOLDEN_APPLE: 4 nutrition, 1.2 modifier, FoodConstants.saturationByModifier = 4 * 1.2 * 2.
        assertEquals(9.6f, Effects.GOLDEN_APPLE.saturationGained(), 1e-5f);
        assertEquals(8.0f, Effects.GOLDEN_APPLE.totalHealing(0.0f), 1e-5f);
        assertEquals(1, Effects.GOLDEN_APPLE.amplifierOf(Effects.Effect.REGENERATION));
        assertEquals(100, Effects.GOLDEN_APPLE.durationOf(Effects.Effect.REGENERATION));
    }

    @Test
    void enchantedGoldenAppleTotals()
    {
        // Consumables.ENCHANTED_GOLDEN_APPLE: Regeneration II 400, Resistance I 6000,
        // Fire Resistance I 6000 and Absorption IV 2400.
        assertEquals(32, Effects.ENCHANTED_GOLDEN_APPLE.useTicks);
        assertEquals(16.0f, Effects.ENCHANTED_GOLDEN_APPLE.regenerationTotal(), 1e-5f);
        // Absorption IV is amplifier 3, so max(current, 4 * (1 + 3)) = 16.
        assertEquals(16.0f, Effects.ENCHANTED_GOLDEN_APPLE.absorptionGranted(0.0f), 1e-5f);
        assertEquals(32.0f, Effects.ENCHANTED_GOLDEN_APPLE.totalHealing(0.0f), 1e-5f);
        assertEquals(0, Effects.ENCHANTED_GOLDEN_APPLE.amplifierOf(Effects.Effect.RESISTANCE));
        assertEquals(6000, Effects.ENCHANTED_GOLDEN_APPLE.durationOf(Effects.Effect.RESISTANCE));
        assertEquals(6000, Effects.ENCHANTED_GOLDEN_APPLE.durationOf(Effects.Effect.FIRE_RESISTANCE));
        assertEquals(9.6f, Effects.ENCHANTED_GOLDEN_APPLE.saturationGained(), 1e-5f);
    }

    @Test
    void totemTotals()
    {
        // DeathProtection.TOTEM_OF_UNDYING: Regeneration II 900, Absorption II 100, Fire Resistance I 800.
        assertEquals(36.0f, Effects.TOTEM.regenerationTotal(), 1e-5f);
        assertEquals(8.0f, Effects.TOTEM.absorptionGranted(0.0f), 1e-5f);
        assertEquals(800, Effects.TOTEM.durationOf(Effects.Effect.FIRE_RESISTANCE));
        assertEquals(1.0f, Effects.TOTEM_HEALTH, 0.0f);
        assertTrue(Effects.TOTEM_CLEARS_EFFECTS);
        assertEquals(0, Effects.TOTEM.useTicks);
    }

    @Test
    void regenerationPerTick()
    {
        // RegenerationMobEffect.shouldApplyEffectTickThisTick uses 50 >> amplifier.
        assertEquals(50, Effects.regenerationInterval(0));
        assertEquals(25, Effects.regenerationInterval(1));
        assertEquals(12, Effects.regenerationInterval(2));
        assertEquals(6, Effects.regenerationInterval(3));
        assertEquals(3, Effects.regenerationInterval(4));
        assertEquals(1, Effects.regenerationInterval(5));
        assertEquals(1, Effects.regenerationInterval(6));
        assertEquals(1.0 / 50.0, Effects.regenerationPerTick(0), 1e-9);
        assertEquals(1.0 / 25.0, Effects.regenerationPerTick(1), 1e-9);
        assertEquals(1.0 / 6.0, Effects.regenerationPerTick(3), 1e-9);
        assertEquals(1.0, Effects.regenerationPerTick(5), 1e-9);
        // Regeneration II for 400 ticks fires 16 times, the whole advantage of an enchanted apple.
        assertEquals(4, Effects.regenerationTicks(100, 1));
        assertEquals(16, Effects.regenerationTicks(400, 1));
        assertEquals(36, Effects.regenerationTicks(900, 1));
        assertEquals(1, Effects.regenerationTicks(50, 0));
        assertEquals(0, Effects.regenerationTicks(49, 0));
    }

    @Test
    void damageModifiers()
    {
        // MobEffects.STRENGTH and WEAKNESS are ATTACK_DAMAGE ADD_VALUE, scaled by amplifier + 1.
        assertEquals(3.0, Effects.strengthBonus(0), 0.0);
        assertEquals(6.0, Effects.strengthBonus(1), 0.0);
        assertEquals(-4.0, Effects.weaknessPenalty(0), 0.0);
        assertEquals(-8.0, Effects.weaknessPenalty(1), 0.0);
        // MobEffects.SPEED is MOVEMENT_SPEED ADD_MULTIPLIED_TOTAL 0.2, so 20 percent per level.
        assertEquals(0.2, Effects.speedBonus(0), 1e-9);
        assertEquals(0.4, Effects.speedBonus(1), 1e-9);
        assertEquals(-0.15, Effects.slownessPenalty(0), 1e-9);
        assertEquals(0.1, Effects.hasteBonus(0), 1e-9);
        // MobEffects.HEALTH_BOOST is MAX_HEALTH ADD_VALUE 4, on the Attributes.MAX_HEALTH default of 20.
        assertEquals(4.0, Effects.maxHealthBonus(0), 0.0);
        assertEquals(8.0, Effects.maxHealthBonus(1), 0.0);
        assertEquals(4.0, Effects.maxAbsorptionBonus(0), 0.0);
        assertEquals(16.0, Effects.absorptionOnStart(3), 1e-6f);
        assertEquals(8.0f, Effects.absorptionOnStart(1), 1e-6f);
    }

    @Test
    void resistance()
    {
        // LivingEntity.getDamageAfterMagicAbsorb: damage * (25 - 5 * (amplifier + 1)) / 25.
        assertEquals(0.8f, Effects.resistanceFactor(0), 1e-6f);
        assertEquals(0.6f, Effects.resistanceFactor(1), 1e-6f);
        assertEquals(0.4f, Effects.resistanceFactor(2), 1e-6f);
        assertEquals(0.2f, Effects.resistanceFactor(3), 1e-6f);
        assertEquals(0.0f, Effects.resistanceFactor(4), 0.0f);
        assertEquals(1.0f, Effects.resistanceFactor(-1), 0.0f);
        // 10 damage through Resistance I is 8.
        assertEquals(8.0f, Effects.resistanceFactor(0) * 10.0f, 1e-5f);
    }

    @Test
    void instantEffects()
    {
        // HealOrHarmMobEffect: heal (int) (power * (4 << amplifier) + 0.5), harm uses 6 instead.
        assertEquals(4, Effects.instantHeal(1.0, 0));
        assertEquals(8, Effects.instantHeal(1.0, 1));
        assertEquals(16, Effects.instantHeal(1.0, 2));
        assertEquals(2, Effects.instantHeal(0.5, 0));
        assertEquals(4, Effects.instantHeal(0.5, 1));
        assertEquals(0, Effects.instantHeal(0.0, 0));
        assertEquals(6, Effects.instantHarm(1.0, 0));
        assertEquals(12, Effects.instantHarm(1.0, 1));
        // Potions.HEALING and STRONG_HEALING hold Instant Health for one tick.
        assertEquals(0, Effects.HEALING_POTION.amplifierOf(Effects.Effect.INSTANT_HEALTH));
        assertEquals(1, Effects.STRONG_HEALING_POTION.amplifierOf(Effects.Effect.INSTANT_HEALTH));
        assertEquals(32, Effects.HEALING_POTION.useTicks);
    }

    @Test
    void splashPotionRange()
    {
        // ThrownSplashPotion.onHitAsPotion: power = 1 - sqrt(distanceSqr) / 4, nothing past 4 blocks.
        assertEquals(1.0, Effects.splashPower(0.0), 1e-9);
        assertEquals(0.5, Effects.splashPower(4.0), 1e-9);
        assertEquals(0.25, Effects.splashPower(9.0), 1e-9);
        assertEquals(0.0, Effects.splashPower(16.0), 1e-9);
        assertEquals(0.0, Effects.splashPower(25.0), 1e-9);
        // Duration scales by power, and anything 20 ticks or shorter is dropped by endsWithin(20).
        assertEquals(3600, Effects.splashDuration(1.0, Effects.POTION_STRENGTH_TICKS, 1.0));
        assertEquals(1800, Effects.splashDuration(0.5, Effects.POTION_STRENGTH_TICKS, 1.0));
        assertEquals(180, Effects.splashDuration(0.05, Effects.POTION_STRENGTH_TICKS, 1.0));
        // 0.005 power scales 3600 down to (int) (18.0 + 0.5) = 18, which is under the cut-off.
        assertEquals(0, Effects.splashDuration(0.005, Effects.POTION_STRENGTH_TICKS, 1.0));
        assertEquals(0, Effects.splashDuration(0.0, Effects.POTION_STRENGTH_TICKS, 1.0));
        assertEquals(2880, Effects.splashDuration(0.8, Effects.POTION_STRENGTH_TICKS, 1.0));
    }

    @Test
    void potionDurations()
    {
        assertEquals(3600, Effects.POTION_STRENGTH_TICKS);
        assertEquals(9600, Effects.POTION_LONG_STRENGTH_TICKS);
        assertEquals(1800, Effects.POTION_STRONG_STRENGTH_TICKS);
        assertEquals(3600, Effects.POTION_SWIFTNESS_TICKS);
        assertEquals(1800, Effects.POTION_STRONG_SWIFTNESS_TICKS);
        assertEquals(3600, Effects.POTION_FIRE_RESISTANCE_TICKS);
        assertEquals(900, Effects.POTION_REGENERATION_TICKS);
        assertEquals(1, Effects.STRONG_STRENGTH_POTION.amplifierOf(Effects.Effect.STRENGTH));
        assertEquals(0, Effects.STRENGTH_POTION.amplifierOf(Effects.Effect.STRENGTH));
    }

    @Test
    void naturalRegenerationWalkedByHand()
    {
        // FoodData.tick from the FoodData constructor state (20 food, 5.0 saturation, no exhaustion yet):
        // every 10 ticks the saturated branch heals min(saturation, 6.0f) / 6.0f and spends that much
        // exhaustion, and exhaustion strictly above 4.0f takes one point of saturation per tick.
        Effects.Food food = new Effects.Food();
        assertEquals(20, food.food);
        assertEquals(5.0f, food.saturation, 0.0f);
        float tenth = 0.0f;
        float twentieth = 0.0f;
        float total = 0.0f;
        for (int tick = 1; tick <= 100; tick++)
        {
            float healed = food.step();
            total += healed;
            if (tick == 10)
            {
                tenth = healed;
            }
            if (tick == 20)
            {
                twentieth = healed;
            }
        }
        assertEquals(5.0f / 6.0f, tenth, 1e-6f);
        assertEquals(4.0f / 6.0f, twentieth, 1e-6f);
        // 5/6 + 4/6 + 1/2 + 1/2 + 2/6 + 1/6 + 1/6 + 1/6 + 1/6 = 3.5.
        assertEquals(3.5f, total, 1e-4f);
        assertEquals(20, food.food);
        assertEquals(0.0f, food.saturation, 0.0f);
    }

    @Test
    void exhaustionDrainsSaturationThenFood()
    {
        Effects.Food food = new Effects.Food(20, 5.0f);
        food.exhaustion = 5.0f;
        // Exhaustion 5.0f is above the 4.0f drop, so saturation loses exactly one point.
        food.step();
        assertEquals(4.0f, food.saturation, 1e-6f);
        assertEquals(1.0f, food.exhaustion, 1e-6f);
        assertEquals(20, food.food);

        Effects.Food dry = new Effects.Food(20, 0.0f);
        dry.exhaustion = 5.0f;
        dry.step();
        assertEquals(0.0f, dry.saturation, 0.0f);
        assertEquals(19, dry.food);
    }

    @Test
    void unsaturatedAndStarvationBranches()
    {
        // Food 17 sits between STARVE_LEVEL 0 and HEAL_LEVEL 18: no regen and no starvation.
        Effects.Food inBetween = new Effects.Food(17, 0.0f);
        float healed = 0.0f;
        for (int tick = 0; tick < 200; tick++)
        {
            healed += inBetween.step();
        }
        assertEquals(0.0f, healed, 0.0f);
        assertEquals(17, inBetween.food);

        // With saturation gone but food still 18 or more, the 80-tick branch heals exactly 1.0f.
        Effects.Food hungry = new Effects.Food(20, 0.0f);
        float first = 0.0f;
        for (int tick = 0; tick < 79; tick++)
        {
            first += hungry.step();
        }
        assertEquals(0.0f, first, 0.0f);
        assertEquals(1.0f, hungry.step(), 1e-6f);
        assertEquals(6.0f, hungry.exhaustion, 1e-6f);

        // Food 0 starves for 1.0f on the same 80-tick timer.
        Effects.Food starving = new Effects.Food(0, 0.0f);
        float starve = 0.0f;
        for (int tick = 0; tick < 80; tick++)
        {
            starve += starving.step();
        }
        assertEquals(-1.0f, starve, 1e-6f);
    }

    @Test
    void foodConstants()
    {
        assertEquals(20, Effects.MAX_FOOD);
        assertEquals(20.0f, Effects.MAX_SATURATION, 0.0f);
        assertEquals(5.0f, Effects.START_SATURATION, 0.0f);
        assertEquals(4.0f, Effects.EXHAUSTION_DROP, 0.0f);
        assertEquals(80, Effects.HEALTH_TICK_COUNT);
        assertEquals(10, Effects.HEALTH_TICK_COUNT_SATURATED);
        assertEquals(18, Effects.HEAL_LEVEL);
        assertEquals(0, Effects.STARVE_LEVEL);
        assertEquals(6.0f, Effects.EXHAUSTION_HEAL, 0.0f);
        assertEquals(0.1f, Effects.EXHAUSTION_ATTACK, 0.0f);
        assertEquals(0.005f, Effects.EXHAUSTION_MINE, 0.0f);
        assertEquals(0.0f, Effects.EXHAUSTION_WALK, 0.0f);
        // A netherite sword swings every 12.5 ticks, so a full-rate fight spends this much per tick.
        float attackRate = Effects.EXHAUSTION_ATTACK / CombatMath.fullChargeTicks(1.6);
        assertEquals(0.008f, attackRate, 1e-6f);
        assertFalse(Effects.EXHAUSTION_ATTACK <= 0.0f);
        // ThrownExperienceBottle.onHit rolls nextInt(5) + 5 points.
        assertEquals(5, Effects.EXPERIENCE_BOTTLE_MIN_XP);
        assertEquals(9, Effects.EXPERIENCE_BOTTLE_MAX_XP);
        assertEquals(9.6f, Effects.saturationByModifier(4, 1.2f), 1e-5f);
        assertEquals(1.0f, Effects.UNSATURATED_HEAL, 0.0f);
    }
}