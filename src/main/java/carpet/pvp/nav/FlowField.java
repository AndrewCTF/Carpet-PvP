package carpet.pvp.nav;

import carpet.pvp.nav.BudgetedSearch.Budget;
import carpet.pvp.nav.BudgetedSearch.Status;

/**
 * A flow field towards one target, for when many bots chase the same player and none of them should pay for its
 * own search. A Dijkstra expansion runs outwards from the target over a bounded region and leaves, in every cell
 * it reaches, the direction of the next step towards the target.
 *
 * One instance belongs to one target and is shared by every bot chasing it: whoever holds it calls
 * {@link #update} once a tick and the bots read {@link #direction} while it runs. The expansion is capped at a
 * given number of cells per call and carries on where it stopped, so a big region never costs more than its share
 * of one tick. A finished field is kept until the target has moved further than the rebuild distance, because
 * chasing a target that shifted by a block has not made the old field useless.
 *
 * Costs are in tenths of a block, which keeps them exact: walking 10, a diagonal 14, stepping up 12 and dropping
 * off a ledge 7. The field says which way to go, not how to move; the executor still decides whether to walk, jump
 * or swim.
 */
public final class FlowField implements BudgetedSearch.Step
{
    /** Returned by {@link #direction} for a cell outside the field, or one that cannot reach the target. */
    public static final int NO_ROUTE = -1;

    private static final int STRAIGHT = 10;
    private static final int DIAGONAL = 14;
    private static final int STEP_UP = 12;
    private static final int DROP = 7;
    /** Nothing packs to zero, because that would be a step of minus one on all three axes, so zero marks no step. */
    private static final int NO_STEP = 0;

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final int[] MOVE_COST =
            {STRAIGHT, STRAIGHT, STRAIGHT, STRAIGHT, DIAGONAL, DIAGONAL, DIAGONAL, DIAGONAL};

    private final int radiusXZ;
    private final int radiusY;
    private final double rebuildDistance;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    /** Cells per step in x: a column of sizeZ by sizeY. */
    private final int strideX;

    private final int[] cost;
    private final byte[] step;
    private final int[] stamp;
    private final int[] heapCell;
    private final int[] heapKey;
    private final int[] heapPos;
    private final BudgetedSearch runner = new BudgetedSearch();

    private int heapSize;
    private int generation;
    // The region and the view are fixed while a rebuild runs, so a field for a moving target stays self-consistent.
    private Walkability view;
    private int minX;
    private int minY;
    private int minZ;
    private int targetX;
    private int targetY;
    private int targetZ;
    private boolean complete;
    private boolean built;

    public FlowField(int radiusXZ, int radiusY, double rebuildDistance)
    {
        if (radiusXZ < 1 || radiusY < 1)
        {
            throw new IllegalArgumentException("radiusXZ and radiusY must be at least 1");
        }
        this.radiusXZ = radiusXZ;
        this.radiusY = radiusY;
        this.rebuildDistance = rebuildDistance;
        sizeX = sizeZ = 2 * radiusXZ + 1;
        sizeY = 2 * radiusY + 1;
        strideX = sizeZ * sizeY;
        int cells = sizeX * strideX;
        cost = new int[cells];
        step = new byte[cells];
        stamp = new int[cells];
        heapCell = new int[cells];
        heapKey = new int[cells];
        heapPos = new int[cells];
    }

    /**
     * Points the field at a target, starting a rebuild if the last one has run its course, and spends at most
     * {@code maxExpansions} cells of it. Returns {@link Status#DONE} once the whole region has been expanded.
     */
    public Status update(Walkability view, int x, int y, int z, int maxExpansions)
    {
        rebuild(view, x, y, z);
        return runner.run(this, maxExpansions);
    }

