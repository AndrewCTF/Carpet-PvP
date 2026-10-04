package carpet.pvp.sim;

/**
 * Frequency-table model of a duel opponent. The context is a coarse view of the state
 * (distance bucket, target in reach, attack charged, airborne, sprint locked after a sprint hit); the prediction is the most frequent action seen in that context,
 * with a prior of "approach sprinting, and attack when charged and in reach".
 *
 * <p>A table per exact context has no answer in most of the contexts a fight passes through: closing from five
 * blocks to two walks the opponent out of one distance bucket and into another, and a model that answered
 * those with its prior described a target that stood still as one that charges. The counts are therefore kept a
 * second time without the distance bucket, and a context with nothing in it is answered from the same situation
 * at whatever distance, and failing that from anything the opponent was ever seen to do. Only a model that has
 * never watched the opponent at all falls back on the prior, which is what the prior is for.</p>
 */
public final class OpponentModel
{
    private static final double[] DISTANCE_LIMITS = {2.0, 3.0, 3.4, 5.0, 8.0};
    private static final int BUCKETS = DISTANCE_LIMITS.length + 1;
    /** One context per distance bucket and these bits: everything about the opponent but how far off it is. */
    private static final int FLAGS = 16;
    private static final int CONTEXTS = BUCKETS * FLAGS;
    /** First row of the distance-free table, and then the row that counts everything the model has seen. */
    private static final int ANY = CONTEXTS;
    private static final int EVERYTHING = CONTEXTS + FLAGS;
    private static final int ROWS = EVERYTHING + 1;
    private static final int PRIOR_WEIGHT = 2;
    private static final int APPROACH = DuelSim.action(1, 0, false, true, false);
    private static final int WALK = DuelSim.action(1, 0, false, false, false);
    private static final int WALK_ATTACK = DuelSim.action(1, 0, false, false, true);
    private static final int APPROACH_ATTACK = DuelSim.action(1, 0, false, true, true);

    private final int[] counts = new int[ROWS * DuelSim.ACTION_COUNT];
    private final int[] prior = new int[ROWS];
    private final int[] best = new int[ROWS];
    private final int[] bestScore = new int[ROWS];
    private final int[] seen = new int[ROWS];

    public OpponentModel()
    {
        reset();
    }

    public void reset()
    {
        java.util.Arrays.fill(counts, 0);
        java.util.Arrays.fill(seen, 0);
        for (int c = 0; c < CONTEXTS; c++)
        {
            prior[c] = priorOf(c & (FLAGS - 1));
        }
        for (int f = 0; f < FLAGS; f++)
        {
            prior[ANY + f] = priorOf(f);
        }
        prior[EVERYTHING] = priorOf(0);
        for (int row = 0; row < ROWS; row++)
        {
            best[row] = prior[row];
            bestScore[row] = PRIOR_WEIGHT;
        }
    }

    /** What an opponent is assumed to do in a situation nothing has been observed in. */
    private static int priorOf(int flags)
    {
        boolean charged = (flags & 2) != 0;
        boolean locked = (flags & 4) != 0;
        boolean reach = (flags & 8) != 0;
        return locked ? (charged && reach ? WALK_ATTACK : WALK) : charged && reach ? APPROACH_ATTACK : APPROACH;
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
        int reach = DuelSim.inReach(f, sim.fighter(1 - who)) ? 8 : 0;
        int charged = f.ticksSinceSwing >= f.gateTicks ? 1 : 0;
        int airborne = f.onGround ? 0 : 1;
        return bucket * FLAGS + reach + (f.sprintLocked ? 4 : 0) + charged * 2 + airborne;
    }

    public int predict(DuelSim sim, int who)
    {
        int c = context(sim, who);
        if (seen[c] > 0)
        {
            return best[c];
        }
        int any = ANY + (c & (FLAGS - 1));
        if (seen[any] > 0)
        {
            return best[any];
        }
        return seen[EVERYTHING] > 0 ? best[EVERYTHING] : best[c];
    }

    /** Records the action fighter who actually took in the given (pre-step) state. */
    public void observe(DuelSim sim, int who, int action)
    {
        int c = context(sim, who);
        tally(c, action);
        tally(ANY + (c & (FLAGS - 1)), action);
        tally(EVERYTHING, action);
    }

    private void tally(int row, int action)
    {
        int score = ++counts[row * DuelSim.ACTION_COUNT + action] + (action == prior[row] ? PRIOR_WEIGHT : 0);
        seen[row]++;
        if (score > bestScore[row] || best[row] == action)
        {
            best[row] = action;
            bestScore[row] = score;
        }
    }
}