package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The swing counter a bot perceives is what a click is judged by, so it has to be the tick count the game
 * itself would charge that click with. {@link CombatMath#ticksOfCharge} is how a count is taken back out of a
 * charge, which is what tells the counter where the game's swing timer stood on the first tick of a fight; a
 * tick out there is a click thrown before it is charged.
 */
class ChargeTicksTest
{
    /** Attack speeds of the weapons a bot may pick up, slowest first. */
    private static final double[] SPEEDS = {0.4, 1.6, 2.4, 4.0};
    /** Ticks a swing has to be able to take to get past the charge ceiling. */
    private static final int TICKS = 60;
    /** How far a float of an attack charge may be read back as itself. */
    private static final float EPSILON = 1.0E-6F;

    @Test
    void aChargeReadsBackAsATickThatMadeIt()
    {
        for (double speed : SPEEDS)
        {
            for (int tick = 0; tick <= TICKS; tick++)
            {
                float charge = CombatMath.chargeScale(tick, speed);
                int back = CombatMath.ticksOfCharge(charge, speed);
                assertTrue(back <= tick, "speed " + speed + " read " + charge + " as " + back
                        + " ticks, which is more than the " + tick + " that had passed");
                assertEquals(charge, CombatMath.chargeScale(back, speed), EPSILON,
                        "speed " + speed + " at tick " + tick + " of charge");
            }
        }
    }

    @Test
    void aFullChargeSaysTheFirstTickThatReachesIt()
    {
        for (double speed : SPEEDS)
        {
            int first = CombatMath.ticksOfCharge(1.0F, speed);
            assertEquals(1.0F, CombatMath.chargeScale(first, speed), EPSILON, "speed " + speed);
            assertTrue(CombatMath.chargeScale(first - 1, speed) < 1.0F,
                    "speed " + speed + " was already charged a tick before " + first);
        }
    }

    @Test
    void nothingBeforeTheGateIsReadBackAsCharged()
    {
        for (double speed : SPEEDS)
        {
            int gate = CombatMath.minTicksForGate(speed);
            for (int tick = 0; tick < gate; tick++)
            {
                int back = CombatMath.ticksOfCharge(CombatMath.chargeScale(tick, speed), speed);
                assertTrue(!CombatMath.passesChargeGate(CombatMath.chargeScale(back, speed)),
                        "speed " + speed + " read tick " + tick + " back as a charged one");
            }
            int back = CombatMath.ticksOfCharge(CombatMath.chargeScale(gate, speed), speed);
            assertTrue(CombatMath.passesChargeGate(CombatMath.chargeScale(back, speed)),
                    "speed " + speed + " did not read its gate tick back as charged");
        }
    }
}