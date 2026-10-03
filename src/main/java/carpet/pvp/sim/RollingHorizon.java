package carpet.pvp.sim;

import java.util.Random;

/**
 * Rolling-horizon evolution over {@link DuelSim} action sequences against an {@link OpponentModel}.
 * A small population is kept between calls and shifted one tick forward each time.
 */
public final class RollingHorizon
{
    private final int horizon;
    private final int population;
    private final double mutationRate;
    private final double dealtWeight;
    private final double takenWeight;
    private final double distanceWeight;
    private final double preferredDistance;
    private final Random random;
    private final int[][] sequences;
    private final double[] fitness;
    private final int[] child;
    private final DuelSim scratch = new DuelSim();
    private boolean seeded;
    private int ticksUsed;

    public RollingHorizon(int horizon, int population, Random random)
    {
        this(PlannerParams.search(horizon, population), random);
    }

    public RollingHorizon(PlannerParams params, Random random)
    {
        this.horizon = params.horizon();
        this.population = params.population();
        this.mutationRate = params.mutationRate();
        this.dealtWeight = params.dealtWeight();
        this.takenWeight = params.takenWeight();
        this.distanceWeight = params.distanceWeight();
        this.preferredDistance = params.preferredDistance();
        this.random = random;
        this.sequences = new int[population][horizon];
        this.fitness = new double[population];
        this.child = new int[horizon];
    }

    /** Simulated ticks spent by the last {@link #plan} call. */
    public int ticksUsed()
    {
        return ticksUsed;
    }

    /** The simulation instance used for rollouts, whose step counter equals the work done. */
    public DuelSim scratch()
    {
        return scratch;
    }

    /**
     * Returns the first action of the best sequence for fighter self (0 = A, 1 = B), spending at most budgetTicks
     * simulated ticks. Only whole rollouts are started, so the spend is at most the budget.
     */
    public int plan(DuelSim state, int self, OpponentModel model, int budgetTicks)
    {
        ticksUsed = 0;
        if (!seeded)
        {
            seedPopulation();
            seeded = true;
        }
        java.util.Arrays.fill(fitness, Double.NEGATIVE_INFINITY);
        for (int i = 0; i < population && budgetTicks - ticksUsed >= horizon; i++)
        {
            fitness[i] = evaluate(sequences[i], state, self, model);
        }
        while (budgetTicks - ticksUsed >= horizon)
        {
            breedChild();
            double f = evaluate(child, state, self, model);
            int worst = 0;
            for (int i = 1; i < population; i++)
            {
                if (fitness[i] < fitness[worst])
                {
                    worst = i;
                }
            }
            if (f > fitness[worst])
            {
                System.arraycopy(child, 0, sequences[worst], 0, horizon);
                fitness[worst] = f;
            }
        }
        int best = 0;
        for (int i = 1; i < population; i++)
        {
            if (fitness[i] > fitness[best])
            {
                best = i;
            }
        }
        int action = sequences[best][0];
        for (int i = 0; i < population; i++)
        {
            int[] seq = sequences[i];
            System.arraycopy(seq, 1, seq, 0, horizon - 1);
            seq[horizon - 1] = i == best ? seq[horizon - 2] : randomAction();
        }
        return action;
    }

    private void seedPopulation()
    {
        for (int i = 0; i < population; i++)
        {
            for (int t = 0; t < horizon; t++)
            {
                sequences[i][t] = randomAction();
            }
        }
        java.util.Arrays.fill(sequences[0], DuelSim.action(1, 0, false, true, true));
        // Critical-hit openers: sprint in, jump after k ticks with sprint released, swing on the way down.
        for (int k = 1; k < population && k <= 8; k++)
        {
            int[] seq = sequences[k];
            for (int t = 0; t < horizon; t++)
            {
                int jumpAt = k - 1;
                seq[t] = t < jumpAt ? DuelSim.action(1, 0, false, true, false)
                        : t < jumpAt + 7 ? DuelSim.action(1, 0, t == jumpAt, false, false)
                        : DuelSim.action(1, 0, false, false, t == jumpAt + 7);
            }
        }
    }

    private void breedChild()
    {
        int p = tournament();
        System.arraycopy(sequences[p], 0, child, 0, horizon);
        if (random.nextBoolean())
        {
            int[] other = sequences[tournament()];
            for (int t = random.nextInt(horizon); t < horizon; t++)
            {
                child[t] = other[t];
            }
        }
        boolean changed = false;
        for (int t = 0; t < horizon; t++)
        {
            if (random.nextDouble() < mutationRate)
            {
                child[t] = randomAction();
                changed = true;
            }
        }
        if (!changed)
        {
            child[random.nextInt(horizon)] = randomAction();
        }
    }

    private int tournament()
    {
        int x = random.nextInt(population);
        int y = random.nextInt(population);
        return fitness[x] >= fitness[y] ? x : y;
    }

    /** Biased toward closing in, sprinting and swinging so that random sequences are mostly sensible. */
    private int randomAction()
    {
        int r = random.nextInt(4);
        int forward = r < 2 ? 1 : r == 2 ? 0 : -1;
        int s = random.nextInt(5);
        int strafe = s < 3 ? 0 : s == 3 ? 1 : -1;
        return DuelSim.action(forward, strafe, random.nextInt(7) == 0, random.nextInt(5) < 3, random.nextInt(6) == 0);
    }

    private double evaluate(int[] sequence, DuelSim state, int self, OpponentModel model)
    {
        DuelSim sim = scratch;
        sim.copyFrom(state);
        int other = 1 - self;
        float mine0 = sim.fighter(self).health;
        float theirs0 = sim.fighter(other).health;
        for (int t = 0; t < horizon && !sim.over(); t++)
        {
            int mine = sequence[t];
            int theirs = model.predict(sim, other);
            if (self == 0)
            {
                sim.step(mine, theirs);
            }
            else
            {
                sim.step(theirs, mine);
            }
            ticksUsed++;
        }
        double dealt = theirs0 - Math.max(0.0f, sim.fighter(other).health);
        double taken = mine0 - Math.max(0.0f, sim.fighter(self).health);
        return dealtWeight * dealt - takenWeight * taken
                - distanceWeight * Math.abs(sim.horizontalDistance() - preferredDistance);
    }
}
