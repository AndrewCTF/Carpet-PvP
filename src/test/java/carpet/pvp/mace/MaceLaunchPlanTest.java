package carpet.pvp.mace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import carpet.pvp.sim.DuelSim;
import carpet.pvp.sim.EngagePlanner;
import carpet.pvp.sim.SmashTiming;
import org.junit.jupiter.api.Test;

/**
 * The window a mace fighter launches in, as the model prices it. The style keeps its distance inside
 * that window and stops swinging there, because the launch is only worth more than walking in when the
 * cooldown it is charged at is still filling.
 */
class MaceLaunchPlanTest
{
    /** A fighter with the kit's density mace at the given distance from a chestplated target. */
    private static DuelSim duel(double gap, int ticksSinceSwing)
    {
        DuelSim sim = new DuelSim();
        sim.a.x = 0.0;
        sim.a.z = 0.0;
        sim.a.y = 0.0;
        sim.a.sinYaw = 0.0;
        sim.a.cosYaw = 1.0;
        sim.a.onGround = true;
        sim.a.setLoadout(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        sim.a.densityLevel = 5;
        sim.a.ticksSinceSwing = ticksSinceSwing;
        sim.b.x = 0.0;
        sim.b.z = gap;
        sim.b.y = 0.0;
        sim.b.onGround = true;
        sim.b.setLoadout(1.0, 1.6, 0.0F, 8.0F, 2.0F, 0.0F, 0.0F);
        sim.b.ticksSinceSwing = 100;
        return sim;
    }

    /** A target that never swings back, which is what a bot fighting a standing dummy plans against. */
    private static EngagePlanner.Threat idle()
    {
        EngagePlanner.Threat threat = new EngagePlanner.Threat();
        threat.baseDamage = 1.0F;
        threat.swingPeriod = 12;
        threat.ticksSinceSwing = 100;
        return threat;
    }

    private static int best(double gap, int ticksSinceSwing)
    {
        EngagePlanner.Option best = EngagePlanner.best(
                EngagePlanner.choose(duel(gap, ticksSinceSwing), 0, 0, idle(), 40));
        return best == null ? -1 : best.approach;
    }

    /**
     * Four and a half blocks out, where the wind charge's arc lands a smash for twenty and walking in takes
     * six ticks for a hit the chestplate leaves at five, the launch is worth more.
     */
    @Test
    void aLaunchIsWorthMoreThanWalkingInAtRange()
    {
        assertEquals(EngagePlanner.WIND_CHARGE, best(4.5, 100));
        assertEquals(EngagePlanner.WIND_CHARGE, best(6.0, 100));
        EngagePlanner.Option[] options = EngagePlanner.choose(duel(4.5, 100), 0, 0, idle(), 40);
        assertEquals(15, options[EngagePlanner.WIND_CHARGE].ticks);
        assertEquals(20.0F, options[EngagePlanner.WIND_CHARGE].dealt, 1e-3F);
    }

    /** Up close the arc cannot come down on the target and a plain hit is worth more than the flight. */
    @Test
    void walkingInWinsUpClose()
    {
        assertEquals(EngagePlanner.WALK_IN, best(2.0, 100));
        assertEquals(EngagePlanner.WALK_IN, best(3.0, 100));
    }

    /**
     * With the cooldown spent the walk in has to wait for it before it can swing, which is what makes the
     * bot stand off and let its mace come up rather than swing it away.
     */
    @Test
    void aSpentCooldownMakesALaunchWorthItAtAnyRange()
    {
        assertEquals(EngagePlanner.WIND_CHARGE, best(2.0, 10));
        assertNotEquals(EngagePlanner.WALK_IN, best(2.5, 10));
    }

    /**
     * Straight after a swing the mace is thirty ticks from its charge gate and the arc is over before then,
     * so there is no launch to be had at all: the bot has to wait for the cooldown before it may throw one.
     */
    @Test
    void aColdMaceCannotSmashOutOfALaunch()
    {
        EngagePlanner.Option[] options = EngagePlanner.choose(duel(4.5, 0), 0, 0, idle(), 40);
        assertEquals(EngagePlanner.WALK_IN, EngagePlanner.best(options).approach);
        assertEquals(false, options[EngagePlanner.WIND_CHARGE].feasible, "no charged swing before the arc ends");
    }
}
