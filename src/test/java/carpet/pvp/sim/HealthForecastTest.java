package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HealthForecastTest
{
    @Test
    void simpleCaseWorkedByHand()
    {
        // 12 health, no absorption, no effects, food 20 with 5.0 saturation and no added exhaustion,
        // taking 0.2 damage a tick for 20 ticks.
        //   ticks 1-9   nothing: the saturated branch counts to 10
        //   tick 10     heal min(5, 6) / 6 = 5/6, then -0.2 damage
        //   tick 11     exhaustion 5.0f > 4.0f so saturation 5 -> 4
        //   tick 20     heal min(4, 6) / 6 = 4/6, then -0.2 damage
        // so health = 12 - 20 * 0.2 + 5/6 + 4/6 = 12 - 4 + 1.5 = 9.5.
        Effects.Food food = new Effects.Food(20, 5.0f);
        HealthForecast forecast = HealthForecast.project(12.0f, 20.0f, 0.0f, new Effects.Instance[0],
                food, 0.0f, 0.2f, 20);
        assertEquals(12.0f, forecast.healthAt(0), 0.0f);
        assertEquals(12.0f - 9 * 0.2f, forecast.healthAt(9), 1e-5f);
        assertEquals(12.0f + 5.0f / 6.0f - 2.0f, forecast.healthAt(10), 1e-5f);
        assertEquals(12.0f + 5.0f / 6.0f + 4.0f / 6.0f - 4.0f, forecast.healthAt(20), 1e-5f);
        assertEquals(9.5f, forecast.healthAt(20), 1e-4f);
        assertEquals(9.5f, forecast.effectiveAt(20), 1e-4f);
        // The trough is tick 19, just before the second heal: 12 + 5/6 - 19 * 0.2.
        assertEquals(12.0f + 5.0f / 6.0f - 3.8f, forecast.minEffective(), 1e-4f);
        assertEquals(-1, forecast.ticksToLethal());
        assertEquals(20, forecast.ticks());
        // The caller's food state is copied, not stepped.
        assertEquals(5.0f, food.saturation, 0.0f);
        assertEquals(0.0f, food.exhaustion, 0.0f);
    }

    @Test
    void absorptionGoesFirst()
    {
        // 10 health with 4 absorption, taking 0.5 a tick and nothing else: LivingEntity.actuallyHurt
        // takes the absorption off first, so it covers 4 / 0.5 = 8 whole ticks and health only starts
        // on tick 9, reaching zero on tick 8 + 20 = 28.
        HealthForecast forecast = HealthForecast.project(10.0f, 20.0f, 4.0f, new Effects.Instance[0],
                new Effects.Food(17, 0.0f), 0.0f, 0.5f, 60);
        assertEquals(4.0f, forecast.absorptionAt(0), 0.0f);
        assertEquals(3.5f, forecast.absorptionAt(1), 1e-5f);
        assertEquals(0.5f, forecast.absorptionAt(7), 1e-5f);
        assertEquals(0.0f, forecast.absorptionAt(8), 1e-5f);
        assertEquals(10.0f, forecast.healthAt(8), 1e-5f);
        assertEquals(9.5f, forecast.healthAt(9), 1e-5f);
        assertEquals(10.0f, forecast.effectiveAt(8), 1e-5f);
        assertEquals(9.5f, forecast.effectiveAt(9), 1e-5f);
        assertEquals(28, forecast.ticksToLethal());
        assertEquals(28, forecast.ticksUntilBelow(0.0f));
        assertTrue(forecast.minEffective() < 0.0f);
    }

    @Test
    void regenerationTicksOnItsOwnSchedule()
    {
        // Regeneration II 100 ticks fires when the remaining duration is a multiple of 25, so with
        // the timer starting at 100 it fires on ticks 1, 26 and 51.
        Effects.Instance[] effects = {new Effects.Instance(Effects.Effect.REGENERATION, 1, 100)};
        HealthForecast forecast = HealthForecast.project(10.0f, 20.0f, 0.0f, effects,
                new Effects.Food(17, 0.0f), 0.0f, 0.0f, 60);
        assertEquals(11.0f, forecast.healthAt(1), 1e-5f);
        assertEquals(11.0f, forecast.healthAt(25), 1e-5f);
        assertEquals(12.0f, forecast.healthAt(26), 1e-5f);
        assertEquals(12.0f, forecast.healthAt(50), 1e-5f);
        assertEquals(13.0f, forecast.healthAt(51), 1e-5f);
        // The 100 tick effect is spent by tick 101.
        assertEquals(13.0f, forecast.healthAt(100), 1e-5f);
        assertEquals(13.0f, forecast.healthAt(60), 1e-5f);
    }

    @Test
    void regenerationStopsAtMaxHealth()
    {
        // Full health wastes the effect: RegenerationMobEffect.applyEffectTick only heals below max.
        Effects.Instance[] effects = {new Effects.Instance(Effects.Effect.REGENERATION, 1, 100)};
        HealthForecast forecast = HealthForecast.project(20.0f, 20.0f, 0.0f, effects,
                new Effects.Food(17, 0.0f), 0.0f, 0.0f, 60);
        assertEquals(20.0f, forecast.healthAt(60), 0.0f);
        // Health Boost raises the cap to 24, so the same effect now heals.
        HealthForecast boosted = HealthForecast.project(20.0f, 24.0f, 0.0f, effects,
                new Effects.Food(17, 0.0f), 0.0f, 0.0f, 60);
        assertEquals(23.0f, boosted.healthAt(60), 1e-5f);
    }

    @Test
    void damageCanBeHeldOff()
    {
        // Out of reach for 10 ticks, so the 0.4 a tick only starts on tick 11.
        HealthForecast forecast = HealthForecast.project(10.0f, 20.0f, 0.0f, new Effects.Instance[0],
                new Effects.Food(17, 0.0f), 0.0f, 0.4f, 10, 30);
        assertEquals(10.0f, forecast.healthAt(10), 1e-5f);
        assertEquals(9.6f, forecast.healthAt(11), 1e-5f);
        // 20 ticks of damage from tick 11 to 30.
        assertEquals(2.0f, forecast.healthAt(30), 1e-5f);
        assertEquals(2.0f, forecast.minEffective(), 1e-5f);
    }

    @Test
    void exhaustionDropsOnePointATime()
    {
        // FoodData.tick takes one point of saturation per tick that exhaustion is strictly above 4.0f,
        // however much was added, so a single big hit is worth exactly one point.
        Effects.Food food = new Effects.Food(20, 5.0f);
        food.exhaustion = 4.0f;
        food.step();
        assertEquals(5.0f, food.saturation, 1e-6f);
        food.exhaustion = 0.0f;
        food.exhaustion += 4.01f;
        food.step();
        assertEquals(4.0f, food.saturation, 1e-6f);
        // With no saturation left the drop falls through to food, and again only one point a tick.
        Effects.Food dry = new Effects.Food(20, 0.0f);
        dry.exhaustion = 4.01f;
        dry.step();
        assertEquals(19, dry.food);
        // A netherite sword at the full 1.6 attack speed spends 0.1f of exhaustion every 12.5 ticks,
        // which is 0.008 a tick.
        float rate = Effects.EXHAUSTION_ATTACK / CombatMath.fullChargeTicks(1.6);
        assertEquals(0.008f, rate, 1e-6f);
    }

    @Test
    void thresholdAndClamping()
    {
        HealthForecast forecast = HealthForecast.project(20.0f, 20.0f, 0.0f, new Effects.Instance[0],
                new Effects.Food(17, 0.0f), 0.0f, 1.0f, 5);
        assertEquals(15.0f, forecast.healthAt(5), 1e-5f);
        assertEquals(4, forecast.ticksUntilBelow(16.0f));
        assertEquals(-1, forecast.ticksUntilBelow(14.0f));
        // Reads past either end clamp instead of throwing.
        assertEquals(20.0f, forecast.healthAt(-4), 0.0f);
        assertEquals(15.0f, forecast.healthAt(500), 1e-5f);
        assertEquals(0, forecast.absorptionAt(500), 0.0f);
        assertEquals(-1, forecast.ticksUntilBelow(14.0f));
    }
}