    /**
     * Starts a rebuild if the target has moved further than the rebuild distance since the last one and returns
     * whether it did. A rebuild in flight is always finished first, so a target that moves every tick cannot stop
     * the field from ever completing.
     */
    public boolean rebuild(Walkability view, int x, int y, int z)
    {
        if (built && !complete)
        {
            return false;
        }
        if (built && !drifted(x, y, z))
        {
            return false;
        }
        this.view = view;
        minX = x - radiusXZ;
        minY = y - radiusY;
        minZ = z - radiusXZ;
        targetX = x;
        targetY = y;
        targetZ = z;
        heapSize = 0;
        generation++;
        complete = false;
        // A target the player cannot even stand in still pulls bots towards it; every other cell must be standable.
        if (view.canPass(x, y, z) && view.canPass(x, y + 1, z))
        {
            int target = index(x, y, z);
            stamp[target] = generation;
            cost[target] = 0;
            step[target] = 0;
            heapCell[0] = target;
            heapKey[0] = 0;
            heapPos[target] = 0;
            heapSize = 1;
        }
        return true;
    }

    /** True once the whole region has been expanded, which is when the directions mean anything. */
    public boolean ready()
    {
        return complete;
    }

    /** How many cells the current field covers in total, reachable or not. */
    public int cells()
    {
        return sizeX * strideX;
    }

    /** The target the current field was built for. */
    public int targetX()
    {
        return targetX;
    }

    /** The target the current field was built for. */
    public int targetY()
    {
        return targetY;
    }

    /** The target the current field was built for. */
    public int targetZ()
    {
        return targetZ;
    }

    /** The next step towards the target, packed as three unit offsets, or {@link #NO_ROUTE} if there is none. */
    public int direction(int x, int y, int z)
    {
        int cell = cellIndex(x, y, z);
        if (!complete || cell < 0 || stamp[cell] != generation)
        {
            return NO_ROUTE;
        }
        return step[cell];
    }

    /** The distance still to walk in tenths of a block, or -1 if the cell cannot reach the target. */
    public int cost(int x, int y, int z)
    {
        int cell = cellIndex(x, y, z);
        if (cell < 0 || stamp[cell] != generation)
        {
            return -1;
        }
        return cost[cell];
    }

    /** Expands no more cells than the budget has left, and returns true once the region is done. */
    @Override
    public boolean expand(Budget budget)
    {
        while (heapSize > 0 && budget.left())
        {
            budget.spend();
            relaxFrom(pop());
        }
        if (heapSize == 0)
        {
            complete = true;
            built = true;
        }
        return complete;
    }

    private void relaxFrom(int cell)
    {
        int x = minX + cell / strideX;
        int withinLayer = cell % strideX;
        int z = minZ + withinLayer / sizeY;
        int y = minY + withinLayer % sizeY;
        int here = cost[cell];

        for (int i = 0; i < DX.length; i++)
        {
            int nx = x + DX[i];
            int nz = z + DZ[i];
            if (nx < minX || nx >= minX + sizeX || nz < minZ || nz >= minZ + sizeZ)
            {
                continue;
            }
            boolean diagonal = DX[i] != 0 && DZ[i] != 0;
            // A diagonal move has to squeeze between the two blocks it passes, the same rule the pathfinder uses.
            if (diagonal && (!view.canPass(x + DX[i], y, z) || !view.canPass(x, y, z + DZ[i])))
            {
                continue;
            }
            if (view.canStand(nx, y, nz))
            {
                offer(index(nx, y, nz), here + MOVE_COST[i], -DX[i], 0, -DZ[i]);
            }
        }
        if (y > minY && view.canStand(x, y - 1, z))
        {
            offer(index(x, y - 1, z), here + DROP, 0, 1, 0);
        }
        if (y < minY + sizeY - 1 && view.canStand(x, y + 1, z))
        {
            offer(index(x, y + 1, z), here + STEP_UP, 0, -1, 0);
        }
    }

