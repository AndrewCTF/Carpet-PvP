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
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bounded voxel A* pathfinder for fake-player navigation on land and in water.
 *
 * Notes:
 * - This is not a full Baritone replacement yet; it’s a server-safe foundation.
 * - Designed for command-driven use and limited automatic replanning.
 */
public final class NavAStarPathfinder
{
    public enum Traversal
    {
        LAND,
        WATER,
        AMPHIBIOUS
    }

    public record Settings(
            int maxExpanded,
            int maxQueued,
            int maxRangeXZ,
            int maxRangeY,
            int maxFall,
            int maxStepUp,
            boolean allowDiagonal,
            boolean allowJumps,
            int maxJumpLength,
            boolean avoidLava,
            boolean avoidFire,
            boolean avoidPowderSnow,
            boolean avoidCobwebs
    )
    {
        public static Settings defaults()
        {
            return new Settings(
                    40_000,
                    120_000,
                    192,
                    96,
                    4,
                    1,
                true,
                true,
                2,
                true,
                true,
                true,
                true
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
     * Returns a list of block positions (including start and goal) or null.
     */
    public List<BlockPos> findPath(ServerLevel level, BlockPos start, BlockPos goal, Traversal traversal, Settings settings)
    {
        BlockPos s = sanitizeStart(level, start, traversal, settings);
        BlockPos g = sanitizeGoal(level, goal, traversal, settings);
        if (s == null || g == null)
        {
            return null;
        }

        long startKey = s.asLong();
        long goalKey = g.asLong();

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Float.compare(a.f, b.f));
        Map<Long, Node> best = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        Node startNode = new Node(startKey, s.getX(), s.getY(), s.getZ(), 0L, 0.0F, heuristic(s, g));
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

            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    if (dx == 0 && dz == 0) continue;
                    if (!settings.allowDiagonal() && dx != 0 && dz != 0) continue;

                    int nx = cur.x + dx;
                    int nz = cur.z + dz;

                    if (!withinBounds(nx, cur.y, nz, s, g, settings)) continue;
                    if (!level.hasChunk(nx >> 4, nz >> 4)) continue;

                    BlockPos nextPos;
                    if (traversal == Traversal.LAND)
                    {
                        nextPos = nextStandableLand(level, cur.x, cur.y, cur.z, nx, nz, settings);
                    }
                    else if (traversal == Traversal.WATER)
                    {
                        nextPos = nextSwimmable(level, cur.x, cur.y, cur.z, nx, nz, settings);
                    }
                    else
                    {
                        nextPos = nextAmphibious(level, cur.x, cur.y, cur.z, nx, nz, settings);
                    }

                    if (nextPos == null) continue;

                    // Avoid corner-cutting when diagonal.
                    if (dx != 0 && dz != 0)
                    {
                        if (!canMoveDiagonally(level, cur.x, cur.y, cur.z, dx, dz, traversal, settings))
                        {
                            continue;
                        }
                    }

                    long nKey = nextPos.asLong();
                    if (closed.contains(nKey)) continue;

                    float stepCost = stepCost(cur.x, cur.y, cur.z, nextPos);
                    float ng = cur.g + stepCost;

                    Node prev = best.get(nKey);
                    if (prev != null && ng >= prev.g) continue;

                    float nf = ng + heuristic(nextPos, g);
                    Node next = new Node(nKey, nextPos.getX(), nextPos.getY(), nextPos.getZ(), cur.key, ng, nf);
                    best.put(nKey, next);
                    open.add(next);
                }
            }

            // Jump links (land only): short parkour over 1-block gaps / small obstacles.
            if ((traversal == Traversal.LAND || traversal == Traversal.AMPHIBIOUS) && settings.allowJumps() && settings.maxJumpLength() >= 2)
            {
                for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                {
                    int dx = dir[0];
                    int dz = dir[1];
                    for (int len = 2; len <= settings.maxJumpLength(); len++)
                    {
                        int nx = cur.x + dx * len;
                        int nz = cur.z + dz * len;

                        if (!withinBounds(nx, cur.y, nz, s, g, settings)) continue;
                        if (!level.hasChunk(nx >> 4, nz >> 4)) continue;

                        // Ensure intermediate chunks are loaded too.
                        int mx = cur.x + dx;
                        int mz = cur.z + dz;
                        if (!level.hasChunk(mx >> 4, mz >> 4)) continue;

                        BlockPos nextPos = nextStandableLand(level, cur.x, cur.y, cur.z, nx, nz, settings);
                        if (nextPos == null) continue;

                        if (!isJumpArcClear(level, cur.x, cur.y, cur.z, nextPos, settings))
                        {
                            continue;
                        }

                        long nKey = nextPos.asLong();
                        if (closed.contains(nKey)) continue;

                        float jumpCost = stepCost(cur.x, cur.y, cur.z, nextPos) + 0.85F; // prefer walking when possible
                        float ng = cur.g + jumpCost;

                        Node prev = best.get(nKey);
                        if (prev != null && ng >= prev.g) continue;

                        float nf = ng + heuristic(nextPos, g);
                        Node next = new Node(nKey, nextPos.getX(), nextPos.getY(), nextPos.getZ(), cur.key, ng, nf);
                        best.put(nKey, next);
                        open.add(next);
                    }
                }
            }
        }

