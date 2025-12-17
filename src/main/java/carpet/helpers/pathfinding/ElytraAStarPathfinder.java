package carpet.helpers.pathfinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Minimal, bounded A* implementation for elytra "air" navigation.
 *
 * Design goals:
 * - Server-safe (hard limits on explored nodes)
 * - Deterministic and fast enough for command-triggered planning
 * - Builds a foundation for future Baritone-style expansion
 */
public final class ElytraAStarPathfinder
{
    public record Settings(
            int maxExpanded,
            int maxQueued,
            int maxRangeXZ,
            int maxRangeY,
            int clearanceAboveTerrain,
            int clearanceInAir,
            int waypointStride
    )
    {
        public static Settings defaults()
        {
            return new Settings(
                    12_000,
                    40_000,
                    160,
                    80,
                    8,
                    2,
                    4
            );
        }
    }

    private static final class Node
    {
        final long key;
        final int x;
        final int y;
        final int z;
        final long parent;
        final float g;
        final float f;

        Node(long key, int x, int y, int z, long parent, float g, float f)
        {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }
    }

    /**
     * Finds an air path from start to goal using a coarse voxel grid.
     * Returns a list of BlockPos waypoints (including start and goal) or null if no path.
     */
    public List<BlockPos> findPath(ServerLevel level, BlockPos start, BlockPos goal, Settings settings)
    {
        // Clamp goal to a safe air Y above terrain.
        BlockPos safeGoal = liftAboveTerrain(level, goal, settings.clearanceAboveTerrain(), settings.clearanceInAir());
        BlockPos safeStart = liftAboveTerrain(level, start, settings.clearanceAboveTerrain(), settings.clearanceInAir());

        long startKey = safeStart.asLong();
        long goalKey = safeGoal.asLong();

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Float.compare(a.f, b.f));
        Map<Long, Node> best = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        Node startNode = new Node(startKey, safeStart.getX(), safeStart.getY(), safeStart.getZ(), 0L, 0.0F,
                heuristic(safeStart.getX(), safeStart.getY(), safeStart.getZ(), safeGoal));
        open.add(startNode);
        best.put(startKey, startNode);

        int expanded = 0;
        while (!open.isEmpty())
        {
            if (expanded++ > settings.maxExpanded())
            {
                return null;
            }
            if (open.size() > settings.maxQueued())
            {
                return null;
            }

            Node cur = open.poll();
            if (cur == null) break;
            if (!best.containsKey(cur.key)) continue;
            if (closed.contains(cur.key)) continue;

            if (cur.key == goalKey)
            {
                return reconstructPath(cur, best);
            }

            closed.add(cur.key);

            // 26-neighborhood, but weighted to favor forward-ish motion.
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        int nx = cur.x + dx;
                        int ny = cur.y + dy;
                        int nz = cur.z + dz;

                        if (!withinBounds(nx, ny, nz, safeStart, safeGoal, settings)) continue;
                        if (!isAirNavigable(level, nx, ny, nz, settings)) continue;

                        long nKey = BlockPos.asLong(nx, ny, nz);
                        if (closed.contains(nKey)) continue;

                        float stepCost = stepCost(dx, dy, dz);
                        float ng = cur.g + stepCost;

                        Node prev = best.get(nKey);
                        if (prev != null && ng >= prev.g) continue;

                        float nf = ng + heuristic(nx, ny, nz, safeGoal);
                        Node next = new Node(nKey, nx, ny, nz, cur.key, ng, nf);
                        best.put(nKey, next);
                        open.add(next);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Down-samples a raw voxel path into fewer waypoints.
     */
    public static List<BlockPos> compressWaypoints(List<BlockPos> raw, int stride)
    {
        if (raw == null || raw.isEmpty()) return raw;
        stride = Math.max(1, stride);
        if (raw.size() <= 2) return raw;

        List<BlockPos> out = new ArrayList<>();
        out.add(raw.get(0));
        for (int i = stride; i < raw.size() - 1; i += stride)
        {
            out.add(raw.get(i));
        }
        out.add(raw.get(raw.size() - 1));
        return out;
    }

    private static float heuristic(int x, int y, int z, BlockPos goal)
    {
        float dx = x - goal.getX();
        float dy = y - goal.getY();
        float dz = z - goal.getZ();
        return Mth.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float stepCost(int dx, int dy, int dz)
    {
        // Slightly penalize vertical motion so the search prefers going around when possible.
        float base = (dx != 0 && dz != 0) ? 1.4142F : 1.0F;
        if (dy != 0) base += 0.35F;
        if (dx != 0 && dy != 0 && dz != 0) base += 0.15F;
        return base;
    }

    private static boolean withinBounds(int x, int y, int z, BlockPos start, BlockPos goal, Settings settings)
    {
        int minX = Math.min(start.getX(), goal.getX()) - settings.maxRangeXZ();
        int maxX = Math.max(start.getX(), goal.getX()) + settings.maxRangeXZ();
        int minZ = Math.min(start.getZ(), goal.getZ()) - settings.maxRangeXZ();
        int maxZ = Math.max(start.getZ(), goal.getZ()) + settings.maxRangeXZ();
        int minY = Math.min(start.getY(), goal.getY()) - settings.maxRangeY();
        int maxY = Math.max(start.getY(), goal.getY()) + settings.maxRangeY();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY;
    }

    private static boolean isAirNavigable(ServerLevel level, int x, int y, int z, Settings settings)
    {
        // Avoid chunk loads: only allow already-loaded chunks.
        if (!level.hasChunk(ChunkPos.asLong(x >> 4, z >> 4)))
        {
            return false;
        }

        // Require sufficient altitude over terrain.
        int terrain = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y < terrain + settings.clearanceAboveTerrain())
        {
            return false;
        }

        // Require clearance-in-air (player-ish sized) so we don't clip blocks.
        for (int dy = 0; dy < settings.clearanceInAir(); dy++)
        {
            BlockState state = level.getBlockState(new BlockPos(x, y + dy, z));
            if (!state.getCollisionShape(level, new BlockPos(x, y + dy, z)).isEmpty())
            {
                return false;
            }
        }
        return true;
    }

    private static BlockPos liftAboveTerrain(ServerLevel level, BlockPos pos, int clearanceAboveTerrain, int clearanceInAir)
    {
        int x = pos.getX();
        int z = pos.getZ();
        int terrain = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int minY = terrain + clearanceAboveTerrain;
        int y = Math.max(pos.getY(), minY);
        y = Mth.clamp(y, level.getMinY() + 2, level.getMaxY() - 2 - clearanceInAir);
        return new BlockPos(x, y, z);
    }

    private static List<BlockPos> reconstructPath(Node goal, Map<Long, Node> best)
    {
        List<BlockPos> rev = new ArrayList<>();
        Node cur = goal;
        int guard = 0;
        while (cur != null && guard++ < 200_000)
        {
            rev.add(new BlockPos(cur.x, cur.y, cur.z));
            if (cur.parent == 0L) break;
            cur = best.get(cur.parent);
        }
        // reverse
        List<BlockPos> out = new ArrayList<>(rev.size());
        for (int i = rev.size() - 1; i >= 0; i--)
        {
            out.add(rev.get(i));
        }
        return out;
    }
}
