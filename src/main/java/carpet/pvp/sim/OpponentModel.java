package carpet.pvp.sim;

/**
 * Frequency-table model of a duel opponent. The context is a coarse view of the state
 * (distance bucket, attack charged, airborne, sprint locked after a sprint hit); the prediction is the most frequent action seen in that context,
 * with a prior of "approach sprinting, and attack when charged and close".
 */
public final class OpponentModel
{
    private static final double[] DISTANCE_LIMITS = {2.0, 3.0, 3.4, 5.0, 8.0};
    private static final int BUCKETS = DISTANCE_LIMITS.length + 1;
    private static final int CONTEXTS = BUCKETS * 8;
    private static final int PRIOR_WEIGHT = 2;
    private static final int APPROACH = DuelSim.action(1, 0, false, true, false);
    private static final int WALK = DuelSim.action(1, 0, false, false, false);
    private static final int WALK_ATTACK = DuelSim.action(1, 0, false, false, true);
    private static final int APPROACH_ATTACK = DuelSim.action(1, 0, false, true, true);

    private final int[] counts = new int[CONTEXTS * DuelSim.ACTION_COUNT];
    private final int[] prior = new int[CONTEXTS];
    private final int[] best = new int[CONTEXTS];
    private final int[] bestScore = new int[CONTEXTS];

    public OpponentModel()
    {
        reset();
    }

    public void reset()
    {
        java.util.Arrays.fill(counts, 0);
        for (int c = 0; c < CONTEXTS; c++)
        {
            boolean charged = (c & 2) != 0;
            boolean locked = (c & 4) != 0;
            boolean close = c / 8 <= 2;
            prior[c] = locked ? (charged && close ? WALK_ATTACK : WALK) : charged && close ? APPROACH_ATTACK : APPROACH;
            best[c] = prior[c];
            bestScore[c] = PRIOR_WEIGHT;
        }
    }

    /** Context index of fighter who (0 = A, 1 = B) in the given state. */
    public static int context(DuelSim sim, int who)
    {
        DuelSim.Fighter f = sim.fighter(who);
        double dist = sim.horizontalDistance();
        int bucket = 0;
        while (bucket < DISTANCE_LIMITS.length && dist >= DISTANCE_LIMITS[bucket])
        {
            bucket++;
        }
        int charged = f.ticksSinceSwing >= f.gateTicks ? 1 : 0;
        int airborne = f.onGround ? 0 : 1;
        return bucket * 8 + (f.sprintLocked ? 4 : 0) + charged * 2 + airborne;
    }

    public int predict(DuelSim sim, int who)
    {
        return best[context(sim, who)];
    }

    /** Records the action fighter who actually took in the given (pre-step) state. */
    public void observe(DuelSim sim, int who, int action)
    {
        int c = context(sim, who);
        int score = ++counts[c * DuelSim.ACTION_COUNT + action] + (action == prior[c] ? PRIOR_WEIGHT : 0);
        if (score > bestScore[c] || best[c] == action)
        {
            best[c] = action;
            bestScore[c] = score;
        }
    }
}
