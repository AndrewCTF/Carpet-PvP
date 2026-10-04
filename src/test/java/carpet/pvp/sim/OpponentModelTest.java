package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * What the opponent model answers with, and where the answer comes from. A row that has been observed answers
 * from the observation; the prior is only for a model that has never watched the opponent at all. Letting the
 * prior outlive the first couple of observations is what put a charging opponent into every context a bot had
 * not spent long in, which is a bot that walks in circles a few blocks short of a target that never moved.
 */
class OpponentModelTest
{
    /** What a target that stands still looks like to the model. */
    private static final int STILL = DuelSim.action(0, 0, false, false, false);
    /** What the model assumes of an opponent it has never watched. */
    private static final int APPROACH = DuelSim.action(1, 0, false, true, false);
    /** And of one that is charged and in reach of the fighter. */
    private static final int APPROACH_ATTACK = DuelSim.action(1, 0, false, true, true);

    /** A duel at the given distance with both fighters charged and idle, which is a passive target. */
    private static DuelSim duel(double distance)
    {
        DuelSim sim = new DuelSim();
        for (int who = 0; who < 2; who++)
        {
            sim.fighter(who).setLoadout(7.0, 1.6, 0.0f, 20.0f, 8.0f, 0.0f, 0.0);
            sim.fighter(who).ticksSinceSwing = 50;
        }
        sim.placeFacing(distance);
        return sim;
    }

    @Test
    void aModelThatHasSeenNothingAssumesAnApproach()
    {
        OpponentModel model = new OpponentModel();
        assertEquals(APPROACH, model.predict(duel(4.6), 1));
        // Two and a half blocks is inside the reach of the fighter, which the prior reads as a swing.
        assertEquals(APPROACH_ATTACK, model.predict(duel(2.5), 1));
    }

    @Test
    void aTargetThatStandsStillIsPredictedToStandStill()
    {
        OpponentModel model = new OpponentModel();
        DuelSim sim = duel(4.6);
        for (int i = 0; i < 8; i++)
        {
            model.observe(sim, 1, STILL);
        }
        assertEquals(STILL, model.predict(duel(4.6), 1));
    }

    @Test
    void aSituationSeenOnlyOnceIsAnsweredFromThatObservation()
    {
        OpponentModel model = new OpponentModel();
        DuelSim far = duel(4.6);
        for (int i = 0; i < 8; i++)
        {
            model.observe(far, 1, STILL);
        }
        // The target has been watched standing still at melee range, twice: enough for the flags that say
        // "in reach" to have been seen, not enough to be sure of them.
        DuelSim near = duel(2.5);
        model.observe(near, 1, STILL);
        model.observe(near, 1, STILL);
        // A distance it has never been watched at, with the same flags, is answered from what it was seen to
        // do rather than from what an unwatched opponent is assumed to do.
        DuelSim between = duel(3.2);
        assertEquals(STILL, model.predict(between, 1),
                "a context with no data of its own answers from the situation at whatever distance");
        assertNotEquals(APPROACH_ATTACK, model.predict(between, 1),
                "and never with the prior of a context nobody has watched");
    }

    @Test
    void aResetForgetsEverythingAndGoesBackToAssuming()
    {
        OpponentModel model = new OpponentModel();
        DuelSim sim = duel(4.6);
        for (int i = 0; i < 8; i++)
        {
            model.observe(sim, 1, STILL);
        }
        model.reset();
        assertEquals(APPROACH, model.predict(duel(4.6), 1));
        assertEquals(APPROACH_ATTACK, model.predict(duel(2.5), 1));
    }
}