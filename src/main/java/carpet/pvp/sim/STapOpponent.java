package carpet.pvp.sim;

import java.util.Random;

/** S-tap: walks backwards for a few ticks after every hit, which resets sprint and buys spacing. */
public final class STapOpponent extends ScriptedOpponent
{
    private static final int BACK_TICKS = 4;

    private int backing;
    private float lastTheirHealth;

    public STapOpponent(Random random, int reactionDelay)
    {
        super(random, reactionDelay);
    }

    @Override
    protected int decide(View view)
    {
        if (view.theirHealth < lastTheirHealth)
        {
            backing = BACK_TICKS;
        }
        lastTheirHealth = view.theirHealth;
        if (backing > 0)
        {
            backing--;
            return DuelSim.action(-1, 0, false, false, false);
        }
        return DuelSim.action(1, 0, false, true, view.charged && view.inReach);
    }
}