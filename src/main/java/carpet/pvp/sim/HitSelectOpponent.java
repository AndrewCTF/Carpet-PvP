package carpet.pvp.sim;

import java.util.Random;

/**
 * Hit select: keeps the swing on cooldown until the opponent has committed, that is until their swing has come off
 * cooldown, is already in flight, or they are in the air where they cannot correct. Swings held past that are
 * swings spent on air.
 */
public final class HitSelectOpponent extends ScriptedOpponent
{
    public HitSelectOpponent(Random random, int reactionDelay)
    {
        super(random, reactionDelay);
    }

    @Override
    protected int decide(View view)
    {
        boolean committed = view.theirSwingAge <= 2 || view.theirSwingAge >= view.theirGateTicks
                || !view.theyOnGround;
        return DuelSim.action(1, 0, false, view.swingAge > 2, view.charged && view.inReach && committed);
    }
}