package carpet.pvp.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.crystal.CrystalTactics.Blast;
import carpet.pvp.crystal.CrystalTactics.Stage;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.DoubleTap;
import org.junit.jupiter.api.Test;

class CrystalTacticsTest
{
    /** Noughties of an armored netherite fighter, as the crystal kit leaves it after four blast protection. */
    private static final float HEALTH = 20.0F;

    @Test
    void aBlastTheBotSurvivesGoesOffStraightAway()
    {
        assertEquals(Blast.GO, CrystalTactics.judge(6.0F, HEALTH, 12.0F, HEALTH, false, true, true));
        assertEquals(Blast.GO, CrystalTactics.judge(19.9F, HEALTH, 1.0F, HEALTH, true, true, true));
    }

    /**
     * A blast that would finish the bot is only worth taking when it finishes the target as well, and a
     * target with a totem left is not finished by it. Anything else puts a block up or a step back first.
     */
    @Test
    void aLethalBlastIsOnlyTakenWhenItFinishesTheTarget()
    {
        assertEquals(Blast.TRADE, CrystalTactics.judge(25.0F, HEALTH, 24.0F, HEALTH, false, true, true),
                "both die, so the bot takes it");
        assertEquals(Blast.BLOCK_OFF, CrystalTactics.judge(25.0F, HEALTH, 24.0F, HEALTH, true, true, true),
                "the target pops its totem and lives, so the bot blocks the blast off");
        assertEquals(Blast.BACK_OFF, CrystalTactics.judge(25.0F, HEALTH, 3.0F, HEALTH, false, false, true));
        assertEquals(Blast.LEAVE, CrystalTactics.judge(25.0F, HEALTH, 3.0F, HEALTH, false, false, false),
                "boxed in with nothing to spend, there is only walking away");
    }

    @Test
    void aBlockBetweenIsPreferredOverAVacatedCell()
    {
        assertEquals(Blast.BLOCK_OFF, CrystalTactics.judge(40.0F, HEALTH, 2.0F, HEALTH, true, true, true));
        assertEquals(Blast.BACK_OFF, CrystalTactics.judge(40.0F, HEALTH, 2.0F, HEALTH, true, false, true));
    }

    /**
     * A crystal on an obsidian base is one click, and the click that hits it is the next one. A respawn
     * anchor is the same click, then one glowstone click per charge, and the click that sets it off.
     */
    @Test
    void aCrystalIsTwoClicksAndAnAnchorIsSix()
    {
        assertEquals(Stage.PLACE, CrystalTactics.opening(false));
        assertEquals(Stage.BRIDGE, CrystalTactics.opening(true));
        assertEquals(Stage.READY, CrystalTactics.after(Stage.PLACE, false, 0));
        assertEquals(Stage.CHARGE, CrystalTactics.after(Stage.PLACE, true, 0));
        assertEquals(Stage.CHARGE, CrystalTactics.after(Stage.CHARGE, true, 3));
        assertEquals(Stage.READY, CrystalTactics.after(Stage.CHARGE, true, CrystalTactics.ANCHOR_CHARGES));
        assertEquals(Stage.NONE, CrystalTactics.after(Stage.READY, false, 0));
        assertEquals(4, CrystalTactics.ANCHOR_CHARGES);
    }

    /** The stage machine of a whole anchor: place, four charges, set off. */
    @Test
    void theStageMachineCountsTheChargesItIsToldAbout()
    {
        CrystalTactics tactics = new CrystalTactics(1);
        tactics.begin(Stage.PLACE, 0);
        tactics.begin(CrystalTactics.after(tactics.stage(), true, tactics.charges()), tactics.charges());
        assertEquals(Stage.CHARGE, tactics.stage());
        assertEquals(0, tactics.charges());
        for (int charge = 1; charge <= CrystalTactics.ANCHOR_CHARGES; charge++)
        {
            tactics.begin(Stage.CHARGE, charge);
            tactics.begin(CrystalTactics.after(tactics.stage(), true, tactics.charges()), tactics.charges());
        }
        assertEquals(Stage.READY, tactics.stage());
        assertEquals(CrystalTactics.ANCHOR_CHARGES, tactics.charges());
        tactics.reset();
        assertEquals(Stage.NONE, tactics.stage());
        assertEquals(0, tactics.charges());
    }

    /** The same sequence for a crystal, which needs no charging. */
    @Test
    void aCrystalGoesStraightFromPlacedToReady()
    {
        CrystalTactics tactics = new CrystalTactics(1);
        tactics.begin(CrystalTactics.opening(true), 0);
        tactics.begin(CrystalTactics.after(tactics.stage(), false, tactics.charges()), tactics.charges());
        assertEquals(Stage.PLACE, tactics.stage());
        tactics.begin(CrystalTactics.after(tactics.stage(), false, tactics.charges()), tactics.charges());
        assertEquals(Stage.READY, tactics.stage());
    }

