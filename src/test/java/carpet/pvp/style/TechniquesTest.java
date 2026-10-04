package carpet.pvp.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.sim.DuelSim;
import org.junit.jupiter.api.Test;

class TechniquesTest
{
    private static final int WALK = DuelSim.action(1, 0, false, false, false);
    private static final int SPRINT = DuelSim.action(1, 0, false, true, false);
    private static final int STRAFE = DuelSim.action(1, 1, false, false, false);
    private static final int JUMP = DuelSim.action(1, 0, true, false, false);
    private static final int CRIT = DuelSim.action(1, 0, true, false, true);

    @Test
    void everythingIsAllowedWhenNothingIsForbidden()
    {
        Techniques techniques = new Techniques(true, true, true, true);
        assertTrue(techniques.test(SPRINT));
        assertTrue(techniques.test(STRAFE));
        assertTrue(techniques.test(JUMP));
        assertTrue(techniques.test(CRIT));
    }

    @Test
    void jumpCritsAreRemovedFromTheActionSet()
    {
        Techniques techniques = new Techniques(false, true, true, true);
        assertFalse(techniques.test(JUMP));
        assertFalse(techniques.test(CRIT));
        assertTrue(techniques.test(WALK), "walking is still allowed");
        assertTrue(techniques.test(STRAFE));
    }

    @Test
    void strafingIsRemovedFromTheActionSet()
    {
        Techniques techniques = new Techniques(true, false, true, true);
        assertFalse(techniques.test(STRAFE));
        assertFalse(techniques.test(DuelSim.action(1, -1, false, false, false)));
        assertTrue(techniques.test(WALK));
    }

    @Test
    void sprintIsGoneWhileTheTapLockHolds()
    {
        Techniques techniques = new Techniques(true, true, true, true);
        techniques.lockSprint(1);
        assertFalse(techniques.test(SPRINT), "sprint cannot be planned while the lock is on");
        assertTrue(techniques.test(WALK), "the release tick is still allowed");
        techniques.tick();
        assertTrue(techniques.test(SPRINT), "the tick after the release tick sprint is back");
    }

    @Test
    void aLongerLockKeepsSprintOutForLonger()
    {
        Techniques techniques = new Techniques(true, true, true, true);
        techniques.lockSprint(Techniques.NO_TAP_LOCK_TICKS);
        for (int tick = 0; tick < Techniques.NO_TAP_LOCK_TICKS; tick++)
        {
            assertFalse(techniques.test(SPRINT), "still locked on tick " + tick);
            techniques.tick();
        }
        assertEquals(0, techniques.sprintLock());
        assertTrue(techniques.test(SPRINT));
    }

    @Test
    void aBotThatMayNotTapSprintNeverSprints()
    {
        // Sprint cannot be tapped back on after a sprint hit, so a bot that is not allowed to tap it
        // keeps sprint out of its action set altogether and never lands one.
        Techniques techniques = new Techniques(true, true, false, true);
        assertFalse(techniques.test(SPRINT));
        techniques.lockSprint(1);
        techniques.tick();
        assertEquals(0, techniques.sprintLock());
        assertFalse(techniques.test(SPRINT), "the lock is over, but the technique is still not allowed");
        assertTrue(techniques.test(WALK));
    }

    @Test
    void theLockIsNeverShortened()
    {
        Techniques techniques = new Techniques(true, true, true, true);
        techniques.lockSprint(5);
        techniques.lockSprint(2);
        assertEquals(5, techniques.sprintLock());
    }
}