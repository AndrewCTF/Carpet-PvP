package carpet.pvp.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathSmootherTest
{
    private static final int JUMP = 1;
    private static final int FALL = 2;
    private static final int PARKOUR = 3;

    private record Waypoint(int x, int y, int z, int move)
    {
    }

    /** Ground at y = 0 over x = -8..15, z = -8..15. */
    private static TestGrid open()
    {
        return TestGrid.flat(-8, -8, 24, 24);
    }

    private static List<String> smooth(TestGrid grid, List<Waypoint> path)
    {
        long[] packed = new long[path.size()];
        int[] moves = new int[path.size()];
        for (int i = 0; i < path.size(); i++)
        {
            Waypoint step = path.get(i);
            packed[i] = NavPos.pack(step.x(), step.y(), step.z());
            moves[i] = step.move();
        }
        int count = PathSmoother.smooth(grid, packed, moves, path.size(), packed, moves);
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            out.add(NavPos.unpackX(packed[i]) + "," + NavPos.unpackY(packed[i]) + "," + NavPos.unpackZ(packed[i])
                    + (moves[i] == PathSmoother.WALK ? "" : "/" + moves[i]));
        }
        return out;
    }

    private static List<Waypoint> walk(int... xz)
    {
        List<Waypoint> path = new ArrayList<>(xz.length / 2);
        for (int i = 0; i < xz.length; i += 2)
        {
            path.add(new Waypoint(xz[i], 1, xz[i + 1], PathSmoother.WALK));
        }
        return path;
    }

    @Test
    void aZigzagAcrossOpenGroundCollapsesToTwoWaypoints()
    {
        List<Waypoint> path = walk(0, 0, 1, 0, 1, 1, 2, 1, 2, 2, 3, 2, 3, 3, 4, 3, 4, 4, 5, 4, 5, 5, 6, 5, 6, 6, 7, 6, 7, 7,
                8, 7, 8, 8);
        //   input    (0,1,0) (1,1,0) (1,1,1) (2,1,1) ... (8,1,7) (8,1,8)   17 waypoints
        //   smoothed (0,1,0) (8,1,8)                                        2 waypoints
        assertEquals(List.of("0,1,0", "8,1,8"), smooth(open(), path));
    }

    @Test
    void theWaypointsAroundAWallKeepTheTurn()
    {
        TestGrid grid = open();
        //   wall     solid at x = 4, feet and head, for z = 1..7
        grid.wall(4, 1, 4, 7);
        List<Waypoint> path = walk(0, 0, 1, 0, 2, 0, 3, 0, 3, 1, 3, 2, 3, 3, 3, 4, 3, 5, 3, 6, 3, 7, 3, 8, 4, 8,
                5, 8, 5, 7, 5, 6, 5, 5, 5, 4, 5, 3, 5, 2, 5, 1);
        //   input    (0,1,0) ... (3,1,8) (4,1,8) (5,1,8) ... (5,1,1)       21 waypoints
        //   smoothed (0,1,0) (3,1,8) (5,1,8) (5,1,1)                        4 waypoints
        //
        // (3,1,8) and (5,1,8) have to stay: the box reaches into (4,1,7) while cutting the corner from
        // (3,1,8) to (5,1,7), and the wall is there. (4,1,8) itself goes, because it is clear ground.
        assertEquals(List.of("0,1,0", "3,1,8", "5,1,8", "5,1,1"), smooth(grid, path));
    }

    @Test
    void everyLegThatSurvivesIsWalkable()
    {
        TestGrid grid = open();
        grid.wall(4, 1, 4, 7);
        List<String> smoothed = smooth(grid, walk(0, 0, 1, 0, 2, 0, 3, 0, 3, 1, 3, 2, 3, 3, 3, 4, 3, 5, 3, 6, 3, 7,
                3, 8, 4, 8, 5, 8, 5, 7, 5, 6, 5, 5, 5, 4, 5, 3, 5, 2, 5, 1));
        for (int i = 1; i < smoothed.size(); i++)
        {
            int[] from = xyz(smoothed.get(i - 1));
            int[] to = xyz(smoothed.get(i));
            assertTrue(DirectSteer.canWalkLine(grid, from[0], from[1], from[2], to[0], to[1], to[2]),
                    "leg " + smoothed.get(i - 1) + " -> " + smoothed.get(i));
        }
    }

    @Test
    void aJumpKeepsItsAnchors()
    {
        TestGrid grid = open();
        grid.platform(4, -8, 15, 15, 2);
        List<Waypoint> path = new ArrayList<>();
        for (int x = 0; x <= 3; x++)
        {
            path.add(new Waypoint(x, 1, 0, PathSmoother.WALK));
        }
        path.add(new Waypoint(4, 2, 0, JUMP));
        for (int x = 5; x <= 7; x++)
        {
            path.add(new Waypoint(x, 2, 0, PathSmoother.WALK));
        }
        //   input    (0,1,0) (1,1,0) (2,1,0) (3,1,0) (4,2,0)/jump (5,2,0) (6,2,0) (7,2,0)
        //   smoothed (0,1,0) (3,1,0) (4,2,0)/jump (7,2,0)
        //
        // The run before the step is pulled into one line, the jump is still there, and the platform after it is
        // pulled into another. No line is drawn across the step.
        assertEquals(List.of("0,1,0", "3,1,0", "4,2,0/1", "7,2,0"), smooth(grid, path));
    }

    @Test
    void fallsAndParkourGapsKeepTheirAnchors()
    {
        TestGrid grid = open();
        grid.platform(-8, -8, -1, 15, 4);
        grid.clear(2, 0, 0, 4, 0, 0);
        List<Waypoint> path = List.of(
                new Waypoint(0, 4, 0, PathSmoother.WALK),
                new Waypoint(0, 3, 0, FALL),
                new Waypoint(0, 2, 0, FALL),
                new Waypoint(0, 1, 0, FALL),
                new Waypoint(1, 1, 0, PathSmoother.WALK),
                new Waypoint(4, 1, 0, PARKOUR),
                new Waypoint(5, 1, 0, PARKOUR),
                new Waypoint(6, 1, 0, PathSmoother.WALK));
        //   input    (0,4,0) (0,3,0)/fall (0,2,0)/fall (0,1,0)/fall (1,1,0) (4,1,0)/parkour (5,1,0)/parkour (6,1,0)
        //   smoothed the same eight, nothing may be pulled across a fall or a gap
        assertEquals(List.of("0,4,0", "0,3,0/2", "0,2,0/2", "0,1,0/2", "1,1,0", "4,1,0/3", "5,1,0/3", "6,1,0"),
                smooth(grid, path));
    }

    @Test
    void aGapIsNotWalkedAcrossEvenWhenTheMovesArePlainWalks()
    {
        TestGrid grid = open();
        grid.clear(2, 0, 0, 4, 0, 0);
        List<Waypoint> path = List.of(
                new Waypoint(0, 1, 0, PathSmoother.WALK),
                new Waypoint(1, 1, 0, PathSmoother.WALK),
                new Waypoint(2, 1, 0, PathSmoother.WALK),
                new Waypoint(3, 1, 0, PathSmoother.WALK),
                new Waypoint(4, 1, 0, PathSmoother.WALK),
                new Waypoint(5, 1, 0, PathSmoother.WALK));
        // Nothing stands over the hole, so a pathfinder would never produce this one, and a smoother must not
        // pretend a straight walk across it is fine.
        assertEquals(path.size(), smooth(grid, path).size());
    }

    @Test
    void packedPositionsSurviveTheRoundTrip()
    {
        int[][] cases = {{0, 0, 0}, {-1, -1, -1}, {12345, -60, -300}, {-33554431, 2047, 33554431}};
        for (int[] c : cases)
        {
            long packed = NavPos.pack(c[0], c[1], c[2]);
            assertEquals(c[0], NavPos.unpackX(packed));
            assertEquals(c[1], NavPos.unpackY(packed));
            assertEquals(c[2], NavPos.unpackZ(packed));
        }
        assertTrue(NavPos.canPack(-33554431, 33554431));
        assertTrue(NavPos.canPack(0, 0));
    }

    @Test
    void smoothingAllocatesNothingAfterWarmUp()
    {
        TestGrid grid = open();
        long[] path = new long[129];
        int[] moves = new int[129];
        long[] out = new long[129];
        int[] outMoves = new int[129];
        for (int i = 0; i < 129; i++)
        {
            path[i] = NavPos.pack(i / 16, 1, i % 16);
            moves[i] = i == 64 ? JUMP : PathSmoother.WALK;
        }
        assertEquals(0, AllocationCounter.measure(() -> {
            AllocationCounter.keep(PathSmoother.smooth(grid, path, moves, 129, out, outMoves));
            for (int i = 0; i < 16; i++)
            {
                AllocationCounter.keep(out[i] + outMoves[i]);
            }
        }, 2000));
        assertNotEquals(0, out[0]);
    }

    private static int[] xyz(String waypoint)
    {
        int[] out = new int[3];
        String[] parts = waypoint.split(",");
        for (int i = 0; i < 3; i++)
        {
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }
}