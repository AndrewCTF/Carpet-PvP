package carpet.pvp.sim;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Tunes {@link PlannerParams} by league score against the scripted opponents, every candidate paying the same
 * simulation budget per call so horizon and population compete for the same search effort.
 *
 * <p>The search is a (1+lambda) evolution strategy with a self-adapting step size on the 1/5th success rule, not
 * the N-Tuple Bandit EA. That EA carries a binary table of partial solutions and grows it combinatorially, which
 * only pays off on small discrete problems; here the vector is mixed continuous and integer, the fitness is noisy
 * and non-separable, and log-normal steps on a handful of coordinates get to the same place far more cheaply. The
 * seed of this class runs the search from the command line, the tests run it with a few generations.
 */
public final class Tuner
{
    /** Coordinates of the search vector, in the order {@link #toVector} writes them. */
    private static final int COORDS = 7;
    private static final double SIGMA_START = 0.35;
    private static final double SIGMA_MIN = 0.02;
    private static final double SIGMA_MAX = 1.2;
    private static final int SIGMA_WINDOW = 5;

    /** Where the search ended up, and how much the search gained over where it started. */
    public record Result(PlannerParams start, PlannerParams best, double startScore, double bestScore, int evaluations)
    {
    }

    private final List<League.Combatant> opponents;
    private final int duelsPerOpponent;
    private final long seedBase;
    private final int maxTicks;
    private final int plannerSeed;

    /**
     * @param opponents the scripted opponents every candidate faces
     * @param duelsPerOpponent duels against each opponent with each side, all on fixed seeds
     * @param seedBase first seed of the tuning set; the held-out set starts somewhere else entirely
     */
    public Tuner(List<League.Combatant> opponents, int duelsPerOpponent, long seedBase, int maxTicks,
                 int plannerSeed)
    {
        this.opponents = List.copyOf(opponents);
        this.duelsPerOpponent = duelsPerOpponent;
        this.seedBase = seedBase;
        this.maxTicks = maxTicks;
        this.plannerSeed = plannerSeed;
    }

    /**
     * Average health left for the planner over every duel against the scripted opponents, scaled by the 20 points
     * of health so the result lands in [-1, 1]. Wins count for more than the health left in a timeout, which is
     * what makes the objective a score rather than a health lead.
     */
    public double score(PlannerParams params)
    {
        int tasks = opponents.size() * 2 * duelsPerOpponent;
        double[] margins = new double[tasks];
        League.Combatant[] roster = opponents.toArray(new League.Combatant[0]);
        Parallel.forEach(tasks, t ->
        {
            int opponent = t / (duelsPerOpponent * 2);
            boolean plannerFirst = (t / duelsPerOpponent) % 2 == 0;
            int duel = t % duelsPerOpponent;
            long seed = seedBase + 7919L * opponent + 104729L * (plannerFirst ? 1 : 2) + 31L * duel;
            DuelPolicy planner = new PlannerOpponent(params, new Random(plannerSeed));
            DuelPolicy other = roster[opponent].factory().get();
            float margin = plannerFirst ? Duel.run(planner, other, seed, maxTicks)
                    : -Duel.run(other, planner, seed, maxTicks);
            margins[t] = margin;
        });
        double total = 0.0;
        for (double margin : margins)
        {
            total += margin;
        }
        return total / (tasks * DuelSim.MAX_HEALTH);
    }

