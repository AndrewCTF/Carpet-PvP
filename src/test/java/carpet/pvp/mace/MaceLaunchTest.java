package carpet.pvp.mace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.DuelSim;
import org.junit.jupiter.api.Test;

class MaceLaunchTest
{
    /**
     * A wind charge thrown straight down at the fighter's own feet bursts a quarter of a block above the
     * ground face under them, which the model puts at a straight 1.0929167 upwards: the same impulse
     * MaceEngagementTest reads off the burst, and the arc it lifts to is the number the self test holds
     * the game against.
     */
    @Test
    void theFeetBurstIsTheModelsNumber()
    {
        DuelSim sim = new DuelSim();
        sim.placeFacing(1000.0);
        assertTrue(sim.windCharge(0, 0.0, MaceLaunch.BURST_HEIGHT, 0.0));
        assertEquals(1.0929167, sim.a.vy, 1e-6);
    }

    @Test
    void theApexOfALaunchIsTheOnesTheModelGives()
    {
        assertEquals(6.9335757, MaceLaunch.apexOf(0.0), 1e-5);
        assertEquals(MaceLaunch.apexOf(0.0), MaceLaunch.APEX[0], 0.0);
    }

    /**
     * Bursting the charge off the fighter's feet costs some of the upward impulse and buys some forward
     * one, so a burst further along the ground lifts the fighter less but carries it further.
     */
    @Test
    void aBurstAheadLiftsLessButCarriesFurther()
    {
        assertTrue(MaceLaunch.apexOf(0.5) < MaceLaunch.apexOf(0.0));
        assertTrue(MaceLaunch.apexOf(1.0) < MaceLaunch.apexOf(0.5));
        DuelSim sim = new DuelSim();
        sim.placeFacing(1000.0);
        assertTrue(sim.windCharge(0, sim.a.sinYaw * 1.0, MaceLaunch.BURST_HEIGHT, -sim.a.cosYaw * 1.0));
        assertTrue(sim.a.vz > 0.0, "the burst ahead of the feet pushes towards the target");
        assertTrue(sim.a.vy < 1.0929167, "and it pushes less straight up");
    }

    /**
     * Let go from the top of the feet burst, the fighter falls the whole 6.93 blocks of the arc in the
     * fifteen ticks a free fall of that height takes, which is the half of the launch the descent is.
     */
    @Test
    void theDropFromAnApexTakesTheBallisticTicks()
    {
        double apex = MaceLaunch.APEX[0];
        double[] drop = MaceLaunch.drop(apex, 0.0, 0.0);
        assertEquals(15.0, drop[0], 0.0);
        assertEquals(apex, drop[1], 0.05);
        assertTrue(drop[0] < 24, "the arc is over in half a second, not a whole one");
    }

    @Test
    void aDropIsCountedInTheTicksItTakesToGetThere()
    {
        double[] drop = MaceLaunch.drop(1.0, -0.5, 0.0);
        assertEquals(2.0, drop[0], 0.0, "half a block on the first tick, the rest on the second");
        assertEquals(1.0, drop[1], 1.0E-9);
    }

    @Test
    void aChargeThrownLowBurstsUnderFeetThatAreAlreadyDown()
    {
        // Two and a bit blocks up and sinking at a launch's speed: the fighter is on its feet before the
        // charge is, so the burst is the next launch rather than a break in the fall.
        assertEquals(-1.0, MaceLaunch.burstGap(2.3, -0.9), 0.0);
        assertEquals(-1.0, MaceLaunch.burstGap(1.07, -0.3), 0.0);
    }

    @Test
    void aChargeThrownFromFurtherUpBurstsBeforeTheFeetTouch()
    {
        double gap = MaceLaunch.burstGap(4.0, -0.9);
        assertTrue(gap >= 0.0 && gap < 0.5, "four blocks up the burst catches the feet just off the ground: " + gap);
        assertTrue(MaceLaunch.burstGap(6.0, -0.9) > gap, "and from higher up it goes off further under them");
    }

    @Test
    void aLongFallHasToThrowItsChargeFromHigherUp()
    {
        // Out of twenty blocks the feet outrun a charge thrown from eight: it has to leave the hand at ten.
        assertEquals(-1.0, MaceLaunch.burstGap(8.0, -1.7), 0.0);
        double gap = MaceLaunch.burstGap(10.0, -1.7);
        assertTrue(gap > 0.0 && gap < 3.0, "inside the three blocks a fall is free from: " + gap);
    }

    @Test
    void theSmashGateIsTheOnesTheModelUses()
    {
        assertEquals(0.25, MaceLaunch.BURST_HEIGHT, 0.0);
        assertTrue(CombatMath.canSmash(CombatMath.SMASH_FALL_THRESHOLD + 0.01, false));
        assertFalse(CombatMath.canSmash(CombatMath.SMASH_FALL_THRESHOLD, false));
    }
}
