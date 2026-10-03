package carpet.pvp.sim;

import java.util.Random;

/**
 * Crit: sprints in until charged and in reach, then jumps and swings on the way down, which is a critical because
 * the jump was taken without sprint. Waiting for the charge before the jump is what makes the swing a few ticks
 * later a full strength critical.
 */
public final class CritOpponent extends ScriptedOpponent
{
    private boolean rising;
    private boolean swung;

    public CritOpponent(Random random, int reactionDelay)
    {
        super(random, reactionDelay);
    }

    @Override
    protected int decide(View view)
    {
        if (view.swingAge <= 1)
        {
            rising = false;
            swung = false;
        }
        if (rising && !swung && !view.onGround)
        {
            if (view.vy < 0.0 && view.inReach)
            {
                swung = true;
                return DuelSim.action(1, 0, false, false, true);
            }
            return DuelSim.action(1, 0, false, false, false);
        }
        rising = view.charged && view.inReach;
        return DuelSim.action(1, 0, rising, !rising && view.onGround && view.swingAge > 2, false);
    }
}