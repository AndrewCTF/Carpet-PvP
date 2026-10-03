package carpet.pvp.sim;

import java.util.Random;

/**
 * Walks straight in with sprint held and attacks whenever charged and in reach. Sprint never lets go, so after
 * the first sprint hit the fighter can never sprint again; this is the yardstick the other policies improve on.
 */
public final class BaselineOpponent extends ScriptedOpponent
{
    public BaselineOpponent(Random random, int reactionDelay)
    {
        super(random, reactionDelay);
    }

    @Override
    protected int decide(View view)
    {
        return DuelSim.action(1, 0, false, true, view.charged && view.inReach);
    }
}