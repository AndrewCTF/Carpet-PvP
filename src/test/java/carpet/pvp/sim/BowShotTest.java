package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BowShotTest
{
    @Test
    void erfIsTheErrorFunctionAtItsUsualLandmarks()
    {
        assertEquals(0.0, BowShot.erf(0.0), 1.0E-9);
        assertEquals(0.9999778948, BowShot.erf(3.0), 1.0E-6);
        assertEquals(-0.9999778948, BowShot.erf(-3.0), 1.0E-6);
        assertEquals(0.8427007929, BowShot.erf(1.0), 1.0E-6);
        assertEquals(0.9953222650, BowShot.erf(2.0), 1.0E-6);
    }

    @Test
    void aPerfectShotAlwaysLands()
    {
        assertEquals(1.0, BowShot.hitChance(0.0, 20.0), 1.0E-9);
        assertEquals(1.0, BowShot.hitChance(0.0, 0.5), 1.0E-9);
    }

    @Test
    void aFullDrawAddsAboutFourTenthsOfADegreeOfSpread()
    {
        // RandomSource.triangle(0, spread) has the variance spread^2 / 6, and a full draw is a spread of
        // 0.0172275 direction units, which is 0.403 degrees at the standard deviation.
        assertEquals(0.403, BowShot.arrowSigmaDegrees(1.0), 0.01);
        assertEquals(0.0, BowShot.arrowSigmaDegrees(0.0), 1.0E-12);
        // A shot at the very edge of what a bow will fire still carries half the power, and with it the spread.
        assertEquals(0.202, BowShot.arrowSigmaDegrees(0.5), 0.01);
    }

    @Test
    void twoErrorsAddUpInQuadrature()
    {
        assertEquals(5.0, BowShot.combined(3.0, 4.0), 1.0E-9);
    }

    @Test
    void aHitFallsOffAsTheTargetGetsFurtherAway()
    {
        double near = BowShot.hitChance(1.0, 4.0);
        double far = BowShot.hitChance(1.0, 24.0);
        assertTrue(near > far, "4 blocks: " + near + ", 24 blocks: " + far);
        assertEquals(1.0, BowShot.hitChance(0.01, 4.0), 1.0E-6);
        assertTrue(BowShot.hitChance(1.0, 5.0) > BowShot.hitChance(1.0, 20.0));
    }

    @Test
    void theRateTheErrorPredictsAtTwentyBlocks()
    {
        // A player box is 0.6 wide and 1.8 high, so at 20 blocks it covers about 0.86 degrees across and 2.58 up.
        // A standard deviation of one degree on each axis is therefore about erf(0.61) across and erf(1.82) up,
        // so roughly three shots in five.
        assertEquals(0.604, BowShot.hitChance(1.0, 20.0), 0.01);
        // The arrow's own spread at a full draw takes a little off that, to about 0.565, which is why a bot
        // that aims well still hits most of its shots.
        assertEquals(0.565, BowShot.hitChance(1.0, 20.0, 1.0), 0.01);
    }

    @Test
    void theCrosswaysChanceIsWhatLimitsALongShot()
    {
        // Far out the box is much taller than it is wide, so the yaw error decides the shot rather than the
        // pitch error, and widening the error costs a long shot far more than a close one.
        double atFive = BowShot.hitChance(1.0, 5.0) - BowShot.hitChance(2.0, 5.0);
        double atTwenty = BowShot.hitChance(1.0, 20.0) - BowShot.hitChance(2.0, 20.0);
        assertTrue(atTwenty > atFive, "5 blocks: " + atFive + ", 20 blocks: " + atTwenty);
    }
}