        return null;
    }

    private static boolean isJumpArcClear(ServerLevel level, int fromX, int fromY, int fromZ, BlockPos landing, Settings settings)
    {
        // Very conservative clearance check:
        // require 2-3 blocks of empty space at the midpoint so we don't bonk on low ceilings.
        int dx = Integer.signum(landing.getX() - fromX);
        int dz = Integer.signum(landing.getZ() - fromZ);
        int mx = fromX + dx;
        int mz = fromZ + dz;

        int baseY = Math.max(fromY, landing.getY());
        BlockPos midFeet = new BlockPos(mx, baseY, mz);
        if (!isPassable(level, midFeet, settings)) return false;
        if (!isPassable(level, midFeet.above(), settings)) return false;
        if (!isPassable(level, midFeet.above(2), settings)) return false;
        return true;
    }

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

    private static float heuristic(BlockPos a, BlockPos b)
    {
        float dx = a.getX() - b.getX();
        float dy = a.getY() - b.getY();
        float dz = a.getZ() - b.getZ();
        return Mth.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float stepCost(int x, int y, int z, BlockPos next)
    {
        int dx = Math.abs(next.getX() - x);
        int dz = Math.abs(next.getZ() - z);
        int dy = Math.abs(next.getY() - y);
        float cost = (dx != 0 && dz != 0) ? 1.4142F : 1.0F;
        if (dy != 0) cost += 0.25F * dy;
        return cost;
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

    private static BlockPos sanitizeStart(ServerLevel level, BlockPos start, Traversal traversal, Settings settings)
    {
        if (!level.hasChunk(start.getX() >> 4, start.getZ() >> 4)) return null;

        if (traversal == Traversal.WATER)
        {
            BlockPos s = findNearbySwimmable(level, start, settings);
            return s;
        }

        if (traversal == Traversal.AMPHIBIOUS)
        {
            BlockPos s = findNearbyAmphibious(level, start, settings);
            return s;
        }

        BlockPos s = findNearbyStandable(level, start, settings);
        return s;
    }

    private static BlockPos sanitizeGoal(ServerLevel level, BlockPos goal, Traversal traversal, Settings settings)
    {
        if (!level.hasChunk(goal.getX() >> 4, goal.getZ() >> 4)) return null;

        if (traversal == Traversal.WATER)
        {
            return findNearbySwimmable(level, goal, settings);
        }

        if (traversal == Traversal.AMPHIBIOUS)
        {
            return findNearbyAmphibious(level, goal, settings);
        }

        return findNearbyStandable(level, goal, settings);
    }

    private static BlockPos findNearbyAmphibious(ServerLevel level, BlockPos around, Settings settings)
    {
        BlockPos water = findNearbySwimmable(level, around, settings);
        if (water != null) return water;
        return findNearbyStandable(level, around, settings);
    }

    private static BlockPos findNearbyStandable(ServerLevel level, BlockPos around, Settings settings)
    {
        // Small vertical scan first.
        for (int dy = 0; dy <= 2; dy++)
        {
            BlockPos up = around.above(dy);
            if (isStandable(level, up, settings)) return up;
        }
        for (int dy = 1; dy <= 6; dy++)
        {
            BlockPos down = around.below(dy);
            if (isStandable(level, down, settings)) return down;
        }
        return null;
    }

    private static BlockPos findNearbySwimmable(ServerLevel level, BlockPos around, Settings settings)
    {
        for (int dy = -4; dy <= 4; dy++)
        {
            BlockPos p = around.offset(0, dy, 0);
            if (isSwimmable(level, p, settings)) return p;
        }
        return null;
    }

    private static BlockPos nextStandableLand(ServerLevel level, int fromX, int fromY, int fromZ, int toX, int toZ, Settings settings)
    {
        // Try step-up first.
        for (int stepUp = 0; stepUp <= settings.maxStepUp(); stepUp++)
        {
            BlockPos p = new BlockPos(toX, fromY + stepUp, toZ);
            if (isStandable(level, p, settings))
            {
                // Also ensure the move corridor is passable at body height.
                if (!isBodyPassable(level, p, settings)) return null;
                return p;
            }
        }

        // Then allow falling down.
        for (int fall = 1; fall <= settings.maxFall(); fall++)
        {
            BlockPos p = new BlockPos(toX, fromY - fall, toZ);
            if (isStandable(level, p, settings))
            {
                if (!isBodyPassable(level, p, settings)) return null;
                return p;
            }
        }

        return null;
    }

    private static BlockPos nextSwimmable(ServerLevel level, int fromX, int fromY, int fromZ, int toX, int toZ, Settings settings)
    {
        // Swimming allows vertical drift, but keep it bounded.
        for (int dy = -1; dy <= 1; dy++)
        {
            BlockPos p = new BlockPos(toX, fromY + dy, toZ);
            if (!withinWorldY(level, p.getY())) continue;
            if (isSwimmable(level, p, settings))
            {
                return p;
            }
        }
        return null;
    }

    private static BlockPos nextAmphibious(ServerLevel level, int fromX, int fromY, int fromZ, int toX, int toZ, Settings settings)
    {
        // Prefer staying on land when possible.
        BlockPos land = nextStandableLand(level, fromX, fromY, fromZ, toX, toZ, settings);
        if (land != null) return land;
        return nextSwimmable(level, fromX, fromY, fromZ, toX, toZ, settings);
    }

    private static boolean withinWorldY(ServerLevel level, int y)
    {
        return y >= level.getMinY() + 1 && y <= level.getMaxY() - 2;
    }

    private static boolean canMoveDiagonally(ServerLevel level, int x, int y, int z, int dx, int dz, Traversal traversal, Settings settings)
    {
        BlockPos a = new BlockPos(x + dx, y, z);
        BlockPos b = new BlockPos(x, y, z + dz);
        if (traversal == Traversal.WATER)
        {
            return isSwimmable(level, a, settings) && isSwimmable(level, b, settings);
        }
        if (traversal == Traversal.AMPHIBIOUS)
        {
            return isBodyPassable(level, a, settings) && isBodyPassable(level, b, settings);
        }
        return isBodyPassable(level, a, settings) && isBodyPassable(level, b, settings);
    }

    private static boolean isBodyPassable(ServerLevel level, BlockPos feet, Settings settings)
    {
        // Player-ish: 2 blocks tall.
        return isPassable(level, feet, settings) && isPassable(level, feet.above(), settings);
    }

    private static boolean isStandable(ServerLevel level, BlockPos feet, Settings settings)
    {
        if (!withinWorldY(level, feet.getY())) return false;
        if (!isBodyPassable(level, feet, settings)) return false;

        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        if (settings.avoidLava() && ground.getFluidState().is(FluidTags.LAVA)) return false;
        if (settings.avoidFire() && (ground.is(Blocks.FIRE) || ground.is(Blocks.SOUL_FIRE))) return false;
        if (settings.avoidPowderSnow() && ground.is(Blocks.POWDER_SNOW)) return false;
        if (settings.avoidCobwebs() && ground.is(Blocks.COBWEB)) return false;
        return !ground.getCollisionShape(level, below).isEmpty() && !ground.getFluidState().is(FluidTags.WATER);
    }

    private static boolean isSwimmable(ServerLevel level, BlockPos feet, Settings settings)
    {
        if (!withinWorldY(level, feet.getY())) return false;

        BlockState s0 = level.getBlockState(feet);
        BlockState s1 = level.getBlockState(feet.above());

        boolean inWater = s0.getFluidState().is(FluidTags.WATER);
        boolean headOk = s1.getFluidState().is(FluidTags.WATER) || isPassable(level, feet.above(), settings);
        if (!inWater || !headOk) return false;

        // Avoid swimming into solid blocks.
        return isPassable(level, feet, settings) && isPassable(level, feet.above(), settings);
    }

    private static boolean isPassable(ServerLevel level, BlockPos pos, Settings settings)
    {
        BlockState state = level.getBlockState(pos);
        if (settings.avoidLava() && state.getFluidState().is(FluidTags.LAVA)) return false;
        if (settings.avoidFire() && (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE))) return false;
        if (settings.avoidPowderSnow() && state.is(Blocks.POWDER_SNOW)) return false;
        if (settings.avoidCobwebs() && state.is(Blocks.COBWEB)) return false;
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private static List<BlockPos> reconstructPath(Node goal, Map<Long, Node> best)
    {
        List<BlockPos> rev = new ArrayList<>();
        Node cur = goal;
        int guard = 0;
        while (cur != null && guard++ < 500_000)
        {
            rev.add(new BlockPos(cur.x, cur.y, cur.z));
            if (cur.parent == 0L) break;
            cur = best.get(cur.parent);
        }

        List<BlockPos> out = new ArrayList<>(rev.size());
        for (int i = rev.size() - 1; i >= 0; i--)
        {
            out.add(rev.get(i));
        }
        return out;
    }
}
