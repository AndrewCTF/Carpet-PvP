package carpet.pvp.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class BudgetedSearchTest
{
    /** Spends from a counter of its own, and reports done once the counter has run out. */
    private static BudgetedSearch.Step counting(int[] left)
    {
        return budget ->
        {
            while (budget.left() && left[0] > 0)
            {
                budget.spend();
                left[0]--;
            }
            return left[0] == 0;
        };
    }

    @Test
    void aStepRunsOutOfBudgetAndSaysSo()
    {
        int[] left = {10};
        BudgetedSearch runner = new BudgetedSearch();
        BudgetedSearch.Step step = counting(left);

        //   budget 3, 10 nodes left -> CONTINUE, and exactly 3 expansions were spent:
        //   (0,1,0) (1,1,0) (2,1,0)                     3
        //   (3,1,0) (4,1,0) (5,1,0) (6,1,0) (7,1,0) (8,1,0) (9,1,0)   7 more
        assertEquals(BudgetedSearch.Status.CONTINUE, runner.run(step, 3));
        assertEquals(3, runner.expansions());
        assertEquals(3, runner.limit());
        assertEquals(7, left[0]);

        assertEquals(BudgetedSearch.Status.CONTINUE, runner.run(step, 3));
        assertEquals(3, runner.expansions());
        assertEquals(4, left[0]);

        // The fourth call has one node left to spend, and finishes the search:
        assertEquals(BudgetedSearch.Status.CONTINUE, runner.run(step, 3));
        assertEquals(3, runner.expansions());
        assertEquals(1, left[0]);

        assertEquals(BudgetedSearch.Status.DONE, runner.run(step, 3));
        assertEquals(1, runner.expansions());
        assertEquals(0, left[0]);
    }

    @Test
    void aZeroBudgetSpendsNothingAndMakesNoProgress()
    {
        int[] left = {5};
        BudgetedSearch runner = new BudgetedSearch();
        assertEquals(BudgetedSearch.Status.CONTINUE, runner.run(counting(left), 0));
        assertEquals(0, runner.expansions());
        assertEquals(5, left[0]);
        assertEquals(0, new BudgetedSearch(-4).limit(), "a nonsense budget is clamped, not obeyed");
    }

    @Test
    void theBudgetBuiltWithIsUsedWhenNoOtherIsGiven()
    {
        int[] left = {4};
        BudgetedSearch runner = new BudgetedSearch(4);
        assertEquals(4, runner.limit());
        assertEquals(BudgetedSearch.Status.DONE, runner.run(counting(left)));
        assertEquals(4, runner.expansions());
    }

    @Test
    void anInstantSearchIsDoneInOneCall()
    {
        BudgetedSearch runner = new BudgetedSearch();
        assertEquals(BudgetedSearch.Status.DONE, runner.run(budget -> true, 1_000));
        assertEquals(0, runner.expansions(), "a step that does no work spends nothing");
    }

    @Test
    void drivingASearchAllocatesNothingAfterWarmUp()
    {
        BudgetedSearch runner = new BudgetedSearch();
        BudgetedSearch.Step step = budget ->
        {
            while (budget.left())
            {
                budget.spend();
            }
            return false;
        };
        assertEquals(0, AllocationCounter.measure(() -> {
            for (int i = 0; i < 64; i++)
            {
                runner.run(step, 64);
            }
            AllocationCounter.keep(runner.expansions());
        }, 2000));
        assertNotEquals(0, runner.expansions());
    }
}