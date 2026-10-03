package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** What each scripted technique is named after is what it does in the duel log. */
class ScriptedOpponentTest
{
    private static final int DUELS = 8;
    private static final float FULL = DuelSim.MAX_HEALTH;

    /** One tick of a duel: the state and the two actions before the tick, and the health after it. */
    private static final class Frame
    {
        int actA;
        int actB;
        float aBefore;
        float bBefore;
        boolean sprinting;
        boolean onGround;
        boolean airborne;
        int swingAge;
        int gateTicks;
        int theirSwingAge;
        int theirGateTicks;
        boolean theyAirborne;
    }

    private interface Watcher
    {
        void tick(DuelSim after, Frame frame);
    }

    /** Runs a duel, handing every tick to the watcher, and returns the number of ticks it took. */
    private static int watch(DuelPolicy a, DuelPolicy b, long seed, Watcher watcher)
    {
        DuelSim sim = Duel.newDuel(seed);
        Frame frame = new Frame();
        int ticks = 0;
        for (int t = 0; t < Duel.DEFAULT_MAX_TICKS && !sim.over(); t++)
        {
            DuelSim.Fighter me = sim.a;
            frame.actA = a.act(sim, 0);
            frame.actB = b.act(sim, 1);
            frame.aBefore = me.health;
            frame.bBefore = sim.b.health;
            frame.sprinting = me.sprinting;
            frame.onGround = me.onGround;
            frame.airborne = !me.onGround && me.vy < 0.0;
            frame.swingAge = me.ticksSinceSwing;
            frame.gateTicks = me.gateTicks;
            frame.theirSwingAge = sim.b.ticksSinceSwing;
            frame.theirGateTicks = sim.b.gateTicks;
            frame.theyAirborne = !sim.b.onGround;
            sim.step(frame.actA, frame.actB);
            watcher.tick(sim, frame);
            ticks++;
        }
        return ticks;
    }

    private static DuelPolicy opponent(int which)
    {
        Random random = new Random(which);
        return switch (which)
        {
            case 1 -> new WTapOpponent(random, 0);
            case 2 -> new STapOpponent(random, 0);
            case 3 -> new CritOpponent(random, 0);
            case 4 -> new StrafeOpponent(random, 0);
            case 5 -> new JumpResetOpponent(random, 0);
            case 6 -> new HitSelectOpponent(random, 0);
            case 7 -> new ComboOpponent(random, 0);
            default -> new BaselineOpponent(random, 0);
        };
    }

    /** A tick on which the swing A had queued was a sprint hit. */
    private static boolean wasSprintHit(Frame frame)
    {
        return frame.sprinting && frame.swingAge >= frame.gateTicks;
    }

    /** A tick on which the swing A had queued was a critical. */
    private static boolean wasCrit(Frame frame)
    {
        return frame.airborne && !frame.sprinting && frame.swingAge >= frame.gateTicks;
    }

    @Test
    void wTapMakesEveryHitASprintHit()
    {
        int[] hits = {0};
        int[] sprintHits = {0};
        int[] whiffs = {0};
        for (int d = 0; d < DUELS; d++)
        {
            watch(opponent(1), opponent(0), 4000L + d, (after, frame) ->
            {
                if (frame.bBefore > after.b.health)
                {
                    hits[0]++;
                    if (wasSprintHit(frame))
                    {
                        sprintHits[0]++;
                    }
                }
                else if (DuelSim.attack(frame.actA))
                {
                    whiffs[0]++;
                }
            });
        }
        assertTrue(hits[0] >= 3 * DUELS, "hits " + hits[0]);
        assertEquals(hits[0], sprintHits[0], "every hit of a W-tapper goes through sprinting");
        assertTrue(whiffs[0] < hits[0], "whiffed " + whiffs[0] + " of " + hits[0] + " swings");
    }

    @Test
    void baselineNeverReSprints()
    {
        int[] hits = {0};
        int[] sprintHits = {0};
        for (int d = 0; d < DUELS; d++)
        {
            watch(opponent(0), opponent(0), 4000L + d, (after, frame) ->
            {
                if (frame.bBefore > after.b.health)
                {
                    hits[0]++;
                }
                if (DuelSim.attack(frame.actA) && wasSprintHit(frame))
                {
                    sprintHits[0]++;
                }
            });
        }
        assertTrue(hits[0] >= 3 * DUELS, "hits " + hits[0]);
        assertTrue(sprintHits[0] <= DUELS, "sprint hits " + sprintHits[0] + " of " + hits[0]
                + ": holding sprint never clears the lock, so it only sprints once per duel");
    }

