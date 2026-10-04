package carpet.logic.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveLimiterTest
{
    private static final long START = 1_000_000L;

    @Test
    void aSessionMayWriteSoOftenInAMinuteAndThenWaits()
    {
        SaveLimiter limiter = new SaveLimiter();
        Object session = new Object();
        for (int i = 0; i < SaveLimiter.WRITES_PER_MINUTE; i++)
        {
            assertEquals(0, limiter.admit(session, START + i * 100L), "write " + (i + 1));
        }

        assertEquals(60_000L - 10_000L, limiter.admit(session, START + 10_000L), "the wait is what is left of the minute");
        assertEquals(0, limiter.admit(new Object(), START + 10_000L), "another session is not held up by it");
        assertEquals(0, limiter.admit(session, START + 60_000L), "and the minute after starts afresh");
    }

    @Test
    void anEditorThatSavesByItselfStaysFarBelowTheLimit()
    {
        // One save two seconds after the last change at most: thirty a minute from one tab.
        assertTrue(SaveLimiter.WRITES_PER_MINUTE >= 60);
    }

    @Test
    void theTableOfSessionsIsBounded()
    {
        SaveLimiter limiter = new SaveLimiter();
        for (int i = 0; i < 256; i++)
        {
            assertEquals(0, limiter.admit("session " + i, START));
        }
        assertTrue(limiter.admit("one more", START) > 0, "a full table takes no new session");
        assertEquals(0, limiter.admit("one more", START + 60_000L), "until the minute is over");
    }
}
