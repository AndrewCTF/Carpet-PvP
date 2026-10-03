package carpet.pvp.sim;

import java.util.Random;

/**
 * Drives {@link RollingHorizon} as a duel policy: plans the fighter's action from a fixed simulation budget per
 * tick and learns the opponent from the action it took in the same state.
 */
public final class PlannerOpponent implements DuelPolicy
{
    private final RollingHorizon planner;
    private final OpponentModel model;
    private final int budgetTicks;

    public PlannerOpponent(PlannerParams params, Random random)
    {
        this.planner = new RollingHorizon(params, random);
        this.model = new OpponentModel();
        this.budgetTicks = params.budgetTicks();
    }

    @Override
    public int act(DuelSim sim, int who)
    {
        return planner.plan(sim, who, model, budgetTicks);
    }

    @Override
    public void observe(DuelSim sim, int who, int theirAction)
    {
        model.observe(sim, 1 - who, theirAction);
    }
}