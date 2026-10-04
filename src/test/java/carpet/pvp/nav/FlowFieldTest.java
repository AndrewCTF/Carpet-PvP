package carpet.pvp.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import org.junit.jupiter.api.Test;

import carpet.pvp.nav.BudgetedSearch.Status;

class FlowFieldTest
{
    private static final int RADXZ = 10;
    private static final int RADY = 3;
    private static final double REBUILD_DISTANCE = 2.0D;
    /** Kept inside the region, so the bounded field has the same routes to work with as an unbounded search. */
    private static final int RANGE = 6;

    /**
     * A flat world with a wall to walk round, a platform to step up onto, a blocking slab and a hazard.
     * Ground at y = 0 over x = -10..13, z = -10..13; the wall is x = 0, z = -4..-1; the platform's surface is
     * y = 1 over x = 3..6, z = -2..2, so a player on it stands at y = 2.
     */
    private static TestGrid world()
    {
        TestGrid grid = new TestGrid(-10, 0, -10, 24, 8, 24);
        grid.fill(-10, 0, -10, 13, 0, 13, TestGrid.SOLID);
        grid.wall(0, -4, 0, -1);
        grid.platform(3, -2, 6, 2, 2);
        grid.solid(-4, 1, -6, -4, 2, -6);
        grid.hazard(4, 0, 6, 4, 0, 6);
        return grid;
    }

    /** Runs a field to completion and returns the number of expansions it needed. */
    private static int build(FlowField field, TestGrid grid, int x, int y, int z, int budget)
    {
        field.rebuild(grid, x, y, z);
        BudgetedSearch runner = new BudgetedSearch();
        int calls = 0;
        while (runner.run(field, budget) == Status.CONTINUE)
        {
            assertTrue(++calls < 10_000, "field never finished");
        }
        assertTrue(field.ready());
        return runner.expansions();
    }

    @Test
    void everyCellLeadsToTheTargetAlongAShortestRoute()
    {
        TestGrid grid = world();
        FlowField field = new FlowField(RADXZ, RADY, REBUILD_DISTANCE);
        build(field, grid, 0, 1, 0, 4);

        // The wall at x = 0, z = -4..-1 splits the field in two, so a cell on the far side has to come back round
        // one of its ends instead of walking at the target:
        //   (0,1,-5) (1,1,-5) (1,1,-4) (1,1,-3) (1,1,-2) (1,1,-1) (1,1,0) (0,1,0)   7 steps, 70 tenths
        // The diagonals cannot shorten it, because (0,1,-4) and (0,1,-1) are wall.
        assertEquals(0, field.cost(0, 1, 0), "the target has reached itself");
        assertEquals(70, field.cost(0, 1, -5));
        assertTrue(field.cost(0, 1, -6) > field.cost(0, 1, -5), "and it costs more the further round it is");

        // Follow the field's own directions from every cell: the cost has to drop by exactly what the step costs,
        // and the walk has to end on the target rather than loop or dead-end.
        for (int x = -RANGE; x <= RANGE; x++)
        {
            for (int y = 0; y <= 3; y++)
            {
                for (int z = -RANGE; z <= RANGE; z++)
                {
                    checkRoute(field, x, y, z, grid);
                }
            }
        }
    }

    @Test
    void costsMatchAnIndependentSearchOfTheSameGrid()
    {
        TestGrid grid = world();
        FlowField field = new FlowField(RADXZ, RADY, REBUILD_DISTANCE);
        int expanded = build(field, grid, 0, 1, 0, 100_000);
        Map<Long, Integer> reference = referenceCosts(grid, 0, 1, 0);
        assertTrue(expanded > 100, "only " + expanded + " cells expanded");

        int compared = 0;
        for (int x = -RANGE; x <= RANGE; x++)
        {
            for (int y = 0; y <= 3; y++)
            {
                for (int z = -RANGE; z <= RANGE; z++)
                {
                    Integer expected = reference.get(NavPos.pack(x, y, z));
                    assertEquals(expected == null ? -1 : expected, field.cost(x, y, z),
                            "cost to the target from " + x + "," + y + "," + z);
                    compared++;
                }
            }
        }
        assertTrue(compared > 100, "only " + compared + " cells compared");
    }

