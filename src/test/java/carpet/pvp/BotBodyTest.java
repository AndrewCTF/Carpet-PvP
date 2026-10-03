package carpet.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.BotBody.ClickLimiter;
import carpet.pvp.BotBody.SprintLock;
import org.junit.jupiter.api.Test;

class BotBodyTest
{
    @Test
    void clickLimiterSpreadsClicksOverTime()
    {
        ClickLimiter limiter = new ClickLimiter(10.0);
        assertEquals(2, limiter.period());
        assertTrue(limiter.tick(), "the first click is free");
        assertFalse(limiter.tick(), "the next tick is too early");
        assertTrue(limiter.tick());
        assertFalse(limiter.tick());
    }

    @Test
    void clickLimiterRateFollowsClicksPerSecond()
    {
        assertEquals(1, new ClickLimiter(20.0).period());
        assertEquals(4, new ClickLimiter(5.0).period());
        assertEquals(20, new ClickLimiter(1.0).period());
        assertEquals(1, new ClickLimiter(0.0).period(), "zero clicks still ticks once in a while");
        assertEquals(1, new ClickLimiter(-3.0).period());
    }

    @Test
    void clickLimiterNeverAllowsMoreClicksThanTheRate()
    {
        ClickLimiter limiter = new ClickLimiter(10.0);
        int clicks = 0;
        for (int tick = 0; tick < 200; tick++)
        {
            if (limiter.tick())
            {
                clicks++;
            }
        }
        assertEquals(100, clicks);
    }

    @Test
    void sprintLockNeedsAReleaseTickBeforeSprintComesBack()
    {
        SprintLock lock = new SprintLock();
        assertTrue(lock.apply(true), "sprint is available before the first sprint hit");
        lock.onSprintHit();
        assertTrue(lock.locked());
        assertFalse(lock.apply(true), "a sprint hit clears sprint server side, so it cannot be pressed");
        assertTrue(lock.locked(), "and the key still being down does not get it back");
        assertFalse(lock.apply(false), "the release tick of the W-tap is not sprinting");
        assertFalse(lock.locked(), "and it gives the lock back");
        assertTrue(lock.apply(true), "sprint works again on the next tick");
    }

    @Test
    void sprintLockWithoutASprintHitNeverBlocks()
    {
        SprintLock lock = new SprintLock();
        for (int tick = 0; tick < 20; tick++)
        {
            assertTrue(lock.apply(true));
            lock.apply(false);
        }
    }

    @Test
    void aRayMeetsABoxOnlyInFrontOfTheOrigin()
    {
        double[] box = {-0.3, 0.0, 1.7, 0.3, 1.8, 2.3};
        assertTrue(ray(0.0, 1.62, 0.0, 0.0, 0.0, 1.0, box), "straight ahead");
        assertTrue(ray(0.0, 1.62, 0.0, 0.0, -0.1, 0.99, box), "aimed a little low but still on the box");
        assertFalse(ray(0.0, 1.62, 0.0, 0.0, 0.0, -1.0, box), "the box is behind the bot");
        assertFalse(ray(0.0, 1.62, 0.0, 1.0, 0.0, 0.0, box), "the bot is looking the other way");
        assertFalse(ray(0.0, 2.5, 0.0, 0.0, 0.0, 1.0, box), "above the box");
        assertFalse(ray(0.0, 1.62, 2.5, 0.0, 0.0, 1.0, box), "the bot already stands past the box");
    }

    @Test
    void aRayParallelToABoxPlaneIsInsideOrOut()
    {
        double[] box = {-0.3, 0.0, 1.7, 0.3, 1.8, 2.3};
        assertTrue(ray(0.0, 0.9, 0.0, 0.0, 0.0, 1.0, box), "level with the box, through it");
        assertFalse(ray(0.9, 0.9, 0.0, 0.0, 0.0, 1.0, box), "level with the box, beside it");
    }

    private static boolean ray(double ox, double oy, double oz, double dx, double dy, double dz, double[] box)
    {
        return BotBody.rayHitsBox(ox, oy, oz, dx, dy, dz, box[0], box[1], box[2], box[3], box[4], box[5]);
    }
}