package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpearMathTest
{
    /** A player box standing at (x, y, z), the corners a reach test is given. */
    private static double[] box(double x, double y, double z)
    {
        return new double[] {x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3};
    }

    @Test
    void theReachWindowIsTheOneTheSpearCarries()
    {
        assertEquals(1.875, SpearMath.minReach(), 1.0E-9);
        assertEquals(4.625, SpearMath.maxReach(), 1.0E-9);
    }

    @Test
    void aSpearReachesPastWhereASwordCan()
    {
        // A sword's reach is the entity interaction range of three blocks, and the spear's window ends at 4.625.
        assertTrue(SpearMath.maxReach() > 3.0);
        double[] target = box(3.9, 0.0, 0.0);
        assertTrue(SpearMath.inReach(0.0, 1.62, 0.0, target[0], target[1], target[2], target[3], target[4],
                target[5]), "a target four blocks out is inside the spear reach and past the sword one");
    }

    @Test
    void aSpearNeedsRoomToThrustAndALongArmToReachOut()
    {
        double[] close = box(0.5, 0.0, 0.0);
        assertFalse(SpearMath.inReach(0.0, 1.62, 0.0, close[0], close[1], close[2], close[3], close[4], close[5]),
                "a target hugging the bot is inside the near limit");
        double[] far = box(5.0, 0.0, 0.0);
        assertFalse(SpearMath.inReach(0.0, 1.62, 0.0, far[0], far[1], far[2], far[3], far[4], far[5]),
                "a target five blocks out is past the far limit");
        double[] just = box(3.5, 0.0, 0.0);
        assertTrue(SpearMath.inReach(0.0, 1.62, 0.0, just[0], just[1], just[2], just[3], just[4], just[5]));
    }

    @Test
    void theMotionAlongTheViewIsTheClosingSpeed()
    {
        // Looking along +z, which is yaw zero, so the motion along the view is the z component.
        assertEquals(0.3, SpearMath.along(0.0, 0.0, 0.3, 0.0, 0.0), 1.0E-9);
        assertEquals(-0.2, SpearMath.along(0.0, 0.0, -0.2, 0.0, 0.0), 1.0E-9);
        // A yaw of ninety degrees looks along -x.
        assertEquals(0.3, SpearMath.along(-0.3, 0.0, 0.0, 90.0, 0.0), 1.0E-9);
        // Looking straight down takes the vertical component.
        assertEquals(0.4, SpearMath.along(0.0, -0.4, 0.0, 0.0, 90.0), 1.0E-9);
    }

    @Test
    void aTargetRunningAwayNeverAddsToTheThrust()
    {
        assertEquals(0.2, SpearMath.closingSpeed(0.25, 0.05), 1.0E-9);
        assertEquals(0.0, SpearMath.closingSpeed(0.25, 0.30), 1.0E-9);
    }

    @Test
    void theDamageIsTheBaseDamagePlusTheClosingSpeedTimesTheMultiplier()
    {
        // A netherite spear scales the closing speed by 1.2 and starts from the plain base damage of one.
        assertEquals(1.0F, SpearMath.thrustDamage(1.0, 0.0, 1.2), 1.0E-6);
        assertEquals(6.0F, SpearMath.thrustDamage(1.0, 4.6, 1.2), 1.0E-6);
        assertEquals(14.0F, SpearMath.thrustDamage(1.0, 11.2, 1.2), 1.0E-6);
        // The game rounds the speed term down, so a closing speed just short of five only counts four.
        assertEquals(5.0F, SpearMath.thrustDamage(1.0, 4.1, 1.2), 1.0E-6);
    }

    @Test
    void theThrustIsBehindBothAChargeAndASpeedGate()
    {
        assertTrue(SpearMath.damages(100, 175, 4.6, 4.6), "exactly at the gate counts");
        assertFalse(SpearMath.damages(100, 175, 4.59, 4.6), "a hair under the speed gate does not");
        assertFalse(SpearMath.damages(176, 175, 9.0, 4.6), "a charge that ran out does not land");
    }
}