    private void offer(int cell, int newCost, int dx, int dy, int dz)
    {
        byte packed = (byte) packStep(dx, dy, dz);
        if (stamp[cell] == generation)
        {
            // Known already. Every step costs something, so a cell that has been expanded cannot be improved and
            // is still in the heap here.
            if (newCost >= cost[cell])
            {
                return;
            }
            cost[cell] = newCost;
            step[cell] = packed;
            siftUp(heapPos[cell]);
            return;
        }
        stamp[cell] = generation;
        cost[cell] = newCost;
        step[cell] = packed;
        heapPos[cell] = heapSize;
        heapCell[heapSize] = cell;
        heapKey[heapSize] = newCost;
        heapSize++;
        siftUp(heapSize - 1);
    }

    private int pop()
    {
        int cell = heapCell[0];
        heapSize--;
        if (heapSize > 0)
        {
            heapCell[0] = heapCell[heapSize];
            heapKey[0] = heapKey[heapSize];
            heapPos[heapCell[0]] = 0;
            siftDown(0);
        }
        heapPos[cell] = -1;
        return cell;
    }

    private void siftUp(int at)
    {
        int cell = heapCell[at];
        int key = heapKey[at];
        while (at > 0)
        {
            int parent = (at - 1) >>> 1;
            if (heapKey[parent] <= key)
            {
                break;
            }
            heapCell[at] = heapCell[parent];
            heapKey[at] = heapKey[parent];
            heapPos[heapCell[at]] = at;
            at = parent;
        }
        heapCell[at] = cell;
        heapKey[at] = key;
        heapPos[cell] = at;
    }

    private void siftDown(int at)
    {
        int cell = heapCell[at];
        int key = heapKey[at];
        while (at < (heapSize >>> 1))
        {
            int child = at * 2 + 1;
            if (child + 1 < heapSize && heapKey[child + 1] < heapKey[child])
            {
                child++;
            }
            if (heapKey[child] >= key)
            {
                break;
            }
            heapCell[at] = heapCell[child];
            heapKey[at] = heapKey[child];
            heapPos[heapCell[at]] = at;
            at = child;
        }
        heapCell[at] = cell;
        heapKey[at] = key;
        heapPos[cell] = at;
    }

    private boolean drifted(int x, int y, int z)
    {
        double dx = x - targetX;
        double dy = y - targetY;
        double dz = z - targetZ;
        return dx * dx + dy * dy + dz * dz > rebuildDistance * rebuildDistance;
    }

    private int index(int x, int y, int z)
    {
        return ((x - minX) * sizeZ + (z - minZ)) * sizeY + (y - minY);
    }

    private int cellIndex(int x, int y, int z)
    {
        if (x < minX || x >= minX + sizeX || y < minY || y >= minY + sizeY || z < minZ || z >= minZ + sizeZ)
        {
            return -1;
        }
        return index(x, y, z);
    }

    /** Two bits for x at 4 and 5, two for z at 2 and 3, two for y at 0 and 1, all of them offset by one. */
    private static int packStep(int dx, int dy, int dz)
    {
        return (dx + 1) << 4 | (dz + 1) << 2 | (dy + 1);
    }

    /** The x offset of a {@link #direction}, or zero when there is no step. */
    public static int stepX(int direction)
    {
        return direction <= NO_STEP ? 0 : ((direction >> 4) & 3) - 1;
    }

    /** The y offset of a {@link #direction}, or zero when there is no step. */
    public static int stepY(int direction)
    {
        return direction <= NO_STEP ? 0 : (direction & 3) - 1;
    }

    /** The z offset of a {@link #direction}, or zero when there is no step. */
    public static int stepZ(int direction)
    {
        return direction <= NO_STEP ? 0 : ((direction >> 2) & 3) - 1;
    }

    /** What a step of these offsets costs in tenths of a block, or -1 if it is not a step the field can take. */
    public static int stepCost(int dx, int dy, int dz)
    {
        if (dy != 0)
        {
            return dx != 0 || dz != 0 ? -1 : dy > 0 ? STEP_UP : DROP;
        }
        for (int i = 0; i < DX.length; i++)
        {
            if (DX[i] == dx && DZ[i] == dz)
            {
                return MOVE_COST[i];
            }
        }
        return -1;
    }
}