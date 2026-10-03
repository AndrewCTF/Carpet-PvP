package carpet.pvp.look;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LookControllerTest
{
    private static final double TOL = 1e-3;

    private static LookProfile typical(float sens)
    {
        return LookProfile.ofSkill(0.5, sens);
    }

    private static LookProfile noiseless(LookProfile p)
    {
        return new LookProfile(p.sensitivity(), p.reactionMinMs(), p.reactionMaxMs(), p.fittsA(), p.fittsB(),
                0.0, p.maxDegPerTick(), p.trackRangeDegrees());
    }

    private static LookProfile withNoise(LookProfile p, double noise)
    {
        return new LookProfile(p.sensitivity(), p.reactionMinMs(), p.reactionMaxMs(), p.fittsA(), p.fittsB(),
                noise, p.maxDegPerTick(), p.trackRangeDegrees());
    }

    /** Runs the controller toward a fixed target; returns per-tick yaw, pitch pairs and changed flags. */
    private static final class Run
    {
        final double[] yaw;
        final double[] pitch;
        final boolean[] changed;

        Run(LookController c, float startYaw, float startPitch, float ty, float tp, float r, int ticks)
        {
            yaw = new double[ticks + 1];
            pitch = new double[ticks + 1];
            changed = new boolean[ticks + 1];
            c.reset(startYaw, startPitch);
            yaw[0] = c.yaw();
            pitch[0] = c.pitch();
            for (int i = 1; i <= ticks; i++)
            {
                c.aimAt(ty, tp, r);
                c.tick();
                yaw[i] = c.yaw();
                pitch[i] = c.pitch();
                changed[i] = c.changed();
            }
        }

        double dy(int i)
        {
            return yaw[i] - yaw[i - 1];
        }

        double dp(int i)
        {
            return pitch[i] - pitch[i - 1];
        }
    }

    private static double wrapped(double d)
    {
        return d - 360.0 * Math.rint(d / 360.0);
    }

    @Test
    void staticTargetIsReached()
    {
        float[][] targets = {{3f, 1f}, {20f, -10f}, {120f, 45f}, {-170f, 0f}};
        for (float[] t : targets)
        {
            LookController c = new LookController(typical(0.5f), new Random(7));
            Run r = new Run(c, 0f, 0f, t[0], t[1], 0.5f, 150);
            double ey = wrapped(t[0] - r.yaw[150]);
            double ep = t[1] - r.pitch[150];
            assertTrue(Math.hypot(ey, ep) <= 0.5, "target " + t[0] + "," + t[1] + " missed by " + Math.hypot(ey, ep));
        }
    }

    @Test
    void changesAreMultiplesOfMouseGrid()
    {
        for (float sens : new float[] {0.5f, 0.2f, 1.0f})
        {
            LookProfile p = typical(sens);
            double g = p.grid();
            LookController c = new LookController(p, new Random(3));
            Run r = new Run(c, 10f, 5f, 100f, -40f, 0.5f, 150);
            boolean moved = false;
            for (int i = 1; i <= 150; i++)
            {
                assertEquals(0.0, Math.abs(r.dy(i) / g - Math.rint(r.dy(i) / g)) * g, TOL, "yaw step");
                assertEquals(0.0, Math.abs(r.dp(i) / g - Math.rint(r.dp(i) / g)) * g, TOL, "pitch step");
                moved |= r.changed[i];
            }
            assertTrue(moved);
        }
        assertEquals(0.15, typical(0.5f).grid(), 1e-9);
    }

    @Test
    void speedLimitHoldsAndSpeedRisesThenFalls()
    {
        LookProfile p = noiseless(typical(0.5f));
        LookController c = new LookController(p, new Random(1));
        Run r = new Run(c, 0f, 0f, 150f, 0f, 0.5f, 200);
        int peak = -1;
        int last = -1;
        double peakSpeed = 0;
        for (int i = 1; i <= 200; i++)
        {
            double s = Math.abs(r.dy(i));
            assertTrue(s <= p.maxDegPerTick() + TOL, "tick " + i + " speed " + s);
            if (s > peakSpeed)
            {
                peakSpeed = s;
                peak = i;
            }
            if (s > 0)
            {
                last = i;
            }
        }
        int first = 1;
        while (r.dy(first) == 0)
        {
            first++;
        }
        assertTrue(peak > first, "peak must not be first moving tick");
        assertTrue(peak < last, "peak must not be last moving tick");
        assertTrue(Math.abs(r.dy(first)) < peakSpeed);
        assertTrue(Math.abs(r.dy(last)) < peakSpeed);
    }

    @Test
    void yawBoundaryTakesTheShortWay()
    {
        float[][] cases = {{170f, -170f}, {-170f, 170f}, {179f, -179f}};
        for (float[] cs : cases)
        {
            LookProfile p = typical(0.5f);
            LookController c = new LookController(p, new Random(5));
            Run r = new Run(c, cs[0], 0f, cs[1], 0f, 0.5f, 150);
            for (int i = 1; i <= 150; i++)
            {
                assertTrue(Math.abs(r.dy(i)) <= p.maxDegPerTick() + TOL);
                assertTrue(Math.abs(r.dy(i)) < 320);
            }
            assertTrue(Math.abs(wrapped(cs[1] - r.yaw[150])) <= 0.5 + TOL);
            assertTrue(Math.abs(r.yaw[150] - cs[0]) < 30, "went the long way");
        }
    }

    @Test
    void nothingMovesDuringReactionDelay()
    {
        LookProfile base = typical(0.5f);
        LookProfile p = new LookProfile(0.5f, 250, 250, base.fittsA(), base.fittsB(), 0.05,
                base.maxDegPerTick(), base.trackRangeDegrees());
        int delay = p.reactionTicks(new Random(0));
        assertEquals(5, delay);
        LookController c = new LookController(p, new Random(2));
        Run r = new Run(c, 0f, 0f, 60f, 20f, 0.5f, 40);
        for (int i = 1; i <= delay; i++)
        {
            assertFalse(r.changed[i], "moved at tick " + i);
            assertEquals(0.0, r.yaw[i], 0.0);
        }
        boolean moved = false;
        for (int i = delay + 1; i <= 40; i++)
        {
            moved |= r.changed[i];
        }
        assertTrue(moved);
    }

    @Test
    void reactionDelayAppliesAfterMidFlightTargetJump()
    {
        LookProfile base = noiseless(typical(0.5f));
        LookProfile p = new LookProfile(0.5f, 250, 250, base.fittsA(), base.fittsB(), 0.0,
                base.maxDegPerTick(), base.trackRangeDegrees());
        LookController c = new LookController(p, new Random(2));
        c.reset(0, 0);
        for (int i = 0; i < 12; i++)
        {
            c.aimAt(90f, 0f, 0.5f);
            c.tick();
        }
        float held = c.yaw();
        for (int i = 0; i < 5; i++)
        {
            c.aimAt(-90f, 0f, 0.5f);
            c.tick();
            assertFalse(c.changed());
            assertEquals(held, c.yaw(), 0f);
        }
    }

    @Test
    void movementTimeGrowsWithDistanceAndShrinksWithWidth()
    {
        LookProfile p = typical(0.5f);
        assertTrue(p.movementTicks(10, 1) < p.movementTicks(40, 1));
        assertTrue(p.movementTicks(40, 1) < p.movementTicks(160, 1));
        assertTrue(p.movementTicks(60, 8) < p.movementTicks(60, 1));
        assertTrue(p.movementTicks(60, 1) < p.movementTicks(60, 0.1));
        assertTrue(p.movementTicks(0, 1) >= 1);

        // Behavioural: noiseless controller with no speed limit follows the Fitts time exactly.
        LookProfile free = new LookProfile(0.5f, 100, 100, p.fittsA(), p.fittsB(), 0.0, 1000, 4.0);
        int near = ticksToArrive(free, 30f, 0.5f);
        int far = ticksToArrive(free, 120f, 0.5f);
        int wide = ticksToArrive(free, 120f, 10f);
        assertTrue(near < far, near + " vs " + far);
        assertTrue(wide < far, wide + " vs " + far);
    }

    private static int ticksToArrive(LookProfile p, float yawTarget, float r)
    {
        LookController c = new LookController(p, new Random(4));
        c.reset(0, 0);
        for (int i = 1; i <= 400; i++)
        {
            c.aimAt(yawTarget, 0f, r);
            c.tick();
            if (Math.abs(yawTarget - c.yaw()) <= r)
            {
                return i;
            }
        }
        throw new AssertionError("never arrived");
    }

    @Test
    void noNoiseNeedsNoCorrection()
    {
        for (float d : new float[] {5f, 40f, 150f})
        {
            LookController c = new LookController(noiseless(typical(0.5f)), new Random(9));
            Run r = new Run(c, 0f, 0f, d, 10f, 0.5f, 200);
            assertEquals(0, c.correctionsPlanned());
            assertTrue(Math.hypot(d - r.yaw[200], 10 - r.pitch[200]) <= 0.5);
        }
    }

    @Test
    void largeNoiseTriggersCorrectionAndStillArrives()
    {
        LookController c = new LookController(withNoise(typical(0.5f), 0.2), new Random(11));
        Run r = new Run(c, 0f, 0f, 100f, 0f, 0.5f, 400);
        assertTrue(c.correctionsPlanned() >= 1);
        assertTrue(Math.hypot(100 - r.yaw[400], r.pitch[400]) <= 0.5);
    }

    @Test
    void seededNoisyRunIsReproducible()
    {
        Run a = new Run(new LookController(typical(0.5f), new Random(42)), 0f, 0f, 80f, -20f, 0.5f, 150);
        Run b = new Run(new LookController(typical(0.5f), new Random(42)), 0f, 0f, 80f, -20f, 0.5f, 150);
        assertArrayEquals(a.yaw, b.yaw);
        assertArrayEquals(a.pitch, b.pitch);
    }

    @Test
    void changedFlagMatchesMovementAndNeverRepeatsAngles()
    {
        LookController c = new LookController(typical(0.5f), new Random(8));
        Run r = new Run(c, 0f, 0f, 100f, -30f, 0.5f, 150);
        for (int i = 1; i <= 150; i++)
        {
            boolean moved = r.yaw[i] != r.yaw[i - 1] || r.pitch[i] != r.pitch[i - 1];
            assertEquals(moved, r.changed[i], "tick " + i);
        }
        for (int i = 2; i <= 150; i++)
        {
            if (r.changed[i] && r.changed[i - 1])
            {
                assertTrue(r.yaw[i] != r.yaw[i - 1] || r.pitch[i] != r.pitch[i - 1]);
            }
        }
        assertNotEquals(0, r.yaw[150]);
    }

    @Test
    void pitchStaysClamped()
    {
        LookController c = new LookController(withNoise(typical(0.5f), 0.3), new Random(6));
        Run r = new Run(c, 0f, 80f, 20f, 90f, 0.5f, 200);
        for (int i = 0; i <= 200; i++)
        {
            assertTrue(r.pitch[i] <= 90.0 && r.pitch[i] >= -90.0);
        }
    }
}
