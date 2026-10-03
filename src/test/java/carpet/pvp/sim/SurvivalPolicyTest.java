package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SurvivalPolicyTest
{
    private final SurvivalPolicy policy = new SurvivalPolicy();

    private SurvivalPolicy.Inputs duel()
    {
        SurvivalPolicy.Inputs in = new SurvivalPolicy.Inputs();
        in.horizonTicks = 60;
        in.beingHit = true;
        in.distance = 2.0;
        in.incomingDamagePerTick = 0.15f;
        in.outgoingDamagePerTick = 0.2f;
        in.incomingHitDamage = 7.0f;
        in.armorValue = 20.0f;
        in.armorToughness = 3.0f;
        in.enemyHealth = 20.0f;
        in.goldenApples = 2;
        in.pearls = 2;
        in.food = new Effects.Food(20, 5.0f);
        return in;
    }

    @Test
    void doesNotWasteAGoldenAppleAtFullHealth()
    {
        // Comfortable trade: 20 health against 0.1 a tick bottoms out at 16.9, well above the 6 heart
        // buffer, so the eight hearts an apple buys are worth less than the 32 ticks of offense and
        // the two gaps it spends. The reserve alone is RESERVE_WEIGHT * 2 / 3 = 6.
        SurvivalPolicy.Inputs in = duel();
        in.incomingDamagePerTick = 0.1f;
        in.horizonTicks = 50;
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertEquals(-1, decision.forecast.ticksToLethal());
        assertTrue(decision.forecast.minEffective() > SurvivalPolicy.SAFETY_BUFFER);
        assertEquals(SurvivalPolicy.Action.KEEP_FIGHTING, decision.chosen.action);
        assertTrue(decision.offered(SurvivalPolicy.Action.EAT_GOLDEN_APPLE));
        assertTrue(decision.scoreOf(SurvivalPolicy.Action.EAT_GOLDEN_APPLE)
                < decision.scoreOf(SurvivalPolicy.Action.KEEP_FIGHTING));
        assertEquals(6.0, SurvivalPolicy.RESERVE_WEIGHT * 2.0 / 3.0, 1e-9);
        // 32 ticks of Consumables.defaultFood time, and 32 ticks of offense given up.
        assertEquals(32, Effects.CONSUME_TICKS);
        assertEquals(32, Effects.GOLDEN_APPLE.useTicks);
    }

    @Test
    void eatsWhenTheForecastSaysItWillOtherwiseDie()
    {
        // 8 health against 0.25 a tick runs out on tick 42. The apple's 4 regeneration plus 4
        // absorption land at the end of its 32 tick eat, with health still above zero at tick 31,
        // so the eat turns a lethal forecast into a survivable one.
        SurvivalPolicy.Inputs in = duel();
        in.health = 8.0f;
        in.incomingDamagePerTick = 0.25f;
        in.horizonTicks = 50;
        in.pearls = 0;
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertEquals(42, decision.forecast.ticksToLethal());
        assertTrue(decision.forecast.minEffective() <= 0.0f);
        assertEquals(SurvivalPolicy.Action.EAT_GOLDEN_APPLE, decision.chosen.action);
        assertFalse(decision.offered(SurvivalPolicy.Action.RETREAT));
        // Trading on takes the dying penalty, eating does not.
        assertTrue(decision.scoreOf(SurvivalPolicy.Action.KEEP_FIGHTING) < -900.0);
        assertTrue(decision.scoreOf(SurvivalPolicy.Action.EAT_GOLDEN_APPLE)
                > decision.scoreOf(SurvivalPolicy.Action.KEEP_FIGHTING) + 900.0);
    }

    @Test
    void throwsAHealingPotionWhenEatingIsTooSlow()
    {
        // 5 health runs out on tick 17, long before a 32 tick apple is finished, but a splash at the
        // bot's own feet has full power and heals (int) (1.0 * (4 << 0) + 0.5) = 4 after a 3 tick
        // hotbar swap and 2 ticks of aim, which is inside the 20 tick decision window.
        SurvivalPolicy.Inputs in = duel();
        in.health = 5.0f;
        in.incomingDamagePerTick = 0.35f;
        in.horizonTicks = 20;
        in.pearls = 0;
        in.goldenApples = 0;
        in.enchantedGoldenApples = 0;
        in.healingPotions = 2;
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertEquals(17, decision.forecast.ticksToLethal());
        assertEquals(SurvivalPolicy.Action.THROW_HEALING_POTION, decision.chosen.action);
        assertFalse(decision.offered(SurvivalPolicy.Action.EAT_GOLDEN_APPLE));
        assertTrue(decision.chosen.reason.contains("for 4.0"));
        assertTrue(decision.explain().contains("hp/tick"));
    }

    @Test
    void retreatsWhenOutOfHealingAndLosing()
    {
        // No gap and no potion left, 6 health at 0.25 a tick runs out on tick 33. A pearl buys
        // in.breakTicks = 30 out of reach, which is enough to keep the trough above zero.
        SurvivalPolicy.Inputs in = duel();
        in.health = 6.0f;
        in.incomingDamagePerTick = 0.25f;
        in.horizonTicks = 50;
        in.breakTicks = 30;
        in.goldenApples = 0;
        in.enchantedGoldenApples = 0;
        in.healingPotions = 0;
        in.pearls = 1;
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertEquals(33, decision.forecast.ticksToLethal());
        assertEquals(SurvivalPolicy.Action.RETREAT, decision.chosen.action);
        assertTrue(decision.forecast.minEffective() <= 0.0f);
        assertTrue(decision.scoreOf(SurvivalPolicy.Action.KEEP_FIGHTING) < -900.0);
        assertTrue(decision.scoreOf(SurvivalPolicy.Action.RETREAT)
                > decision.scoreOf(SurvivalPolicy.Action.KEEP_FIGHTING) + 900.0);
        assertTrue(decision.chosen.reason.contains("out of reach for 30 ticks"));
    }

    @Test
    void doesNotRetreatWhenTheForecastIsComfortable()
    {
        // Trading at 0.1 a tick from full health never runs out, so there is nothing to run from and
        // the pearl is not even offered, reserve or not.
        SurvivalPolicy.Inputs in = duel();
        in.incomingDamagePerTick = 0.1f;
        in.horizonTicks = 50;
        assertEquals(-1, policy.decide(in).forecast.ticksToLethal());
        assertFalse(policy.decide(in).offered(SurvivalPolicy.Action.RETREAT));
        assertEquals(SurvivalPolicy.Action.KEEP_FIGHTING, policy.decide(in).chosen.action);
    }

    @Test
    void reEngagesWhenTheForecastRecovers()
    {
        // Back off with 18 health and 4 absorption, the enemy down to 6 and still closing: nothing the
        // bot carries is needed, so it closes the distance and resumes trading.
        SurvivalPolicy.Inputs in = duel();
        in.beingHit = false;
        in.distance = 9.0;
        in.travelTicks = 10;
        in.health = 18.0f;
        in.absorption = 4.0f;
        in.incomingDamagePerTick = 0.05f;
        in.enemyHealth = 6.0f;
        in.horizonTicks = 80;
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertEquals(-1, decision.forecast.ticksToLethal());
        assertEquals(SurvivalPolicy.Action.RE_ENGAGE, decision.chosen.action);
        // Out of reach, so trading is not an option and the forecast already holds the damage off.
        assertFalse(decision.offered(SurvivalPolicy.Action.KEEP_FIGHTING));
        assertFalse(decision.offered(SurvivalPolicy.Action.RETREAT));
        assertTrue(decision.scoreOf(SurvivalPolicy.Action.RE_ENGAGE)
                > decision.scoreOf(SurvivalPolicy.Action.EAT_GOLDEN_APPLE));
    }

    @Test
    void swapsArmorBeforeAPieceBreaks()
    {
        SurvivalPolicy.Inputs in = duel();
        in.incomingDamagePerTick = 0.1f;
        in.horizonTicks = 50;
        in.unbreakingLevel = 3;
        in.armorDamage[Durability.HEAD] = Durability.maxDamage(Durability.HEAD, Durability.NETHERITE_MULTIPLIER) - 12;
        in.spareArmor[Durability.HEAD] = Durability.maxDamage(Durability.HEAD, Durability.NETHERITE_MULTIPLIER);
        assertEquals(12, in.armorRemaining(Durability.HEAD));
        assertEquals(SurvivalPolicy.Action.SWAP_ARMOR, policy.decide(in).chosen.action);
        assertTrue(policy.decide(in).chosen.reason.contains("12 durability left"));

        // Without a spare there is nothing to swap to.
        SurvivalPolicy.Inputs noSpare = duel();
        noSpare.incomingDamagePerTick = 0.1f;
        noSpare.horizonTicks = 50;
        noSpare.armorDamage[Durability.HEAD] = 395;
        assertFalse(policy.decide(noSpare).offered(SurvivalPolicy.Action.SWAP_ARMOR));
    }

    @Test
    void mendsOnlyWhenSafe()
    {
        SurvivalPolicy.Inputs safe = duel();
        safe.incomingDamagePerTick = 0.1f;
        safe.horizonTicks = 50;
        for (int piece = 0; piece < Durability.PIECE_COUNT; piece++)
        {
            safe.armorDamage[piece] = 40;
            safe.mending[piece] = 1;
        }
        safe.experienceBottles = 8;
        SurvivalPolicy.Decision decision = policy.decide(safe);
        assertEquals(SurvivalPolicy.Action.MEND_ARMOR, decision.chosen.action);
        // 40 durability is 20 experience points at 2 per point, and 20 / 9 rounds up to 3 bottles.
        assertEquals(20, Durability.experienceFor(40));
        assertEquals(3, Durability.bottlesFor(40));
        assertTrue(decision.chosen.reason.startsWith("3 bottles"));

        // Losing the fight, mending is not offered at all.
        SurvivalPolicy.Inputs losing = duel();
        losing.health = 6.0f;
        losing.incomingDamagePerTick = 0.5f;
        losing.goldenApples = 0;
        losing.pearls = 0;
        for (int piece = 0; piece < Durability.PIECE_COUNT; piece++)
        {
            losing.armorDamage[piece] = 40;
            losing.mending[piece] = 1;
        }
        losing.experienceBottles = 8;
        assertFalse(policy.decide(losing).offered(SurvivalPolicy.Action.MEND_ARMOR));

        // Not enough bottles either.
        SurvivalPolicy.Inputs broke = duel();
        broke.incomingDamagePerTick = 0.1f;
        broke.horizonTicks = 50;
        for (int piece = 0; piece < Durability.PIECE_COUNT; piece++)
        {
            broke.armorDamage[piece] = 40;
            broke.mending[piece] = 1;
        }
        broke.experienceBottles = 2;
        assertFalse(policy.decide(broke).offered(SurvivalPolicy.Action.MEND_ARMOR));
    }

    @Test
    void buffIsNotThrownPastTheSplashRange()
    {
        SurvivalPolicy.Inputs near = duel();
        near.incomingDamagePerTick = 0.1f;
        near.horizonTicks = 50;
        near.enemyHealth = 18.0f;
        near.buffPotions = 4;
        SurvivalPolicy.Decision close = policy.decide(near);
        assertTrue(close.offered(SurvivalPolicy.Action.DRINK_BUFF));
        assertTrue(close.offered(SurvivalPolicy.Action.THROW_BUFF));
        // Drinking keeps the full 3600 ticks; a splash at 2 blocks has half power, so 1800.
        assertEquals(0.5, Effects.splashPower(4.0), 1e-9);
        assertEquals(1800, Effects.splashDuration(0.5, Effects.POTION_STRENGTH_TICKS, 1.0));
        assertTrue(close.explain().contains("drink Strength amplifier 0 for 3600 ticks"));
        assertTrue(close.explain().contains("throw Strength amplifier 0 for 1800 ticks"));

        // 5 blocks is 25 squared, past the 16 the splash area check allows, so the throw is dropped
        // while the drink still works.
        near.distance = 5.0;
        SurvivalPolicy.Decision far = policy.decide(near);
        assertFalse(far.offered(SurvivalPolicy.Action.THROW_BUFF));
        assertTrue(far.offered(SurvivalPolicy.Action.DRINK_BUFF));
        assertEquals(0.0, Effects.splashPower(25.0), 0.0);
    }

    @Test
    void cooldownsAndCountsRemoveOptions()
    {
        SurvivalPolicy.Inputs in = duel();
        in.health = 8.0f;
        in.incomingDamagePerTick = 0.25f;
        in.horizonTicks = 50;
        in.eatingCooldown = 5;
        in.pearls = 0;
        assertFalse(policy.decide(in).offered(SurvivalPolicy.Action.EAT_GOLDEN_APPLE));

        SurvivalPolicy.Inputs noPearl = duel();
        noPearl.health = 3.0f;
        noPearl.incomingDamagePerTick = 0.3f;
        noPearl.pearlCooldown = 5;
        noPearl.pearls = 1;
        assertFalse(policy.decide(noPearl).offered(SurvivalPolicy.Action.RETREAT));

        SurvivalPolicy.Inputs noPotion = duel();
        noPotion.health = 5.0f;
        noPotion.incomingDamagePerTick = 0.35f;
        noPotion.horizonTicks = 20;
        noPotion.pearls = 0;
        noPotion.goldenApples = 0;
        noPotion.enchantedGoldenApples = 0;
        noPotion.potionCooldown = 5;
        noPotion.healingPotions = 2;
        assertFalse(policy.decide(noPotion).offered(SurvivalPolicy.Action.THROW_HEALING_POTION));

        SurvivalPolicy.Inputs none = duel();
        none.goldenApples = 0;
        assertFalse(policy.decide(none).offered(SurvivalPolicy.Action.EAT_GOLDEN_APPLE));
    }

    @Test
    void enchantedAppleOutranksAGoldenApple()
    {
        // Both are on a 32 tick eat, so only the health they add separates them: 4 plus 4 against
        // 16 plus 16, and the trough over the rest of the horizon keeps the difference.
        SurvivalPolicy.Inputs in = duel();
        in.health = 8.0f;
        in.incomingDamagePerTick = 0.25f;
        in.horizonTicks = 50;
        in.pearls = 0;
        in.goldenApples = 1;
        in.enchantedGoldenApples = 1;
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertEquals(SurvivalPolicy.Action.EAT_ENCHANTED_GOLDEN_APPLE, decision.chosen.action);
        assertTrue(decision.scoreOf(SurvivalPolicy.Action.EAT_ENCHANTED_GOLDEN_APPLE)
                > decision.scoreOf(SurvivalPolicy.Action.EAT_GOLDEN_APPLE));
    }

    @Test
    void totemGoesToTheOffhandForAPredictedBurst()
    {
        SurvivalPolicy.Inputs in = duel();
        in.health = 3.0f;
        in.armorValue = 0.0f;
        in.incomingDamagePerTick = 0.2f;
        in.pearls = 0;
        in.goldenApples = 0;
        in.enchantedGoldenApples = 0;
        in.totems = 3;
        in.totemInOffhand = false;
        in.ticksSinceTotemPop = 5;
        // 7 raw damage through no armour is 7 against 3 health, so the burst is lethal.
        assertEquals(7.0f, policy.hitLoss(in, 7.0f), 1e-5f);
        assertTrue(policy.isLethalBurst(in, 7.0f));
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertTrue(decision.lethalBurst);
        assertTrue(decision.totemToOffhand);
        // The retotem delay is a parameter, one second by default.
        assertEquals(15, decision.ticksToRetotem);
        assertEquals(SurvivalPolicy.DEFAULT_RETOTEM_DELAY, decision.ticksToRetotem + in.ticksSinceTotemPop);

        // Once the delay has passed the fresh totem can go in.
        in.ticksSinceTotemPop = 25;
        assertEquals(0, policy.decide(in).ticksToRetotem);
        assertTrue(policy.decide(in).totemToOffhand);

        // Already in the offhand, so nothing to move.
        in.totemInOffhand = true;
        assertFalse(policy.decide(in).totemToOffhand);
        // No totems carried, so nothing to move either.
        in.totemInOffhand = false;
        in.totems = 0;
        assertFalse(policy.decide(in).totemToOffhand);
        // A configured delay other than the default is honoured.
        in.totems = 1;
        in.retotemDelayTicks = 40;
        in.ticksSinceTotemPop = 5;
        assertEquals(35, policy.decide(in).ticksToRetotem);
    }

    @Test
    void burstPredictionUsesArmourResistanceAndProtection()
    {
        SurvivalPolicy.Inputs in = duel();
        in.armorValue = 20.0f;
        in.armorToughness = 3.0f;
        in.epf = 0.0f;
        in.resistanceAmplifier = 1;
        in.health = 6.0f;
        in.absorption = 0.0f;
        // CombatRules.getDamageAfterAbsorb: divisor 2 + 3/4, so 10 raw becomes 10 * (1 - 16.36/25).
        float afterArmour = CombatMath.damageAfterDefences(10.0f, 20.0f, 3.0f, 0, 0.0f);
        assertEquals(3.4545457f, afterArmour, 1e-4f);
        // Resistance II then keeps (25 - 10) / 25 of it.
        assertEquals(0.6f, Effects.resistanceFactor(1), 1e-6f);
        assertEquals(2.0727274f, policy.hitLoss(in, 10.0f), 1e-4f);
        assertFalse(policy.isLethalBurst(in, 10.0f));
        assertTrue(policy.isLethalBurst(in, 30.0f));
        in.resistanceAmplifier = -1;
        assertEquals(3.4545457f, policy.hitLoss(in, 10.0f), 1e-4f);
        // Protection is applied inside CombatMath after the armour fraction: 3.4545 * (1 - 16/25).
        in.epf = 16.0f;
        assertEquals(1.2436365f, policy.hitLoss(in, 10.0f), 1e-4f);
    }

    @Test
    void scoresAreExposedForLogging()
    {
        SurvivalPolicy.Decision decision = policy.decide(duel());
        assertEquals(decision.chosen.score, decision.scoreOf(decision.chosen.action), 1e-12);
        assertEquals(0.0, decision.scoreOf(SurvivalPolicy.Action.RETREAT), 0.0);
        assertFalse(decision.offered(SurvivalPolicy.Action.RE_ENGAGE));
        String log = decision.explain();
        assertTrue(log.startsWith("trough="));
        assertTrue(log.contains("KEEP_FIGHTING"));
        assertTrue(log.contains("hp/tick"));
        // Every option is scoreable and carries a reason.
        for (SurvivalPolicy.Option option : decision.options)
        {
            assertTrue(option.reason.length() > 0);
            assertTrue(Double.isFinite(option.score));
        }
    }
}