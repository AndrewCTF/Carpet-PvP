package carpet.pvp.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DirectSteerTest
{
    // Ground at y = 0 over x = -8..15, z = -8..15, so a player walks with its feet at y = 1 and head at y = 2.
    private static TestGrid open()
    {
        return TestGrid.flat(-8, -8, 24, 24);
    }

    @Test
    void openGroundIsWalkable()
    {
        TestGrid grid = open();
        // 8 blocks along x, along z, and on the diagonal:
        //   ground at y = 0, nothing at y = 1 or 2 -> every block the 0.6-wide box covers is standable.
        assertTrue(DirectSteer.canWalkLine(grid, 0, 1, 0, 8, 1, 0));
        assertTrue(DirectSteer.canWalkLine(grid, 0, 1, 0, 0, 1, 8));
        assertTrue(DirectSteer.canWalkLine(grid, 0, 1, 0, 8, 1, 8));
        // A walk to where the player already stands only has that one block to check.
        assertTrue(DirectSteer.canWalkLine(grid, 3, 1, 3, 3, 1, 3));
    }

    @Test
    void oneBlockGapIsNotWalkable()
    {
        TestGrid grid = open();
        assertTrue(DirectSteer.canWalkLine(grid, 0, 1, 0, 4, 1, 0), "the walk has to start out clear");
        grid.clear(2, 0, 0, 2, 0, 0);
        // The hole at (2, 0, 0) has no ground, so the box would fall into it:
        //   x from 0.5 to 4.5 covers columns 0..4, column 2 has nothing to stand on.
        assertFalse(DirectSteer.canWalkLine(grid, 0, 1, 0, 4, 1, 0));
        assertFalse(DirectSteer.canWalkLine(grid, 0, 1, 0, 4, 1, 0), "and it stays that way");
        // Two blocks further on, where the floor is still whole, the walk is fine again.
        assertTrue(DirectSteer.canWalkLine(grid, 5, 1, 0, 9, 1, 0));
    }

    @Test
    void aWallIsNotWalkable()
    {
        TestGrid grid = open();
        grid.wall(2, -8, 2, 15);
        assertFalse(DirectSteer.canWalkLine(grid, 0, 1, 0, 4, 1, 0));
        // A gap in the wall is a doorway the box fits through.
        grid.clear(2, 1, 0, 2, 2, 0);
        assertTrue(DirectSteer.canWalkLine(grid, 0, 1, 0, 4, 1, 0));
    }

    @Test
    void aOneWideCorridorIsWalkable()
    {
        TestGrid grid = open();
        grid.wall(-1, -8, -1, 15).wall(1, -8, 1, 15);
        // The box spans 0.2 to 0.8 of a block, so it stays inside column 0 and never touches the walls.
        assertTrue(DirectSteer.canWalkLine(grid, 0, 1, 0, 0, 1, 6));
        assertFalse(DirectSteer.canWalkLine(grid, 0, 1, 0, 2, 1, 0), "the corridor has no exit that way");
        // Nothing overhead, though, and the box needs both its own block and the one above it.
        grid.solid(0, 2, 3, 0, 2, 3);
        assertFalse(DirectSteer.canWalkLine(grid, 0, 1, 0, 0, 1, 6));
    }

    @Test
    void aCornerTheBoxWouldClipIsNotWalkable()
    {
        TestGrid grid = open();
        // On the diagonal from (0, 1, 0) to (3, 1, 3) the centre line runs through (0,0) (1,1) (2,2) (3,3) and
        // never through (2, 1), but the box still reaches into (2, 1) on its way past.
        grid.solid(2, 1, 1, 2, 2, 1);
        assertFalse(DirectSteer.canWalkLine(grid, 0, 1, 0, 3, 1, 3));
        grid.clear(2, 1, 1, 2, 2, 1);
        assertTrue(DirectSteer.canWalkLine(grid, 0, 1, 0, 3, 1, 3));
    }

    @Test
    void hazardsAreNotWalkable()
    {
        TestGrid grid = open();
        grid.hazard(3, 0, 0, 3, 0, 0);
        assertFalse(DirectSteer.canWalkLine(grid, 0, 1, 0, 4, 1, 0));
        grid.clear(3, 0, 0, 3, 0, 0).hazard(3, 2, 0, 3, 2, 0);
        assertFalse(DirectSteer.canWalkLine(grid, 0, 1, 0, 4, 1, 0), "head height counts too");
    }

    @Test
    void onlyALevelWalkIsAStraightWalk()
    {
        TestGrid grid = open();
        grid.platform(4, -8, 15, 15, 2);
        // A step up is a jump and a drop is a fall, so neither is a straight walk, however open the ground is.
        assertFalse(DirectSteer.canWalkLine(grid, 3, 1, 0, 4, 2, 0));
        assertFalse(DirectSteer.canWalkLine(grid, 4, 2, 0, 3, 1, 0));
        assertTrue(DirectSteer.canWalkLine(grid, 5, 2, 0, 9, 2, 0), "along the top of the platform is fine");
    }

    @Test
    void theSteeringCheckAllocatesNothingAfterWarmUp()
    {
        TestGrid grid = open();
        assertEquals(0, AllocationCounter.measure(() -> {
            long hits = 0;
            for (int i = 0; i < 40; i++)
            {
                hits += DirectSteer.canWalkLine(grid, i, 1, 0, i + 5, 1, 3) ? 1 : 0;
                hits += DirectSteer.canWalkLine(grid, 0, 1, i, 4, 1, i + 4) ? 2 : 0;
            }
            AllocationCounter.keep(hits);
        }, 2000));
    }
}