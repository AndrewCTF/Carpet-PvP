package carpet.pvp.sim;

import java.util.Random;

/** Strafe: circles the opponent while staying inside sword range, swapping strafe direction every few ticks. */
public final class StrafeOpponent extends ScriptedOpponent
{
    private static final double PREFERRED = 2.6;

    private int direction = 1;
    private int holdTicks = 8 + random.nextInt(12);

    public StrafeOpponent(Random random, int reactionDelay)
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
        int forward = view.distance > PREFERRED ? 1 : 0;
        return DuelSim.action(forward, direction, false, forward > 0 && view.swingAge > 2,
                view.charged && view.inReach);
    }
}