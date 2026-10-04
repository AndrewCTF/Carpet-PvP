package carpet.pvp.sim;

/**
 * The planner's numbers: how much search it does per tick and how it scores a rollout. budgetTicks caps the
 * simulated ticks one plan call may spend, so horizon and population trade off against each other instead of
 * each buying more search.
 */
public record PlannerParams(int horizon, int population, int budgetTicks, double mutationRate,
                            double dealtWeight, double takenWeight, double distanceWeight, double preferredDistance)
{
    public static final PlannerParams DEFAULTS = new PlannerParams(10, 10, 12000, 0.15, 1.0, 1.0, 0.05, 2.6);

    /** The defaults with only the search size replaced. */
    public static PlannerParams search(int horizon, int population)
    {
        return new PlannerParams(horizon, population, DEFAULTS.budgetTicks(), DEFAULTS.mutationRate(),
                DEFAULTS.dealtWeight(), DEFAULTS.takenWeight(), DEFAULTS.distanceWeight(),
                DEFAULTS.preferredDistance());
    }

    /** Clamps every field into the range the search is allowed to explore. */
    public PlannerParams clamp()
    {
        return new PlannerParams(clampInt(horizon, 4, 32), clampInt(population, 4, 48),
                clampInt(budgetTicks, 100, 60000), clamp(mutationRate, 0.01, 0.9),
                clamp(dealtWeight, 0.2, 3.0), clamp(takenWeight, 0.2, 3.0),
                clamp(distanceWeight, 0.0, 0.4), clamp(preferredDistance, 1.0, 4.0));
    }

    private static int clampInt(int value, int min, int max)
    {
        return value < min ? min : Math.min(value, max);
    }

    private static double clamp(double value, double min, double max)
    {
        return value < min ? min : Math.min(value, max);
    }
}