    @Test
    void sTapStepsBackAfterEveryHit()
    {
        int[] hits = {0};
        int[] backedOff = {0};
        int[] pending = {0};
        for (int d = 0; d < DUELS; d++)
        {
            pending[0] = 0;
            watch(opponent(2), opponent(0), 4000L + d, (after, frame) ->
            {
                if (pending[0] > 0 && DuelSim.forward(frame.actA) < 0)
                {
                    backedOff[0]++;
                    pending[0] = 0;
                }
                else if (pending[0] > 0)
                {
                    pending[0]--;
                }
                if (frame.bBefore > after.b.health)
                {
                    hits[0]++;
                    pending[0] = 6;
                }
            });
        }
        assertTrue(hits[0] >= 2 * DUELS, "hits " + hits[0]);
        // The killing blow of a duel ends the fight on the spot, so at most one hit per duel goes unanswered.
        assertTrue(hits[0] - backedOff[0] <= DUELS,
                "hits not answered by a step back: " + (hits[0] - backedOff[0]) + " of " + hits[0]);
    }

    @Test
    void critOpponentOnlyLandsCriticals()
    {
        int[] hits = {0};
        int[] crits = {0};
        double[] critDamage = {0};
        double[] otherDamage = {0};
        for (int d = 0; d < DUELS; d++)
        {
            watch(opponent(3), opponent(0), 4000L + d, (after, frame) ->
            {
                float dealt = frame.bBefore - after.b.health;
                if (dealt <= 0)
                {
                    return;
                }
                hits[0]++;
                if (wasCrit(frame))
                {
                    crits[0]++;
                    critDamage[0] += dealt;
                }
                else
                {
                    otherDamage[0] += dealt;
                }
            });
        }
        assertTrue(hits[0] >= 2 * DUELS, "hits " + hits[0]);
        assertEquals(hits[0], crits[0], "the critter only lands criticals");
        // A critical is 1.5 times a full charge hit, so it has to come out well above one.
        assertTrue(critDamage[0] / crits[0] > otherDamage[0] / Math.max(1, hits[0] - crits[0]) + 1.0,
                "average critical " + critDamage[0] / crits[0]);
    }

    @Test
    void strafeKeepsStrafingAndReverses()
    {
        int[] strafing = {0};
        int[] ticks = {0};
        int[] reversals = {0};
        for (int d = 0; d < DUELS; d++)
        {
            int[] last = {0};
            watch(opponent(4), opponent(0), 4000L + d, (after, frame) ->
            {
                int side = DuelSim.strafe(frame.actA);
                if (side != 0)
                {
                    strafing[0]++;
                    if (last[0] != 0 && side != last[0])
                    {
                        reversals[0]++;
                    }
                    last[0] = side;
                }
                ticks[0]++;
            });
        }
        assertTrue(strafing[0] > ticks[0] * 0.9, "strafing on " + strafing[0] + " of " + ticks[0] + " ticks");
        assertTrue(reversals[0] > 2 * DUELS, "reversals " + reversals[0]);
    }

    @Test
    void jumpResetIsOffTheGroundWhenTheHitLands()
    {
        int[] hitsTaken = {0};
        int[] airborne = {0};
        int[] jumpedJustBefore = {0};
        for (int d = 0; d < DUELS; d++)
        {
            int[] sinceJump = {99};
            watch(opponent(5), opponent(0), 4000L + d, (after, frame) ->
            {
                if (DuelSim.jump(frame.actA))
                {
                    sinceJump[0] = 0;
                }
                else if (sinceJump[0] < 99)
                {
                    sinceJump[0]++;
                }
                if (frame.aBefore > after.a.health)
                {
                    hitsTaken[0]++;
                    if (!frame.onGround)
                    {
                        airborne[0]++;
                        if (sinceJump[0] <= 3)
                        {
                            jumpedJustBefore[0]++;
                        }
                    }
                }
            });
        }
        assertTrue(hitsTaken[0] >= 2 * DUELS, "hits taken " + hitsTaken[0]);
        assertTrue(airborne[0] > hitsTaken[0] / 4,
                "hits met in the air " + airborne[0] + " of " + hitsTaken[0]);
        assertTrue(jumpedJustBefore[0] > 0, "no hit was met with a jump within three ticks of it landing");
    }