    @Test
    void diagonalsDoNotSqueezeThroughACorner()
    {
        TestGrid grid = TestGrid.flat(-6, -6, 13, 13);
        // (1, 1, 1) is walled in on all four sides, so the only way to it from the target (0, 1, 0) is the
        // diagonal, and a diagonal needs both of the cells it passes to be free: (1, 1, 0) and (0, 1, 1).
        grid.solid(0, 1, 1, 0, 2, 1);
        grid.solid(1, 1, 0, 1, 2, 0);
        grid.solid(2, 1, 1, 2, 2, 1);
        grid.solid(1, 1, 2, 1, 2, 2);
        FlowField field = new FlowField(3, 1, REBUILD_DISTANCE);
        build(field, grid, 0, 1, 0, 1000);
        assertEquals(0, field.cost(0, 1, 0));
        assertEquals(FlowField.NO_ROUTE, field.direction(1, 1, 1));
        assertEquals(-1, field.cost(1, 1, 1));

        grid.clear(1, 1, 0, 1, 2, 0).clear(0, 1, 1, 0, 2, 1);
        field = new FlowField(3, 1, REBUILD_DISTANCE);
        build(field, grid, 0, 1, 0, 1000);
        assertEquals(FlowField.stepCost(1, 0, 1), field.cost(1, 1, 1), "one diagonal is all it takes");
        assertEquals(-1, FlowField.stepX(field.direction(1, 1, 1)), "the step points back at the target");
        assertEquals(0, FlowField.stepY(field.direction(1, 1, 1)));
        assertEquals(-1, FlowField.stepZ(field.direction(1, 1, 1)));
    }

    @Test
    void aTargetWithNoBodyLeavesAnEmptyField()
    {
        TestGrid grid = TestGrid.flat(0, 0, 4, 4);
        grid.solid(1, 1, 1, 1, 2, 1);
        FlowField field = new FlowField(1, 1, REBUILD_DISTANCE);
        build(field, grid, 1, 1, 1, 100);
        assertTrue(field.ready());
        assertEquals(FlowField.NO_ROUTE, field.direction(2, 1, 2), "nothing to expand from");
        assertEquals(-1, field.cost(1, 1, 1), "not even the target has a cost");
    }

