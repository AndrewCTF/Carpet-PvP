package carpet.pvp.sim;

import java.util.Random;

/** Combo: the W-tap release on top of a circling diagonal, which is the usual way both are used together. */
public final class ComboOpponent extends WTapOpponent
{
    private int direction = 1;
    private int holdTicks = 8 + random.nextInt(12);

    public ComboOpponent(Random random, int reactionDelay)
    {
        super(random, reactionDelay);
    }

    @Override
    protected int decide(View view)
    {
        if (--holdTicks <= 0)
        {
            holdTicks = 5 + random.nextInt(16);
            direction = -direction;
        }
        return DuelSim.action(1, direction, false, isSprinting(view), view.charged && view.inReach);
    }
}