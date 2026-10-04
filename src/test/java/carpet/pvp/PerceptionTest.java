package carpet.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.Perception.Ring;
import carpet.pvp.Perception.Snapshot;
import org.junit.jupiter.api.Test;

class PerceptionTest
{
    private static Snapshot at(float x)
    {
        Snapshot snapshot = new Snapshot();
        snapshot.x = x;
        snapshot.seen = true;
        return snapshot;
    }

    @Test
    void emptyRingHasNothingToShow()
    {
        Ring ring = new Ring();
        assertEquals(0, ring.size());
        assertFalse(ring.latest().seen);
        assertFalse(ring.delayed(0).seen);
    }

    @Test
    void delayCountsBackFromTheNewestTick()
    {
        Ring ring = new Ring();
        for (int tick = 0; tick < 10; tick++)
        {
            ring.record(at(tick));
        }
        assertEquals(10, ring.size());
        assertEquals(9.0f, ring.latest().x);
        assertEquals(9.0f, ring.delayed(0).x);
        assertEquals(5.0f, ring.delayed(4).x);
        assertEquals(0.0f, ring.delayed(9).x);
    }

    @Test
    void delayIsClampedToWhatTheRingHolds()
    {
        Ring ring = new Ring();
        for (int tick = 0; tick < 3; tick++)
        {
            ring.record(at(tick));
        }
        // Only three ticks recorded, so a longer delay shows the oldest one rather than nothing.
        assertEquals(0.0f, ring.delayed(3).x);
        assertEquals(0.0f, ring.delayed(40).x);
    }

    @Test
    void historyKeepsFortyTicksAndDropsTheRest()
    {
        Ring ring = new Ring();
        for (int tick = 0; tick < Perception.HISTORY + 25; tick++)
        {
            ring.record(at(tick));
        }
        assertEquals(Perception.HISTORY, ring.size());
        assertEquals(Perception.HISTORY + 24.0f, ring.latest().x);
        assertEquals(25.0f, ring.delayed(Perception.HISTORY - 1).x);
    }

    @Test
    void recordingNeverAllocatesASlot()
    {
        Ring ring = new Ring();
        java.util.IdentityHashMap<Snapshot, Boolean> seen = new java.util.IdentityHashMap<>();
        for (int tick = 1; tick <= Perception.HISTORY * 3; tick++)
        {
            ring.record(at(tick));
            seen.put(ring.latest(), Boolean.TRUE);
        }
        assertEquals(Perception.HISTORY, seen.size(), "only as many snapshots as the ring is deep");
        assertEquals((float) Perception.HISTORY * 3, ring.latest().x);
    }

    @Test
    void aResetForgetsEverything()
    {
        Ring ring = new Ring();
        for (int tick = 0; tick < 5; tick++)
        {
            ring.record(at(tick));
        }
        ring.reset();
        assertEquals(0, ring.size());
        assertFalse(ring.latest().seen);
    }
}