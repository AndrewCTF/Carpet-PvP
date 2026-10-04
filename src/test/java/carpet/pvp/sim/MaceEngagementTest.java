package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MaceEngagementTest
{
    private static final int NOOP = DuelSim.NOOP;

    private static DuelSim grounded(double distance)
    {
        DuelSim sim = new DuelSim();
        sim.placeFacing(distance);
        return sim;
    }

    /**
     * Entity.checkFallDamage adds the downward part of each tick's movement to Entity.fallDistance and
     * Entity.move zeroes it on the landing tick. Dropping from y = 6 with vy = 0, each tick moves by the
     * previous vy and then applies vy = (vy - 0.08) * 0.98:
     * v1 = -0.0784, v2 = -0.155232, v3 = -0.23052736, v4 = -0.30431681, v5 = -0.37663047,
     * v6 = -0.44749786, v7 = -0.51694790, v8 = -0.58500894, v9 = -0.65170876,
     * and the first nine displacements sum to 3.34627010.
     */
    @Test
    void fallDistanceOverAKnownDrop()
    {
        DuelSim sim = grounded(1000.0);
        sim.a.y = 6.0;
        sim.a.onGround = false;
        sim.a.vy = 0.0;
        for (int i = 0; i < 10; i++)
        {
            sim.step(NOOP, NOOP);
        }
        assertEquals(3.3462701, sim.a.fallDistance, 1e-6);
        assertEquals(6.0 - 3.3462701, sim.a.y, 1e-6);
        assertFalse(sim.a.onGround);
        int ticks = 0;
        while (!sim.a.onGround && ticks < 40)
        {
            sim.step(NOOP, NOOP);
            ticks++;
        }
        assertTrue(sim.a.onGround, "landed after " + ticks + " more ticks");
        assertEquals(0.0, sim.a.fallDistance, 0.0, "the landing tick zeroes the counter");
    }

    /**
     * A wind charge fired straight down hits the top face of the ground block under the fighter, and
     * AbstractWindCharge.onHitBlock puts the burst a quarter of a block above that face, so the offset from
     * the feet is (0, 0.25, 0). ServerExplosion.hurtEntities then gives
     * (1 - 0.25 / (2 * 1.2)) * 1.22 = 1.0929167 straight up, because the burst to eye direction is vertical.
     */
    @Test
    void windChargeAtTheFeet()
    {
        DuelSim sim = grounded(1000.0);
        assertTrue(sim.windCharge(0, 0.0, 0.25, 0.0));
        assertEquals(1.0929167, sim.a.vy, 1e-6);
        assertEquals(DuelSim.WIND_CHARGE_COOLDOWN, sim.a.windChargeCooldown);
        assertFalse(sim.windCharge(0, 0.0, 0.25, 0.0), "still on cooldown");

        double apex = sim.a.y;
        for (int i = 0; i < 40; i++)
        {
            sim.step(NOOP, NOOP);
            apex = Math.max(apex, sim.a.y);
        }
        assertEquals(6.9335757, apex, 1e-5);
        assertTrue(sim.a.onGround);
    }

    /**
     * The same charge a block to the side. The feet are 1.0307764 from the burst, so the impulse keeps
     * 1 - 1.0307764 / 2.4 of its strength, times 1.22 = 0.6960220, and it points from the burst to the eye,
     * which is at (1.0, 1.37) from it, a length of 1.6961443: 0.4103558 sideways, 0.5621875 up.
     */
    @Test
    void windChargeBesideTheFighter()
    {
        DuelSim sim = grounded(1000.0);
        assertTrue(sim.windCharge(0, 1.0, 0.25, 0.0));
        assertEquals(-0.4103558, sim.a.vx, 1e-6);
        assertEquals(0.5621875, sim.a.vy, 1e-6);
        assertEquals(0.0, sim.a.vz, 1e-9);

        double[] out = new double[3];
        DuelSim.explosionImpulse(out, 0.0, 0.0, 0.0, 2.4, 0.25, 0.0, DuelSim.WIND_CHARGE_RADIUS,
                DuelSim.WIND_CHARGE_KNOCKBACK, 1.0);
        assertEquals(0.0, out[1], 1e-9, "past twice the radius the burst does not reach");
    }

    /** The same impulse at full exposure, so a plan can be made for cover as well. */
    @Test
    void windChargeImpulseScalesWithExposure()
    {
        double[] out = new double[3];
        DuelSim.explosionImpulse(out, 0.0, 0.0, 0.0, 0.0, 0.25, 0.0, DuelSim.WIND_CHARGE_RADIUS,
                DuelSim.WIND_CHARGE_KNOCKBACK, 0.5);
        assertEquals(0.5 * 1.0929167, out[1], 1e-6);
    }

    /**
     * CombatMath.maceSmashBonus gives 4 * fallDistance up to 3, then 12 + 2 * (fallDistance - 3) up to 8, then
     * 22 + fallDistance - 8. On top of the mace's 6 base damage a fully charged hit is therefore 14 at 2, 18 at
     * 3, 22 at 5, 28 at 8 and 32 at 12, and a crit multiplies the sum by 1.5 because the smash bonus is added
     * before Player.attack applies the crit multiplier.
     */
    @Test
    void smashDamageAtSeveralFallDistances()
    {
        assertEquals(14.0f, SmashTiming.smashDamage(2.0, false, 0, 0.0f, 1.0f), 1e-5f);
        assertEquals(18.0f, SmashTiming.smashDamage(3.0, false, 0, 0.0f, 1.0f), 1e-5f);
        assertEquals(22.0f, SmashTiming.smashDamage(5.0, false, 0, 0.0f, 1.0f), 1e-5f);
        assertEquals(28.0f, SmashTiming.smashDamage(8.0, false, 0, 0.0f, 1.0f), 1e-5f);
        assertEquals(32.0f, SmashTiming.smashDamage(12.0, false, 0, 0.0f, 1.0f), 1e-5f);
        assertEquals(33.0f, SmashTiming.smashDamage(5.0, true, 0, 0.0f, 1.0f), 1e-5f);
        assertEquals(34.5f, SmashTiming.smashDamage(5.0, false, 5, 0.0f, 1.0f), 1e-5f);
        assertEquals(0.0f, CombatMath.maceSmashBonus(1.5, false, 5), 0.0f, "1.5 is not more than 1.5");
        assertEquals(9.2f, SmashTiming.smashDamage(2.0, false, 0, 0.0f, 0.0f), 1e-5f,
                "the smash bonus is not charge scaled, only the 6 base damage is");
    }

    /**
     * A charged attacker's smash on a heavily armoured target: the smash bonus rides on top of the charge
     * scaling, so it is not reduced by the armour the base damage suffers, and Breach then takes a slice of
     * the armour fraction off.
     */
    @Test
    void smashDamageThroughArmourAndBreach()
    {
        DuelSim sim = grounded(2.0);
        sim.a.setLoadout(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);
        sim.b.setLoadout(8.0, 1.6, 0.0f, 20.0f, 12.0f, 0.0f, 0.0);
        sim.a.ticksSinceSwing = 100;
        sim.a.fallDistance = 5.0;
        float plain = SmashTiming.damageDealt(sim.a, sim.b, 0, 5.0);
        float breach = SmashTiming.damageDealt(sim.a, sim.b, 1, 5.0);
        assertEquals(plain, CombatMath.armorAbsorb(22.0f, 20.0f, 12.0f, 0), 1e-5f);
        assertEquals(breach, CombatMath.armorAbsorb(22.0f, 20.0f, 12.0f, 1), 1e-5f);
        assertEquals(22.0f * (1.0f - (20.0f - 22.0f / 5.0f) / 25.0f), plain, 1e-4f);
        assertEquals(22.0f * (1.0f - ((20.0f - 22.0f / 5.0f) / 25.0f - 0.15f)), breach, 1e-4f);
        assertTrue(breach > plain);
    }

    /**
     * A crit needs the charge past the gate and the attacker to be off the ground and sinking, which is
     * exactly the state a falling attacker is in.
     */
    @Test
    void fallingAttackerCrits()
    {
        DuelSim sim = grounded(2.0);
        sim.a.setLoadout(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);
        sim.a.y = 4.0;
        sim.a.onGround = false;
        sim.a.vy = -0.4;
        sim.a.fallDistance = 5.0;
        assertEquals(22.0f * CombatMath.CRIT_MULTIPLIER, SmashTiming.smashDamage(5.0, true, 0, 0.0f, 1.0f), 1e-4f);
        sim.a.sprinting = true;
        assertFalse(SmashTiming.canCrit(sim.a, true), "a sprinting attacker cannot crit");
        sim.a.onGround = true;
        assertFalse(SmashTiming.canCrit(sim.a, true), "an attacker on the ground cannot crit");
    }

    /**
     * The stun slam. A raised shield needs BlocksAttacks.blockDelaySeconds of 0.25f, so 5 ticks, to start
     * blocking, and an axe hit that it absorbs puts the shield on cooldown for round(5.0f * 1.0f * 20) = 100
     * ticks. The mace has Weapon(1), whose disableBlockingForSeconds is 0, so a smash on its own never opens
     * a window.
     */
    @Test
    void stunSlamTiming()
    {
        assertEquals(5, SmashTiming.SHIELD_RAISE_TICKS);
        assertEquals(100, SmashTiming.AXE_DISABLE_TICKS);
        assertFalse(SmashTiming.shieldBlocks(true, 10, 14), "not blocking yet");
        assertTrue(SmashTiming.shieldBlocks(true, 10, 15), "blocking from five ticks after it went up");
        assertFalse(SmashTiming.shieldBlocks(false, 10, 500), "a fighter that is not blocking never blocks");

        assertTrue(SmashTiming.stunSlamFits(20, 21), "the mace on the next tick");
        assertTrue(SmashTiming.stunSlamFits(20, 119));
        assertFalse(SmashTiming.stunSlamFits(20, 120), "the shield is back after 100 ticks");
        assertFalse(SmashTiming.stunSlamFits(20, 20), "the mace cannot land on the axe's own tick");
        assertFalse(SmashTiming.stunSlamFits(-1, 21), "without an axe hit there is no window");
    }

    /**
     * Walking in with an axe first and the mace on the next tick reaches a target whose shield came up five ticks
 * before the swing. The axe hit is absorbed, so it deals nothing, but it puts the shield on cooldown for a
     * hundred ticks and the mace lands inside that window.
     */
    @Test
    void stunSlamWalkIn()
    {
        DuelSim sim = grounded(4.0);
        sim.a.setLoadout(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);
        sim.b.setLoadout(8.0, 1.6, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);
        sim.a.ticksSinceSwing = 100;
        SmashTiming.StunSlam plan = SmashTiming.planStunSlam(sim, 0, true, -100, 200);
        assertTrue(plan.axeHitTick >= 0, "the axe reached the target");
        assertTrue(plan.maceHitTick > plan.axeHitTick, "the mace came after the axe");
        assertTrue(plan.shieldAbsorbedAxe, "the raised shield ate the axe hit");
        assertTrue(SmashTiming.stunSlamFits(plan.axeHitTick, plan.maceHitTick));
        assertEquals(0.0f, plan.axeDamage, 0.0f);
        assertTrue(plan.lands(), "the pair lands");
        assertTrue(plan.maceDamage > 0.0f, "the mace still hurt: " + plan.maceDamage);

        DuelSim unraised = grounded(4.0);
        unraised.a.setLoadout(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);
        unraised.b.setLoadout(8.0, 1.6, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);
        unraised.a.ticksSinceSwing = 100;
        SmashTiming.StunSlam open = SmashTiming.planStunSlam(unraised, 0, false, -100, 200);
        assertFalse(open.shieldAbsorbedAxe);
        assertTrue(open.axeDamage > 0.0f, "nothing to absorb it: " + open.axeDamage);
    }

    /**
     * The first tick a falling attacker can reach the target, and the fall distance it has by then. Two blocks
     * apart the wind charge arc peaks at 6.93 blocks, and the attacker only drops back inside the target's reach
     * once it is about three blocks above it, which is 23 ticks after the burst and 3.94 blocks of fall.
     */
    @Test
    void firstSmashTickAfterAWindCharge()
    {
        DuelSim sim = grounded(2.0);
        sim.a.setLoadout(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);
        sim.b.setLoadout(8.0, 1.6, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);
        sim.a.ticksSinceSwing = 100;
        assertTrue(sim.windCharge(0, 0.0, 0.25, 0.0));
        int tick = SmashTiming.smashTick(sim, 0, 100);
        assertEquals(23, tick);
        assertTrue(SmashTiming.canSmash(sim.a));
        assertEquals(3.940, sim.a.fallDistance, 1e-2);
        assertFalse(sim.a.onGround, "the attacker is still in the air");
        float damage = SmashTiming.damageDealt(sim.a, sim.b, 0, sim.a.fallDistance);
        assertEquals(20.0f, sim.b.health, 0.0f, "planning does not move the target's health");
        assertTrue(damage > 6.0f, "the swing is worth more than a plain hit: " + damage);
    }

    /** An ender pearl moves the thrower to the target and clears its fall distance, and costs five health. */
    @Test
    void pearlTeleport()
    {
        DuelSim sim = grounded(20.0);
        sim.a.y = 5.0;
        sim.a.onGround = false;
        sim.a.fallDistance = 3.0;
        assertTrue(sim.pearl(0, 0.0, 0.0, 2.0));
        assertEquals(2.0, sim.a.z, 1e-9);
        assertEquals(0.0, sim.a.fallDistance, 0.0);
        assertEquals(0.0, sim.a.vy, 0.0);
        assertEquals(DuelSim.PEARL_COOLDOWN, sim.a.pearlCooldown);
    }

    /**
     * The post hit state a chained smash is planned from: MaceItem.hurtEnemy pins the vertical velocity to
     * 0.01, MaceItem.postHurtEnemy clears the fall distance, and the Wind Burst explosion then adds its level
     * multiplier straight up because it bursts on the attacker itself.
     */
    @Test
    void smashHitAndWindBurst()
    {
        DuelSim sim = grounded(2.0);
        sim.a.fallDistance = 5.0;
        sim.a.vy = -0.8;
        assertEquals(1.2, DuelSim.WIND_BURST_KNOCKBACK[0], 0.0);
        sim.a.windBurstLevel = 0;
        sim.smashHit(0);
        assertEquals(0.01, sim.a.vy, 1e-9);
        assertEquals(0.0, sim.a.fallDistance, 0.0);

        for (int level = 1; level <= 3; level++)
        {
            DuelSim burst = grounded(2.0);
            burst.a.windBurstLevel = level;
            burst.a.fallDistance = 5.0;
            burst.smashHit(0);
            assertEquals(0.01 + DuelSim.WIND_BURST_KNOCKBACK[level - 1], burst.a.vy, 1e-6, "level " + level);
            assertEquals(0.0, burst.a.fallDistance, 0.0);
        }
    }

    /**
     * A level glide sinks at about 0.48 blocks a tick, which Entity.checkFallDistanceAccumulation clamps back to
     * 1 while the mace needs more than 1.5. A 60 degree dive sinks far faster than that, so the pin does not
     * hold and the fall distance builds up during the glide itself.
     */
    @Test
    void glidingPinsTheFallDistance()
    {
        DuelSim sim = grounded(2.0);
        sim.a.y = 20.0;
        sim.a.onGround = false;
        sim.a.vy = -0.2;
        sim.startGlide(0, 0.0);
        assertFalse(SmashTiming.canSmash(sim.a), "gliding, so no smash");
        for (int i = 0; i < 5; i++)
        {
            sim.step(NOOP, NOOP);
        }
        assertTrue(sim.a.vy > -0.5, "a level glide sinks shallowly: " + sim.a.vy);
        assertTrue(sim.a.fallDistance <= 1.0, "pinned to " + sim.a.fallDistance);
        sim.stopGlide(0);
        for (int i = 0; i < 5; i++)
        {
            sim.step(NOOP, NOOP);
        }
        assertTrue(sim.a.fallDistance > 1.0, "accumulating again: " + sim.a.fallDistance);

        DuelSim dive = grounded(2.0);
        dive.a.y = 20.0;
        dive.a.onGround = false;
        dive.a.vy = -0.2;
        dive.startGlide(0, 60.0);
        for (int i = 0; i < 20; i++)
        {
            dive.step(NOOP, NOOP);
        }
        assertTrue(dive.a.vy < -0.5, "a steep dive sinks fast: " + dive.a.vy);
        assertTrue(dive.a.fallDistance > 1.0, "a dive builds fall distance while gliding: " + dive.a.fallDistance);
    }

    private static EngagePlanner.Threat quietThreat()
    {
        EngagePlanner.Threat threat = new EngagePlanner.Threat();
        threat.swingPeriod = 0;
        return threat;
    }

    private static DuelSim duel(double distance, float targetArmor, float targetToughness, float targetHealth)
    {
        return duel(distance, targetArmor, targetToughness, targetHealth, 0.0);
    }

    private static DuelSim duel(double distance, float targetArmor, float targetToughness, float targetHealth,
                                double attackerY)
    {
        DuelSim sim = grounded(distance);
        sim.a.setLoadout(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);
        sim.b.setLoadout(8.0, 1.6, 0.0f, targetArmor, targetToughness, 0.0f, 0.0);
        sim.b.health = targetHealth;
        sim.a.ticksSinceSwing = 100;
        if (attackerY > 0.0)
        {
            sim.a.y = attackerY;
            sim.a.onGround = false;
            sim.a.vy = -0.3;
        }
        return sim;
    }

    /**
     * Four and a half blocks from a netherite armoured target. A plain 6 damage swing is cut to 1.49 by the
     * armour, and walking in takes six ticks to swing. The wind charge spends 23 ticks in the air but comes
     * down with 3.94 blocks of fall: the bonus is not charge scaled, so 6 + 13.88 = 19.88, times the 1.5 crit
     * for a falling attacker, still gets 13.08 past the armour. Only the smash is worth the flight.
     */
    @Test
    void plannerPrefersTheWindChargeAgainstHeavyArmour()
    {
        DuelSim sim = duel(4.5, 20.0f, 12.0f, 20.0f);
        EngagePlanner.Option[] options = EngagePlanner.choose(sim, 0, 0, quietThreat(), 120);
        EngagePlanner.Option best = EngagePlanner.best(options);
        assertNotNull(best);
        assertEquals(EngagePlanner.WIND_CHARGE, best.approach, describe(options));
        EngagePlanner.Option walk = options[EngagePlanner.WALK_IN];
        assertTrue(walk.feasible);
        assertEquals(6, walk.ticks);
        assertEquals(1.4888f, walk.dealt, 1e-3f, "a plain hit barely dents netherite");
        assertEquals(23, best.ticks);
        assertEquals(13.078f, best.dealt, 1e-2f);
        assertTrue(best.fallDistance > CombatMath.SMASH_FALL_THRESHOLD);
        assertTrue(best.score() > walk.score(), describe(options));
    }

    /** The same fight with one hit of health left: the smash's extra damage is wasted, so the short walk wins. */
    @Test
    void plannerWalksInForTheLastHit()
    {
        DuelSim sim = duel(4.5, 0.0f, 0.0f, 1.0f);
        EngagePlanner.Option[] options = EngagePlanner.choose(sim, 0, 0, quietThreat(), 120);
        EngagePlanner.Option best = EngagePlanner.best(options);
        assertNotNull(best);
        assertEquals(EngagePlanner.WALK_IN, best.approach, describe(options));
        assertEquals(1.0f, options[EngagePlanner.WALK_IN].dealt, 1e-6f, "capped at the health left");
        assertEquals(1.0f, options[EngagePlanner.WIND_CHARGE].dealt, 1e-6f);
        assertTrue(options[EngagePlanner.WALK_IN].ticks < options[EngagePlanner.WIND_CHARGE].ticks,
                describe(options));
    }

    /**
     * The airborne window is what the risk term prices. Against a target that always has a swing ready, the
     * flight spends its whole descent inside the target's reach and eats 14.43 health, while the walk in is
     * gone before the first swing lands.
     */
    @Test
    void plannerPricesTheAirborneWindow()
    {
        EngagePlanner.Threat threat = new EngagePlanner.Threat();
        threat.swingPeriod = 1;
        threat.ticksSinceSwing = 0;
        DuelSim sim = duel(2.5, 0.0f, 0.0f, 20.0f);
        EngagePlanner.Option[] options = EngagePlanner.choose(sim, 0, 0, threat, 120);
        EngagePlanner.Option best = EngagePlanner.best(options);
        assertNotNull(best);
        assertEquals(EngagePlanner.WALK_IN, best.approach, describe(options));
        EngagePlanner.Option flight = options[EngagePlanner.WIND_CHARGE];
        assertEquals(0.0f, options[EngagePlanner.WALK_IN].taken, 0.0f);
        assertEquals(14.4294f, flight.taken, 1e-3f, describe(options));
        assertTrue(flight.score() < options[EngagePlanner.WALK_IN].score(), describe(options));
    }

    /**
     * An elytra dive needs the height to glide down from, so it is off the table on the flat. From twelve blocks
     * up it is in, and it dives at 60 degrees until it is close enough to close the elytra and drop the rest.
     */
    @Test
    void elytraDiveNeedsHeight()
    {
        EngagePlanner.Threat threat = quietThreat();
        EngagePlanner.Option[] flat = EngagePlanner.choose(duel(3.0, 0.0f, 0.0f, 20.0f, 0.0), 0, 0, threat, 200);
        assertFalse(flat[EngagePlanner.ELYTRA].feasible, describe(flat));

        EngagePlanner.Option[] high = EngagePlanner.choose(duel(3.0, 0.0f, 0.0f, 20.0f, 12.0), 0, 0, threat, 200);
        EngagePlanner.Option dive = high[EngagePlanner.ELYTRA];
        assertTrue(dive.feasible, describe(high));
        assertEquals(17, dive.ticks);
        assertEquals(10.38, dive.fallDistance, 0.05);
    }

    private static String describe(EngagePlanner.Option[] options)
    {
        StringBuilder text = new StringBuilder();
        for (EngagePlanner.Option option : options)
        {
            text.append(option.approach).append('{').append(option.feasible).append(", ticks=").append(option.ticks)
                    .append(", dealt=").append(option.dealt).append(", taken=").append(option.taken)
                    .append(", fd=").append(option.fallDistance).append("} ");
        }
        return text.toString();
    }
}
