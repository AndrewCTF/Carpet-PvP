package carpet.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BotBudgetTest
{
    private static final int TOTAL = 20000;
    private static final int TICKS = 200;

    /** Runs {@code fighters} bots for {@code ticks} ticks, each spending everything it was given. */
    private static void run(int fighters, int horizon)
    {
        BotBudget budget = new BotBudget();
        for (int tick = 0; tick < TICKS; tick++)
        {
            budget.beginTick(TOTAL);
            for (int bot = 0; bot < fighters; bot++)
            {
                int share = budget.join();
                if (share >= horizon)
                {
                    budget.spend(share);
                }
            }
            assertTrue(budget.used() <= TOTAL, "tick " + tick + " spent " + budget.used() + " of " + TOTAL);
        }
    }

    @Test
    void theBudgetIsNeverExceeded()
    {
        for (int fighters = 1; fighters <= 32; fighters++)
        {
            run(fighters, 8);
        }
    }

    @Test
    void oneBotGetsTheWholeBudget()
    {
        BotBudget budget = new BotBudget();
        budget.beginTick(TOTAL);
        assertEquals(TOTAL, budget.join());
        budget.spend(budget.share());
        assertEquals(TOTAL, budget.used());
        assertEquals(1, budget.fighters());
    }

    @Test
    void eightFightersSplitEvenlyAndUseItAll()
    {
        BotBudget budget = new BotBudget();
        budget.beginTick(TOTAL);
        for (int bot = 0; bot < 8; bot++)
        {
            budget.join();
        }
        budget.beginTick(TOTAL);
        int[] shares = new int[8];
        for (int bot = 0; bot < 8; bot++)
        {
            shares[bot] = budget.join();
        }
        for (int share : shares)
        {
            assertEquals(TOTAL / 8, share, "every fighter of a tick gets the same share");
        }
        for (int share : shares)
        {
            budget.spend(share);
        }
        assertEquals(TOTAL, budget.used());
        assertEquals(8, budget.fighters());
    }

    @Test
    void theSplitFollowsTheFightersOfThePreviousTick()
    {
        BotBudget budget = new BotBudget();
        for (int tick = 0; tick < 3; tick++)
        {
            budget.beginTick(TOTAL);
            for (int bot = 0; bot < 4; bot++)
            {
                budget.join();
            }
        }
        assertEquals(4, budget.expectedFighters());
        budget.beginTick(TOTAL);
        assertEquals(TOTAL / 4, budget.join());
    }

    @Test
    void aTickWithMoreFightersThanTheLastOneStillFitsInTheBudget()
    {
        BotBudget budget = new BotBudget();
        budget.beginTick(TOTAL);
        for (int bot = 0; bot < 3; bot++)
        {
            budget.join();
        }
        budget.beginTick(TOTAL);
        int used = 0;
        for (int bot = 0; bot < 12; bot++)
        {
            int share = budget.join();
            used += share;
            budget.spend(share);
        }
        assertEquals(TOTAL, used);
        assertTrue(budget.used() <= TOTAL);
    }

    @Test
    void aBotThatJoinsLastOnlyGetsWhatIsLeft()
    {
        BotBudget budget = new BotBudget();
        budget.beginTick(100);
        for (int bot = 0; bot < 3; bot++)
        {
            budget.join();
        }
        budget.beginTick(100);
        int handedOut = 0;
        for (int bot = 0; bot < 3; bot++)
        {
            int share = budget.join();
            budget.spend(share);
            handedOut += share;
        }
        assertEquals(99, handedOut, "three fighters of 33 ticks each");
        int leftover = budget.join();
        assertEquals(1, leftover, "the rounded-down remainder goes to whoever is still around");
        budget.spend(leftover);
        for (int bot = 0; bot < 10; bot++)
        {
            assertEquals(0, budget.join(), "a bot that joins after the budget is gone gets nothing");
            assertEquals(0, budget.share());
        }
    }

    @Test
    void aBudgetOfNothingIsSimplyNeverGiven()
    {
        BotBudget budget = new BotBudget();
        budget.beginTick(0);
        assertEquals(0, budget.total());
        assertEquals(0, budget.join());
    }
}