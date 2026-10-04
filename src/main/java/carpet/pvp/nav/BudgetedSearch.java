package carpet.pvp.nav;

/**
 * Runs an incremental search for a fixed number of node expansions per call and reports whether it is done, so a
 * search can be spread over several ticks instead of stalling one. {@link FlowField} is one such search; the
 * pathfinder can be driven the same way once it grows a resumable form.
 *
 * A wrapper instance holds its own work counter, so a per-tick call allocates nothing.
 */
public final class BudgetedSearch
{
    public enum Status
    {
        DONE,
        CONTINUE
    }

    /** What a search spends: it stops as soon as the budget runs out and picks up where it left off next call. */
    public static final class Budget
    {
        private int limit;
        private int used;

        private Budget(int limit)
        {
            this.limit = Math.max(0, limit);
        }

        private void begin(int maxExpansions)
        {
            limit = Math.max(0, maxExpansions);
            used = 0;
        }

        public boolean left()
        {
            return used < limit;
        }

        /** Counts one node expansion. */
        public void spend()
        {
            used++;
        }

        /** Expansions spent by the run in progress, and by the last one once it has finished. */
        public int used()
        {
            return used;
        }

        /** The cap this run was given. */
        public int limit()
        {
            return limit;
        }
    }

    /** A search that can be paused and resumed. Returns true from {@link #expand} when there is nothing left to do. */
    public interface Step
    {
        boolean expand(Budget budget);
    }

    private final Budget budget;

    public BudgetedSearch()
    {
        this(0);
    }

    public BudgetedSearch(int maxExpansions)
    {
        budget = new Budget(maxExpansions);
    }

    /** Gives {@code step} at most {@code maxExpansions} expansions; {@link #expansions} says how many it used. */
    public Status run(Step step, int maxExpansions)
    {
        budget.begin(maxExpansions);
        return step.expand(budget) ? Status.DONE : Status.CONTINUE;
    }

    /** Gives {@code step} the budget this runner was built with. */
    public Status run(Step step)
    {
        return run(step, budget.limit);
    }

    public int expansions()
    {
        return budget.used;
    }

    public int limit()
    {
        return budget.limit;
    }
}