    @Test
    void hitSelectOnlySwingsAtACommittedOpponent()
    {
        int[] swings = {0};
        int[] landed = {0};
        for (int d = 0; d < DUELS; d++)
        {
            watch(opponent(6), opponent(0), 4000L + d, (after, frame) ->
            {
                if (!DuelSim.attack(frame.actA))
                {
                    return;
                }
                swings[0]++;
                boolean committed = frame.theirSwingAge <= 2 || frame.theirSwingAge >= frame.theirGateTicks
                        || frame.theyAirborne;
                assertTrue(committed, "swung with the opponent at swing age " + frame.theirSwingAge
                        + " against a gate of " + frame.theirGateTicks);
                if (frame.bBefore > after.b.health)
                {
                    landed[0]++;
                }
            });
        }
        assertTrue(swings[0] >= 2 * DUELS, "swings " + swings[0]);
        assertTrue(landed[0] > swings[0] / 2, "only " + landed[0] + " of " + swings[0] + " swings landed");
    }

    @Test
    void comboIsTheWTapOnTopOfAStrafe()
    {
        int[] hits = {0};
        int[] sprintHits = {0};
        int[] strafing = {0};
        int[] ticks = {0};
        for (int d = 0; d < DUELS; d++)
        {
            watch(opponent(7), opponent(0), 4000L + d, (after, frame) ->
            {
                if (DuelSim.strafe(frame.actA) != 0)
                {
                    strafing[0]++;
                }
                ticks[0]++;
                if (frame.bBefore > after.b.health)
                {
                    hits[0]++;
                    if (wasSprintHit(frame))
                    {
                        sprintHits[0]++;
                    }
                }
            });
        }
        assertTrue(hits[0] >= 2 * DUELS, "hits " + hits[0]);
        assertEquals(hits[0], sprintHits[0], "the combo keeps the sprint hits of the W-tap");
        assertTrue(strafing[0] > ticks[0] * 0.9, "strafing on " + strafing[0] + " of " + ticks[0] + " ticks");
    }

    /** Swings on the single tick its view says the charge comes up, which makes the reaction delay measurable. */
    private static final class ChargeWatcher extends ScriptedOpponent
    {
        ChargeWatcher(Random random, int reactionDelay)
        {
            super(random, reactionDelay);
        }

        @Override
        protected int decide(View view)
        {
            return DuelSim.action(1, 0, false, true, view.charged && view.swingAge == view.gateTicks);
        }
    }

    @Test
    void reactionDelayMakesEverySwingLate()
    {
        int[] swings = {0};
        int[] late = {0};
        for (int d = 0; d < DUELS; d++)
        {
            watch(new ChargeWatcher(new Random(1), 5), new BaselineOpponent(new Random(2), 0), 4000L + d,
                    (after, frame) ->
                    {
                        if (DuelSim.attack(frame.actA))
                        {
                            swings[0]++;
                            if (frame.swingAge == frame.gateTicks + 5)
                            {
                                late[0]++;
                            }
                        }
                    });
        }
        assertTrue(swings[0] >= DUELS, "swings " + swings[0]);
        assertEquals(swings[0], late[0],
                "with a five tick delay the swing comes out five ticks after the charge comes up");
    }

    @Test
    void aZeroDelayPolicySeesTheCurrentState()
    {
        int[] swings = {0};
        int[] onTheGate = {0};
        for (int d = 0; d < DUELS; d++)
        {
            watch(new ChargeWatcher(new Random(1), 0), new BaselineOpponent(new Random(2), 0), 4000L + d,
                    (after, frame) ->
                    {
                        if (DuelSim.attack(frame.actA))
                        {
                            swings[0]++;
                            if (frame.swingAge == frame.gateTicks)
                            {
                                onTheGate[0]++;
                            }
                        }
                    });
        }
        assertTrue(swings[0] >= DUELS, "swings " + swings[0]);
        assertEquals(swings[0], onTheGate[0], "without a delay the swing goes out the tick the charge comes up");
    }

    @Test
    void theDuelStartIsNotAMirrorImage()
    {
        DuelSim sim = Duel.newDuel(11L);
        assertTrue(sim.b.x != 0.0, "the second fighter starts off the centre line");
        assertTrue(sim.a.ticksSinceSwing != sim.b.ticksSinceSwing,
                "the two fighters do not start on the same swing clock");
        assertTrue(sim.a.ticksSinceSwing < 20 && sim.b.ticksSinceSwing < 20, "neither starts on a fresh swing");
        assertEquals(FULL, sim.a.health);
    }
}