    @Test
    void aBudgetCapsEachCallAndTheSearchResumes()
    {
        TestGrid grid = world();
        FlowField field = new FlowField(RADXZ, RADY, REBUILD_DISTANCE);
        field.rebuild(grid, 0, 1, 0);
        BudgetedSearch runner = new BudgetedSearch();

        assertEquals(Status.CONTINUE, runner.run(field, 7));
        assertEquals(7, runner.expansions());
        assertFalse(field.ready(), "a budget of 7 cannot finish a region of " + field.cells() + " cells");
        assertEquals(FlowField.NO_ROUTE, field.direction(-4, 1, 0), "half a field has no directions to give");

        int total = runner.expansions();
        int calls = 1;
        while (runner.run(field, 7) == Status.CONTINUE)
        {
            assertEquals(7, runner.expansions(), "call " + calls);
            total += runner.expansions();
            assertTrue(++calls < 10_000, "field never finished");
        }
        total += runner.expansions();
        assertTrue(field.ready());
        assertTrue(calls > 10, "a region of " + field.cells() + " cells should take more than ten calls");

        // Splitting the search over many calls has to reach exactly what one big call reaches.
        FlowField single = new FlowField(RADXZ, RADY, REBUILD_DISTANCE);
        single.rebuild(grid, 0, 1, 0);
        runner.run(single, 1_000_000);
        assertEquals(runner.expansions(), total, "resuming expanded a cell twice, or missed one");
        for (int x = -RANGE; x <= RANGE; x++)
        {
            for (int y = 0; y <= 3; y++)
            {
                for (int z = -RANGE; z <= RANGE; z++)
                {
                    assertEquals(single.cost(x, y, z), field.cost(x, y, z), "cost from " + x + "," + y + "," + z);
                    assertEquals(single.direction(x, y, z), field.direction(x, y, z),
                            "direction from " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void theFieldIsOnlyRebuiltOnceTheTargetHasDrifted()
    {
        TestGrid grid = world();
        FlowField field = new FlowField(RADXZ, RADY, REBUILD_DISTANCE);
        build(field, grid, 0, 1, 0, 100_000);
        int first = field.direction(-4, 1, 0);
        assertEquals(1, FlowField.stepX(first), "a cell west of the target walks east");

        // Inside the rebuild distance the finished field is kept, and a call over it costs nothing.
        assertFalse(field.rebuild(grid, 1, 1, 0));
        assertTrue(field.ready());
        assertEquals(Status.DONE, field.update(grid, 1, 1, 0, 1000));
        assertEquals(first, field.direction(-4, 1, 0));

        assertFalse(field.rebuild(grid, 2, 1, 0), "exactly the rebuild distance is not past it");
        assertTrue(field.rebuild(grid, 7, 1, 0), "seven blocks past it is");
        assertFalse(field.ready());
        // A rebuild in flight is finished before another can start, so a target that moves every tick cannot
        // stop the field from ever completing.
        assertFalse(field.rebuild(grid, 9, 1, 0));

        BudgetedSearch runner = new BudgetedSearch();
        int calls = 0;
        while (runner.run(field, 1000) == Status.CONTINUE)
        {
            assertTrue(++calls < 10_000);
        }
        assertTrue(field.ready());
        assertEquals(7, field.targetX(), "it is still the rebuild that was started");
        assertEquals(0, field.cost(7, 1, 0), "the new field is for the new target");
        assertEquals(-1, FlowField.stepX(field.direction(8, 1, 0)), "a cell east of it walks back west");
    }

    @Test
    void thePerTickUpdateAllocatesNothingAfterWarmUp()
    {
        TestGrid grid = world();
        FlowField field = new FlowField(RADXZ, RADY, REBUILD_DISTANCE);
        build(field, grid, 0, 1, 0, 100_000);
        assertEquals(0, AllocationCounter.measure(() -> {
            for (int tick = 0; tick < 64; tick++)
            {
                Status status = field.update(grid, tick % 2, 1, 0, 32);
                AllocationCounter.keep(field.direction(-4, 1, 0) + field.cost(-4, 1, 0) + status.ordinal());
            }
        }, 500));
    }

    // --- helpers ---

    /** Walks a cell by the field's own directions and checks it arrives, in the cheapest number of steps. */
    private static void checkRoute(FlowField field, int x, int y, int z, TestGrid grid)
    {
        int cost = field.cost(x, y, z);
        if (cost < 0)
        {
            return;
        }
        int walked = 0;
        while (cost > 0)
        {
            int direction = field.direction(x, y, z);
            assertNotEquals(FlowField.NO_ROUTE, direction, "no direction out of " + x + "," + y + "," + z);
            int step = FlowField.stepCost(FlowField.stepX(direction), FlowField.stepY(direction),
                    FlowField.stepZ(direction));
            assertTrue(step > 0, "step out of " + x + "," + y + "," + z);
            x += FlowField.stepX(direction);
            y += FlowField.stepY(direction);
            z += FlowField.stepZ(direction);
            cost -= step;
            assertEquals(cost, field.cost(x, y, z), "the field's own step did not shorten the walk");
            assertTrue(++walked < 500, "the walk never arrived");
        }
        assertTrue(field.direction(x, y, z) <= 0, "arrived at " + x + "," + y + "," + z);
        assertTrue(grid.canStand(x, y, z), "arrived on ground it can stand on");
    }

    /** A plain Dijkstra over the same rules, unbounded, used as the expected answer. */
    private static Map<Long, Integer> referenceCosts(Walkability grid, int tx, int ty, int tz)
    {
        Map<Long, Integer> cost = new HashMap<>();
        PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        long target = NavPos.pack(tx, ty, tz);
        cost.put(target, 0);
        queue.add(new long[] {target, 0L});
        while (!queue.isEmpty())
        {
            long[] entry = queue.poll();
            long at = entry[0];
            int here = (int) entry[1];
            if (cost.get(at) != here)
            {
                continue;
            }
            int x = NavPos.unpackX(at);
            int y = NavPos.unpackY(at);
            int z = NavPos.unpackZ(at);
            for (int dy = -1; dy <= 1; dy++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    for (int dx = -1; dx <= 1; dx++)
                    {
                        int step = FlowField.stepCost(dx, dy, dz);
                        if (step < 0 || !grid.canStand(x + dx, y + dy, z + dz))
                        {
                            continue;
                        }
                        if (dx != 0 && dz != 0
                                && (!grid.canPass(x + dx, y, z) || !grid.canPass(x, y, z + dz)))
                        {
                            continue;
                        }
                        long next = NavPos.pack(x + dx, y + dy, z + dz);
                        int total = here + step;
                        Integer seen = cost.get(next);
                        if (seen == null || total < seen)
                        {
                            cost.put(next, total);
                            queue.add(new long[] {next, total});
                        }
                    }
                }
            }
        }
        return cost;
    }
}