    /**
     * Runs the search from start and returns the best candidate found, which may be start itself if nothing beat it.
     * Children of one generation are evaluated in parallel; each writes its own slot, so the result does not
     * depend on the thread count.
     */
    public Result tune(PlannerParams start, int lambda, int generations, Consumer<String> log)
    {
        Random random = new Random(0x5EEDF00DL);
        PlannerParams parent = start.clamp();
        int budgetTicks = parent.budgetTicks();
        double startScore = score(parent);
        double bestScore = startScore;
        double sigma = SIGMA_START;
        int successes = 0;
        int evaluations = 1;

        for (int generation = 0; generation < generations; generation++)
        {
            double[] base = toVector(parent);
            double[][] candidates = new double[lambda][];
            for (int c = 0; c < lambda; c++)
            {
                double[] vector = new double[COORDS];
                for (int i = 0; i < COORDS; i++)
                {
                    vector[i] = base[i] * Math.exp(sigma * random.nextGaussian());
                }
                candidates[c] = vector;
            }
            double[][] childVectors = new double[lambda][];
            double[] childScores = new double[lambda];
            Parallel.forEach(lambda, c ->
            {
                childVectors[c] = candidates[c];
                childScores[c] = score(fromVector(candidates[c], budgetTicks));
            });
            evaluations += lambda;

            int bestChild = 0;
            for (int c = 1; c < lambda; c++)
            {
                if (childScores[c] > childScores[bestChild])
                {
                    bestChild = c;
                }
            }
            if (childScores[bestChild] > bestScore)
            {
                bestScore = childScores[bestChild];
                parent = fromVector(childVectors[bestChild], budgetTicks);
                successes++;
                if (log != null)
                {
                    log.accept(String.format("generation %2d: score %+.4f  %s", generation, bestScore, parent));
                }
            }
            if (generation % SIGMA_WINDOW == SIGMA_WINDOW - 1)
            {
                sigma = successes * 5 >= SIGMA_WINDOW ? Math.min(SIGMA_MAX, sigma * 1.3)
                        : Math.max(SIGMA_MIN, sigma / 1.3);
                successes = 0;
            }
        }
        return new Result(start.clamp(), parent, startScore, bestScore, evaluations);
    }

    private static double[] toVector(PlannerParams p)
    {
        return new double[] {p.horizon(), p.population(), p.mutationRate(), p.dealtWeight(), p.takenWeight(),
                p.distanceWeight(), p.preferredDistance()};
    }

    private static PlannerParams fromVector(double[] v, int budgetTicks)
    {
        return new PlannerParams((int) Math.round(v[0]), (int) Math.round(v[1]), budgetTicks, v[2], v[3], v[4],
                v[5], v[6]).clamp();
    }

    /**
     * Runs the search from the defaults and prints where it ended up, then scores the result on a held-out seed set.
     * {@code java carpet.pvp.sim.Tuner [duelsPerOpponent] [lambda] [generations] [seedBase]}; the defaults are the
     * run that produced {@link DifficultyPresets#BEST}.
     */
    public static void main(String[] args)
    {
        int duelsPerOpponent = args.length > 0 ? Integer.parseInt(args[0]) : 16;
        int lambda = args.length > 1 ? Integer.parseInt(args[1]) : 16;
        int generations = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        long seedBase = args.length > 3 ? Long.parseLong(args[3]) : 4000L;
        List<League.Combatant> opponents = List.of(
                new League.Combatant("baseline", () -> new BaselineOpponent(new Random(1), 0)),
                new League.Combatant("wtap", () -> new WTapOpponent(new Random(2), 0)),
                new League.Combatant("stap", () -> new STapOpponent(new Random(3), 0)),
                new League.Combatant("crit", () -> new CritOpponent(new Random(4), 0)),
                new League.Combatant("strafe", () -> new StrafeOpponent(new Random(5), 0)),
                new League.Combatant("jreset", () -> new JumpResetOpponent(new Random(6), 0)),
                new League.Combatant("hselect", () -> new HitSelectOpponent(new Random(7), 0)),
                new League.Combatant("combo", () -> new ComboOpponent(new Random(8), 0)));
        Result result = new Tuner(opponents, duelsPerOpponent, seedBase, Duel.DEFAULT_MAX_TICKS, 99)
                .tune(PlannerParams.DEFAULTS, lambda, generations, System.out::println);
        Tuner heldOut = new Tuner(opponents, duelsPerOpponent, seedBase + 5_000_000L, Duel.DEFAULT_MAX_TICKS, 99);
        System.out.println("tuning   start " + String.format("%+.4f", result.startScore())
                + "  best " + String.format("%+.4f", result.bestScore()));
        System.out.println("held-out start " + String.format("%+.4f", heldOut.score(result.start()))
                + "  best " + String.format("%+.4f", heldOut.score(result.best())));
        System.out.println("best    " + result.best());
        System.out.println("evaluations " + result.evaluations());
    }
}