    /**
     * Inside the damage window a second hit only applies the excess over the first, so a crystal worth less
     * than the sword hit that went in first waits for the window to close. A bigger one does not.
     */
    @Test
    void aSmallCrystalWaitsForTheWindowButABigOneDoesNot()
    {
        int window = DoubleTap.earliestFullDamageTick();
        assertEquals(CombatMath.INVULNERABLE_TICKS_AFTER_HIT - CombatMath.INVULNERABLE_WINDOW_THRESHOLD,
                window);
        assertEquals(window, CrystalTactics.doubleTapDelay(4.0F, 9.0F, 0), "just after the hit, wait it out");
        assertEquals(4, CrystalTactics.doubleTapDelay(4.0F, 9.0F, window - 4));
        assertEquals(0, CrystalTactics.doubleTapDelay(4.0F, 9.0F, window), "the window has closed");
        assertEquals(0, CrystalTactics.doubleTapDelay(4.0F, 9.0F, window + 5), "and stays closed");
        assertEquals(0, CrystalTactics.doubleTapDelay(12.0F, 9.0F, 0), "a blast over the sword hit is not worth waiting for");
        assertEquals(0, CrystalTactics.doubleTapDelay(9.0F, 9.0F, 0), "and neither is one that just matches it");
    }

    /**
     * Waiting for the window only ever pays when the hit that went in first was the bigger of the two, so
     * the delay the tactics ask for and the damage DoubleTap reports have to agree.
     */
    @Test
    void theDelayIsWorthTakingOnlyWhenTheSecondHitWouldBeCutDown()
    {
        int window = DoubleTap.earliestFullDamageTick();
        for (int since = 0; since < window; since++)
        {
            int delay = CrystalTactics.doubleTapDelay(4.0F, 9.0F, since);
            assertEquals(window - since, delay);
            assertTrue(DoubleTap.followUpDamage(4.0F, 9.0F, since) <= 0.0F,
                    "a blast smaller than the first hit applies nothing at " + since + " ticks");
            assertTrue(DoubleTap.followUpDamage(4.0F, 9.0F, since + delay) > 0.0F,
                    "and something at " + (since + delay) + " ticks, once the window has closed");
        }
    }

    @Test
    void aSecondTotemOnlyGoesInHandWhenTheBlastWouldFinishTheBot()
    {
        assertTrue(CrystalTactics.handTotemWanted(true, true));
        assertFalse(CrystalTactics.handTotemWanted(true, false), "a blast it survives needs no totem in hand");
        assertFalse(CrystalTactics.handTotemWanted(false, true), "and it cannot hold what it does not carry");
    }

    /** A player does not swap a fresh totem in on the tick the old one popped. */
    @Test
    void aPoppedTotemIsReplacedOnlyAfterTheDelay()
    {
        CrystalTactics tactics = new CrystalTactics(3);
        assertEquals(0, tactics.ticksToReTotem(20), "nothing has been popped, so there is nothing to wait for");
        tactics.onPop();
        assertEquals(20, tactics.ticksToReTotem(20));
        for (int tick = 1; tick < 20; tick++)
        {
            tactics.tick();
            assertEquals(20 - tick, tactics.ticksToReTotem(20));
        }
        tactics.tick();
        assertEquals(0, tactics.ticksToReTotem(20));
    }

    /** A harder bot looks for a placement more often; a slower one waits out its interval. */
    @Test
    void theSearchIntervalCountsItselfDown()
    {
        CrystalTactics quick = new CrystalTactics(1);
        assertTrue(quick.planDue(), "the first tick is always a search");
        quick.planned();
        quick.tick();
        assertTrue(quick.planDue(), "an expert searches again on the next tick");
        CrystalTactics slow = new CrystalTactics(4);
        slow.tick();
        assertTrue(slow.planDue(), "the first tick is always a search");
        slow.planned();
        for (int tick = 1; tick <= 3; tick++)
        {
            slow.tick();
            assertFalse(slow.planDue(), "still waiting at tick " + tick);
        }
        slow.tick();
        assertTrue(slow.planDue());
    }

    /** The counters that drive the double tap start at "no hit yet" and at "no pop yet". */
    @Test
    void theMeleeAndPopCountersStartColdAndWarmUp()
    {
        CrystalTactics tactics = new CrystalTactics(1);
        assertEquals(CrystalTactics.NO_POP, tactics.ticksSinceMeleeHit());
        tactics.onMeleeHit();
        assertEquals(0, tactics.ticksSinceMeleeHit());
        tactics.tick();
        assertEquals(1, tactics.ticksSinceMeleeHit());
        for (int tick = 0; tick < 100; tick++)
        {
            tactics.tick();
        }
        assertEquals(101, tactics.ticksSinceMeleeHit(), "a hit far enough back is not part of a double tap");
    }
}
