package carpet.pvp.sim;

import java.util.Random;

/**
 * W-tap: releases sprint for a single tick after every hit, which clears the sprint lock a sprint hit leaves
 * behind, so the next swing goes through as a sprint hit again.
 */
public class WTapOpponent extends ScriptedOpponent
{
    private int releasing;

    public WTapOpponent(Random random, int reactionDelay)
    {
        super(random, reactionDelay);
    }

    /** Updates the release counter and reports whether this fighter wants sprint held on this tick. */
    protected final boolean isSprinting(View view)
    {
        if (view.sprintLocked)
        {
            releasing = 1;
        }
        else if (releasing > 0)
        {
            releasing--;
        }
        return releasing == 0;
    }

    @Override
    protected int decide(View view)
    {
        return DuelSim.action(1, 0, false, isSprinting(view), view.charged && view.inReach);
    }
}