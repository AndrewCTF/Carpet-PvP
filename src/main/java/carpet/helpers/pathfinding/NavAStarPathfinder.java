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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Baritone-inspired bounded voxel A* pathfinder for fake-player navigation.
 *
 * Movement types supported:
 *   - Walking (cardinal + diagonal)
 *   - Jumping / ascending (step up 1 block)
 *   - Falling / descending (configurable max fall)
 *   - Parkour (gap-jumping up to maxParkourLength blocks, including ascending)
 *   - Pillar (place block below to go up; high cost)
 *   - Break-through (mine obstacles; configurable cost)
 *   - Descend-mine (mine block below feet to descend)
 *   - Swimming (water traversal)
 *   - Amphibious (land + water)
 *
 * Cost model follows Baritone conventions:
 *   - Walk 1 block = 1.0; diagonal = sqrt(2)
 *   - Jump penalty (extra hunger cost)
 *   - Break penalty (scaled by estimated break time)
 *   - Place/pillar penalty (scarce blocks)
 *   - Sprint multiplier (faster = lower cost)
 *   - Mob avoidance overlay (adds cost near hostile mobs)
 *   - Fall damage penalty (per block beyond safe threshold)
 *   - Soul sand slowdown penalty
 *   - Door/fence-gate traversal with small cost
 */
public final class NavAStarPathfinder
{
    public enum Traversal
    {
        LAND,
        WATER,
        AMPHIBIOUS
    }

    /**
     * Flags attached to each path node indicating the movement used to reach it.
     * The navigation executor uses these to perform the correct action.
     */
    public enum MoveType
    {
        WALK,
        JUMP,
        FALL,
        PARKOUR,
        PILLAR,
        BREAK_THROUGH,
        SWIM,
        DESCEND_MINE
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
            boolean avoidCobwebs,
            // --- Baritone-like extensions ---
            boolean allowBreakThrough,
            float breakCostBase,
            boolean allowPillar,
            float pillarCost,
            boolean allowParkour,
            int maxParkourLength,
            boolean allowDescendMine,
            float descendMineCost,
            boolean allowSprint,
            float sprintCostMultiplier,
            boolean avoidMobs,
            int mobAvoidanceRadius,
            float mobAvoidanceCost,
            int maxFallNoWater,
            float jumpPenalty,
            float fallDamagePenalty,
            boolean allowDiagonalAscend,
            boolean allowDiagonalDescend,
            boolean avoidSoulSand,
            boolean allowOpenDoors,
            boolean allowOpenFenceGates,
            boolean allowSwimming
    )
    {
        public static Settings defaults()
        {
            return new Settings(
                    50_000,     // maxExpanded
                    150_000,    // maxQueued
                    256,        // maxRangeXZ
                    128,        // maxRangeY
                    4,          // maxFall
                    1,          // maxStepUp
                    true,       // allowDiagonal
                    true,       // allowJumps
                    2,          // maxJumpLength
                    true,       // avoidLava
                    true,       // avoidFire
                    true,       // avoidPowderSnow
                    true,       // avoidCobwebs
                    false,      // allowBreakThrough
                    4.0F,       // breakCostBase
                    false,      // allowPillar
                    20.0F,      // pillarCost
                    true,       // allowParkour
                    4,          // maxParkourLength (4-block gap)
                    false,      // allowDescendMine
                    6.0F,       // descendMineCost
                    true,       // allowSprint
                    0.8F,       // sprintCostMultiplier
                    false,      // avoidMobs
                    8,          // mobAvoidanceRadius
                    4.0F,       // mobAvoidanceCost
                    3,          // maxFallNoWater (3 = no damage)
                    0.4F,       // jumpPenalty
                    2.0F,       // fallDamagePenalty
                    true,       // allowDiagonalAscend
                    true,       // allowDiagonalDescend
                    false,      // avoidSoulSand
                    true,       // allowOpenDoors
                    true,       // allowOpenFenceGates
                    false       // allowSwimming (default = float on surface)
            );
        }
    }

    public static final class Node
    {
        public final long key;
        public final int x;
        public final int y;
        public final int z;
        public final long parent;
        public final float g;
        public final float f;
        public final MoveType moveType;

        Node(long key, int x, int y, int z, long parent, float g, float f, MoveType moveType)
        {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
            this.g = g;
            this.f = f;
            this.moveType = moveType;
        }
    }

    /** Pathfinding result containing positions and the movement type used to reach each. */
    public record PathResult(List<BlockPos> positions, List<MoveType> moveTypes)
    {
        public static PathResult empty()
        {
            return new PathResult(List.of(), List.of());
        }
    }

    /**
     * Finds a path from start to goal. Returns a PathResult with positions and
     * move types, or null if no path could be found.
     */
    public PathResult findPath(ServerLevel level, BlockPos start, BlockPos goal, Traversal traversal, Settings settings)
    {
        BlockPos s = sanitizeStart(level, start, traversal, settings);
        BlockPos g = sanitizeGoal(level, goal, traversal, settings);
        if (s == null || g == null)
        {
            return null;
        }

        // Pre-compute mob danger map if avoidance is enabled.
        Set<Long> mobDangerZone = settings.avoidMobs() ? buildMobDangerMap(level, s, g, settings) : Set.of();

        long startKey = s.asLong();
        long goalKey = g.asLong();

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Float.compare(a.f, b.f));
        Map<Long, Node> best = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        Node startNode = new Node(startKey, s.getX(), s.getY(), s.getZ(), 0L, 0.0F, heuristic(s, g, settings), MoveType.WALK);
        open.add(startNode);
        best.put(startKey, startNode);

        int expanded = 0;
        while (!open.isEmpty())
        {
            if (expanded++ > settings.maxExpanded())
            {
                return buildPartialPath(best, closed, g, settings);
            }
            if (open.size() > settings.maxQueued())
            {
                return buildPartialPath(best, closed, g, settings);
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

            // === Standard movement: cardinal + diagonal walking ===
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    if (dx == 0 && dz == 0) continue;
                    boolean isDiag = (dx != 0 && dz != 0);
                    if (!settings.allowDiagonal() && isDiag) continue;

                    int nx = cur.x + dx;
                    int nz = cur.z + dz;

                    if (!withinBounds(nx, cur.y, nz, s, g, settings)) continue;
                    if (!level.hasChunk(nx >> 4, nz >> 4)) continue;

                    BlockPos nextPos;
                    MoveType moveType = MoveType.WALK;

                    if (traversal == Traversal.LAND)
                    {
                        nextPos = nextStandableLand(level, cur.x, cur.y, cur.z, nx, nz, settings);
                    }
                    else if (traversal == Traversal.WATER)
                    {
                        nextPos = nextSwimmable(level, cur.x, cur.y, cur.z, nx, nz, settings);
                        if (nextPos != null) moveType = MoveType.SWIM;
                    }
                    else
                    {
                        nextPos = nextAmphibious(level, cur.x, cur.y, cur.z, nx, nz, settings);
                    }

                    if (nextPos == null)
                    {
                        // If break-through is allowed, check if we can mine through.
                        if (settings.allowBreakThrough() && traversal != Traversal.WATER)
                        {
                            nextPos = nextBreakThrough(level, cur.x, cur.y, cur.z, nx, nz, settings);
                            if (nextPos != null) moveType = MoveType.BREAK_THROUGH;
                        }
                        if (nextPos == null) continue;
                    }

                    // Classify movement type from height difference.
                    int heightDiff = nextPos.getY() - cur.y;
                    if (heightDiff > 0 && moveType == MoveType.WALK) moveType = MoveType.JUMP;
                    if (heightDiff < 0 && moveType == MoveType.WALK) moveType = MoveType.FALL;

                    // Diagonal ascend/descend restrictions.
                    if (isDiag && heightDiff > 0 && !settings.allowDiagonalAscend()) continue;
                    if (isDiag && heightDiff < 0 && !settings.allowDiagonalDescend()) continue;

                    // Avoid corner-cutting on diagonals.
                    if (isDiag)
                    {
                        if (!canMoveDiagonally(level, cur.x, cur.y, cur.z, dx, dz, traversal, settings))
                        {
                            continue;
                        }
                    }

                    long nKey = nextPos.asLong();
                    if (closed.contains(nKey)) continue;

                    float stepCost = calcStepCost(cur.x, cur.y, cur.z, nextPos, moveType, level, mobDangerZone, settings);
                    float ng = cur.g + stepCost;

                    Node prev = best.get(nKey);
                    if (prev != null && ng >= prev.g) continue;

                    float nf = ng + heuristic(nextPos, g, settings);
                    Node next = new Node(nKey, nextPos.getX(), nextPos.getY(), nextPos.getZ(), cur.key, ng, nf, moveType);
                    best.put(nKey, next);
                    open.add(next);
                }
            }

            // === Parkour / gap-jump links ===
            if ((traversal == Traversal.LAND || traversal == Traversal.AMPHIBIOUS)
                    && settings.allowParkour() && settings.maxParkourLength() >= 2)
            {
                expandParkour(level, cur, s, g, settings, closed, best, open, mobDangerZone);
            }

            // === Pillar up ===
            if ((traversal == Traversal.LAND || traversal == Traversal.AMPHIBIOUS)
                    && settings.allowPillar())
            {
                expandPillar(level, cur, s, g, settings, closed, best, open, mobDangerZone);
            }

            // === Descend by mining ===
            if ((traversal == Traversal.LAND || traversal == Traversal.AMPHIBIOUS)
                    && settings.allowDescendMine())
            {
                expandDescendMine(level, cur, s, g, settings, closed, best, open, mobDangerZone);
            }
        }

        return null;
    }

    // ====== Legacy compatibility: returns just positions (for callers that don't need MoveType) ======

    /**
     * Legacy method: returns just the list of positions, or null.
     */
    public List<BlockPos> findPathPositions(ServerLevel level, BlockPos start, BlockPos goal, Traversal traversal, Settings settings)
    {
        PathResult result = findPath(level, start, goal, traversal, settings);
        return result != null ? result.positions() : null;
    }

    // --- Parkour expansion ---
    private void expandParkour(ServerLevel level, Node cur, BlockPos s, BlockPos g,
                                Settings settings, Set<Long> closed, Map<Long, Node> best,
                                PriorityQueue<Node> open, Set<Long> mobDangerZone)
    {
        for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
        {
            int dx = dir[0];
            int dz = dir[1];
            for (int len = 2; len <= settings.maxParkourLength(); len++)
            {
                int nx = cur.x + dx * len;
                int nz = cur.z + dz * len;

                if (!withinBounds(nx, cur.y, nz, s, g, settings)) continue;
                if (!level.hasChunk(nx >> 4, nz >> 4)) continue;

                // Check intermediate chunks are loaded.
                boolean allLoaded = true;
                for (int i = 1; i < len; i++)
                {
                    int mx = cur.x + dx * i;
                    int mz = cur.z + dz * i;
                    if (!level.hasChunk(mx >> 4, mz >> 4)) { allLoaded = false; break; }
                }
                if (!allLoaded) continue;

                // Allow ascending parkour: landing up to 1 block higher or lower.
                for (int dy = -1; dy <= 1; dy++)
                {
                    int targetY = cur.y + dy;
                    BlockPos nextPos = new BlockPos(nx, targetY, nz);
                    if (!isStandable(level, nextPos, settings)) continue;

                    if (!isJumpArcClear(level, cur.x, cur.y, cur.z, nextPos, len, settings))
                    {
                        continue;
                    }

                    long nKey = nextPos.asLong();
                    if (closed.contains(nKey)) continue;

                    float jumpCost = calcStepCost(cur.x, cur.y, cur.z, nextPos, MoveType.PARKOUR, level, mobDangerZone, settings);
                    float ng = cur.g + jumpCost;

                    Node prev = best.get(nKey);
                    if (prev != null && ng >= prev.g) continue;

                    float nf = ng + heuristic(nextPos, g, settings);
                    Node next = new Node(nKey, nextPos.getX(), nextPos.getY(), nextPos.getZ(), cur.key, ng, nf, MoveType.PARKOUR);
                    best.put(nKey, next);
                    open.add(next);
                }
            }
        }
    }

    // --- Pillar expansion: place block at feet, stand on it ---
    private void expandPillar(ServerLevel level, Node cur, BlockPos s, BlockPos g,
                               Settings settings, Set<Long> closed, Map<Long, Node> best,
                               PriorityQueue<Node> open, Set<Long> mobDangerZone)
    {
        int nx = cur.x;
        int nz = cur.z;
        int ny = cur.y + 1;

        if (!withinBounds(nx, ny, nz, s, g, settings)) return;

        BlockPos pillarFeet = new BlockPos(nx, ny, nz);
        if (!isPassable(level, pillarFeet, settings)) return;
        if (!isPassable(level, pillarFeet.above(), settings)) return;
        if (!isPassable(level, pillarFeet.above(2), settings)) return;

        long nKey = pillarFeet.asLong();
        if (closed.contains(nKey)) return;

        float cost = settings.pillarCost() + calcMobOverlayCost(nx, ny, nz, mobDangerZone, settings);
        float ng = cur.g + cost;

        Node prev = best.get(nKey);
        if (prev != null && ng >= prev.g) return;

        float nf = ng + heuristic(pillarFeet, g, settings);
        Node next = new Node(nKey, nx, ny, nz, cur.key, ng, nf, MoveType.PILLAR);
        best.put(nKey, next);
        open.add(next);
    }

    // --- Descend by mining the block at (cur.x, cur.y-1, cur.z) ---
    private void expandDescendMine(ServerLevel level, Node cur, BlockPos s, BlockPos g,
                                    Settings settings, Set<Long> closed, Map<Long, Node> best,
                                    PriorityQueue<Node> open, Set<Long> mobDangerZone)
    {
        int nx = cur.x;
        int nz = cur.z;
        int ny = cur.y - 1;

        if (!withinBounds(nx, ny, nz, s, g, settings)) return;
        if (!withinWorldY(level, ny)) return;

        BlockPos belowFeet = new BlockPos(nx, ny, nz);
        BlockState feetState = level.getBlockState(belowFeet);
        if (feetState.isAir() || isLiquid(feetState)) return; // Already passable = just fall.

        if (!isBreakable(level, belowFeet, feetState, settings)) return;

        // Don't mine into lava.
        BlockState twoBelow = level.getBlockState(belowFeet.below());
        if (settings.avoidLava() && twoBelow.getFluidState().is(FluidTags.LAVA)) return;

        // Ground below the new position must be solid.
        if (twoBelow.getCollisionShape(level, belowFeet.below()).isEmpty()) return;

        long nKey = belowFeet.asLong();
        if (closed.contains(nKey)) return;

        float cost = settings.descendMineCost() + estimateBreakCost(level, belowFeet, settings)
                + calcMobOverlayCost(nx, ny, nz, mobDangerZone, settings);
        float ng = cur.g + cost;

        Node prev = best.get(nKey);
        if (prev != null && ng >= prev.g) return;

        float nf = ng + heuristic(belowFeet, g, settings);
        Node next = new Node(nKey, nx, ny, nz, cur.key, ng, nf, MoveType.DESCEND_MINE);
        best.put(nKey, next);
        open.add(next);
    }

    // --- Break-through: mine 1-2 blocks to walk into a solid column ---
    private static BlockPos nextBreakThrough(ServerLevel level, int fromX, int fromY, int fromZ, int toX, int toZ, Settings settings)
    {
        BlockPos feetPos = new BlockPos(toX, fromY, toZ);
        BlockPos headPos = feetPos.above();
        BlockState feetState = level.getBlockState(feetPos);
        BlockState headState = level.getBlockState(headPos);

        boolean feetNeedsBreak = !feetState.getCollisionShape(level, feetPos).isEmpty();
        boolean headNeedsBreak = !headState.getCollisionShape(level, headPos).isEmpty();

        if (!feetNeedsBreak && !headNeedsBreak) return null; // Already passable.

        if (feetNeedsBreak && !isBreakable(level, feetPos, feetState, settings)) return null;
        if (headNeedsBreak && !isBreakable(level, headPos, headState, settings)) return null;

        // Ground below must be solid.
        BlockPos below = feetPos.below();
        BlockState ground = level.getBlockState(below);
        if (ground.getCollisionShape(level, below).isEmpty()) return null;
        if (settings.avoidLava() && ground.getFluidState().is(FluidTags.LAVA)) return null;

        return feetPos;
    }

    private static boolean isBreakable(ServerLevel level, BlockPos pos, BlockState state, Settings settings)
    {
        if (state.isAir()) return true;
        if (state.getDestroySpeed(level, pos) < 0) return false; // Bedrock, barrier, etc.
        if (!state.getFluidState().isEmpty()) return false;       // Don't "break" liquid.
        return true;
    }

    private static float estimateBreakCost(ServerLevel level, BlockPos pos, Settings settings)
    {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return 0.0F;
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return Float.MAX_VALUE;
        return settings.breakCostBase() + hardness * 2.0F;
    }

    private static boolean isJumpArcClear(ServerLevel level, int fromX, int fromY, int fromZ,
                                           BlockPos landing, int distance, Settings settings)
    {
        int dx = Integer.signum(landing.getX() - fromX);
        int dz = Integer.signum(landing.getZ() - fromZ);

        for (int i = 1; i < distance; i++)
        {
            int mx = fromX + dx * i;
            int mz = fromZ + dz * i;
            int baseY = Math.max(fromY, landing.getY());

            BlockPos midFeet = new BlockPos(mx, baseY, mz);
            if (!isPassable(level, midFeet, settings)) return false;
            if (!isPassable(level, midFeet.above(), settings)) return false;
            if (!isPassable(level, midFeet.above(2), settings)) return false;
        }

        // Headroom at start for the jump.
        BlockPos startHead = new BlockPos(fromX, fromY + 2, fromZ);
        if (!isPassable(level, startHead, settings)) return false;

        return true;
    }

    // --- Mob danger zone computation ---
    private static Set<Long> buildMobDangerMap(ServerLevel level, BlockPos start, BlockPos goal, Settings settings)
    {
        Set<Long> dangerZone = new HashSet<>();
        int radius = settings.mobAvoidanceRadius();

        int minX = Math.min(start.getX(), goal.getX()) - settings.maxRangeXZ();
        int maxX = Math.max(start.getX(), goal.getX()) + settings.maxRangeXZ();
        int minZ = Math.min(start.getZ(), goal.getZ()) - settings.maxRangeXZ();
        int maxZ = Math.max(start.getZ(), goal.getZ()) + settings.maxRangeXZ();

        AABB searchBox = new AABB(minX, level.getMinY(), minZ, maxX, level.getMaxY(), maxZ);
        List<Entity> mobs = level.getEntities((Entity) null, searchBox, e -> e instanceof Monster);

        for (Entity mob : mobs)
        {
            int mobX = Mth.floor(mob.getX());
            int mobY = Mth.floor(mob.getY());
            int mobZ = Mth.floor(mob.getZ());

            for (int ddx = -radius; ddx <= radius; ddx++)
            {
                for (int ddz = -radius; ddz <= radius; ddz++)
                {
                    for (int ddy = -2; ddy <= 2; ddy++)
                    {
                        if (ddx * ddx + ddz * ddz <= radius * radius)
                        {
                            dangerZone.add(BlockPos.asLong(mobX + ddx, mobY + ddy, mobZ + ddz));
                        }
                    }
                }
            }
        }
        return dangerZone;
    }

    private static float calcMobOverlayCost(int x, int y, int z, Set<Long> mobDangerZone, Settings settings)
    {
        if (!settings.avoidMobs() || mobDangerZone.isEmpty()) return 0.0F;
        return mobDangerZone.contains(BlockPos.asLong(x, y, z)) ? settings.mobAvoidanceCost() : 0.0F;
    }

    // --- Cost calculation with all Baritone-like modifiers ---
    private static float calcStepCost(int fx, int fy, int fz, BlockPos next, MoveType moveType,
                                       ServerLevel level, Set<Long> mobDangerZone, Settings settings)
    {
        int dx = Math.abs(next.getX() - fx);
        int dz = Math.abs(next.getZ() - fz);
        int dy = next.getY() - fy;

        float cost;
        switch (moveType)
        {
            case PARKOUR:
                float dist = Mth.sqrt((next.getX() - fx) * (next.getX() - fx) + (next.getZ() - fz) * (next.getZ() - fz));
                cost = dist + settings.jumpPenalty() * 2.0F;
                break;
            case PILLAR:
                cost = settings.pillarCost();
                break;
            case BREAK_THROUGH:
                cost = settings.breakCostBase();
                BlockPos feetPos = new BlockPos(next.getX(), next.getY(), next.getZ());
                BlockPos headPos = feetPos.above();
                cost += estimateBreakCost(level, feetPos, settings);
                cost += estimateBreakCost(level, headPos, settings);
                break;
            case DESCEND_MINE:
                cost = settings.descendMineCost();
                break;
            default:
                cost = (dx != 0 && dz != 0) ? 1.4142F : 1.0F;
                break;
        }

        // Height change penalties.
        if (dy > 0)
        {
            cost += settings.jumpPenalty() * dy;
        }
        else if (dy < 0)
        {
            int fallDist = -dy;
            if (fallDist > settings.maxFallNoWater())
            {
                cost += settings.fallDamagePenalty() * (fallDist - settings.maxFallNoWater());
            }
            cost += 0.1F * fallDist;
        }

        // Sprint discount for flat walking.
        if (settings.allowSprint() && moveType == MoveType.WALK && dy == 0)
        {
            cost *= settings.sprintCostMultiplier();
        }

        // Soul sand slowdown penalty.
        if (settings.avoidSoulSand())
        {
            BlockState belowState = level.getBlockState(new BlockPos(next.getX(), next.getY() - 1, next.getZ()));
            if (belowState.is(Blocks.SOUL_SAND))
            {
                cost *= 2.5F;
            }
        }

        // Ice slippery penalty: slightly increase cost on ice to prefer normal paths.
        {
            BlockState belowForIce = level.getBlockState(new BlockPos(next.getX(), next.getY() - 1, next.getZ()));
            if (isIce(belowForIce))
            {
                cost *= 1.3F;
            }
        }

        // Door/fence-gate cost.
        BlockState nextState = level.getBlockState(new BlockPos(next.getX(), next.getY(), next.getZ()));
        if (nextState.getBlock() instanceof DoorBlock || nextState.getBlock() instanceof FenceGateBlock)
        {
            cost += 1.0F;
        }

        // Mob avoidance overlay.
        cost += calcMobOverlayCost(next.getX(), next.getY(), next.getZ(), mobDangerZone, settings);

        return cost;
    }

    // --- Heuristic ---
    private static float heuristic(BlockPos a, BlockPos b, Settings settings)
    {
        float ddx = a.getX() - b.getX();
        float ddy = a.getY() - b.getY();
        float ddz = a.getZ() - b.getZ();
        float dist = Mth.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        if (settings.allowSprint())
        {
            dist *= settings.sprintCostMultiplier();
        }
        return dist;
    }

    // --- Build partial path toward closest explored node to goal ---
    private PathResult buildPartialPath(Map<Long, Node> best, Set<Long> closed, BlockPos goal, Settings settings)
    {
        Node closest = null;
        float closestDist = Float.MAX_VALUE;
        for (Long key : closed)
        {
            Node node = best.get(key);
            if (node == null) continue;
            float dist = heuristic(new BlockPos(node.x, node.y, node.z), goal, settings);
            if (dist < closestDist)
            {
                closestDist = dist;
                closest = node;
            }
        }
        if (closest == null) return null;
        return reconstructPath(closest, best);
    }

    // --- Standard movement helpers ---

    private static BlockPos nextStandableLand(ServerLevel level, int fromX, int fromY, int fromZ, int toX, int toZ, Settings settings)
    {
        for (int stepUp = 0; stepUp <= settings.maxStepUp(); stepUp++)
        {
            BlockPos p = new BlockPos(toX, fromY + stepUp, toZ);
            if (isStandable(level, p, settings))
            {
                if (!isBodyPassable(level, p, settings)) return null;
                return p;
            }
        }

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
        if (settings.allowSwimming())
        {
            // Full underwater navigation: search wider vertical range.
            for (int dy = -2; dy <= 2; dy++)
            {
                BlockPos p = new BlockPos(toX, fromY + dy, toZ);
                if (!withinWorldY(level, p.getY())) continue;
                if (isSwimmable(level, p, settings))
                {
                    return p;
                }
            }
        }
        else
        {
            // Surface-only: prefer water surface positions (floating mode).
            for (int dy = 1; dy >= -1; dy--)
            {
                BlockPos p = new BlockPos(toX, fromY + dy, toZ);
                if (!withinWorldY(level, p.getY())) continue;
                if (isSwimmable(level, p, settings) && isWaterSurface(level, p))
                {
                    return p;
                }
            }
            // Fallback: allow non-surface swimmable to avoid getting stuck
            // at water entry/exit points.
            for (int dy = 1; dy >= -1; dy--)
            {
                BlockPos p = new BlockPos(toX, fromY + dy, toZ);
                if (!withinWorldY(level, p.getY())) continue;
                if (isSwimmable(level, p, settings))
                {
                    return p;
                }
            }
        }
        return null;
    }

    private static BlockPos nextAmphibious(ServerLevel level, int fromX, int fromY, int fromZ, int toX, int toZ, Settings settings)
    {
        BlockPos land = nextStandableLand(level, fromX, fromY, fromZ, toX, toZ, settings);
        if (land != null) return land;
        return nextSwimmable(level, fromX, fromY, fromZ, toX, toZ, settings);
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
        if (traversal == Traversal.WATER) return findNearbySwimmable(level, start, settings);
        if (traversal == Traversal.AMPHIBIOUS) return findNearbyAmphibious(level, start, settings);
        return findNearbyStandable(level, start, settings);
    }

    private static BlockPos sanitizeGoal(ServerLevel level, BlockPos goal, Traversal traversal, Settings settings)
    {
        if (!level.hasChunk(goal.getX() >> 4, goal.getZ() >> 4)) return null;
        if (traversal == Traversal.WATER) return findNearbySwimmable(level, goal, settings);
        if (traversal == Traversal.AMPHIBIOUS) return findNearbyAmphibious(level, goal, settings);
        return findNearbyStandable(level, goal, settings);
    }

    private static BlockPos findNearbyStandable(ServerLevel level, BlockPos around, Settings settings)
    {
        for (int dy = 0; dy <= 3; dy++)
        {
            BlockPos up = around.above(dy);
            if (isStandable(level, up, settings)) return up;
        }
        for (int dy = 1; dy <= 8; dy++)
        {
            BlockPos down = around.below(dy);
            if (isStandable(level, down, settings)) return down;
        }
        return null;
    }

    private static BlockPos findNearbySwimmable(ServerLevel level, BlockPos around, Settings settings)
    {
        if (!settings.allowSwimming())
        {
            // Surface mode: find highest swimmable (surface) position.
            for (int dy = 4; dy >= -4; dy--)
            {
                BlockPos p = around.offset(0, dy, 0);
                if (isSwimmable(level, p, settings) && isWaterSurface(level, p)) return p;
            }
        }
        // Full swimming or fallback: any swimmable.
        for (int dy = -4; dy <= 4; dy++)
        {
            BlockPos p = around.offset(0, dy, 0);
            if (isSwimmable(level, p, settings)) return p;
        }
        return null;
    }

    private static BlockPos findNearbyAmphibious(ServerLevel level, BlockPos around, Settings settings)
    {
        BlockPos water = findNearbySwimmable(level, around, settings);
        if (water != null) return water;
        return findNearbyStandable(level, around, settings);
    }

    static boolean withinWorldY(ServerLevel level, int y)
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
        return isBodyPassable(level, a, settings) && isBodyPassable(level, b, settings);
    }

    private static boolean isBodyPassable(ServerLevel level, BlockPos feet, Settings settings)
    {
        return isPassable(level, feet, settings) && isPassable(level, feet.above(), settings);
    }

    static boolean isStandable(ServerLevel level, BlockPos feet, Settings settings)
    {
        if (!withinWorldY(level, feet.getY())) return false;
        if (!isBodyPassable(level, feet, settings)) return false;

        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        if (settings.avoidLava() && ground.getFluidState().is(FluidTags.LAVA)) return false;
        if (settings.avoidFire() && (ground.is(Blocks.FIRE) || ground.is(Blocks.SOUL_FIRE))) return false;
        if (settings.avoidPowderSnow() && ground.is(Blocks.POWDER_SNOW)) return false;
        if (settings.avoidCobwebs() && ground.is(Blocks.COBWEB)) return false;
        return !ground.getCollisionShape(level, below).isEmpty()
                && (!ground.getFluidState().is(FluidTags.WATER) || isIce(ground));
    }

    private static boolean isSwimmable(ServerLevel level, BlockPos feet, Settings settings)
    {
        if (!withinWorldY(level, feet.getY())) return false;
        BlockState s0 = level.getBlockState(feet);
        BlockState s1 = level.getBlockState(feet.above());
        boolean inWater = s0.getFluidState().is(FluidTags.WATER);
        boolean headOk = s1.getFluidState().is(FluidTags.WATER) || isPassable(level, feet.above(), settings);
        if (!inWater || !headOk) return false;
        return isPassable(level, feet, settings) && isPassable(level, feet.above(), settings);
    }

    static boolean isPassable(ServerLevel level, BlockPos pos, Settings settings)
    {
        BlockState state = level.getBlockState(pos);
        if (settings.avoidLava() && state.getFluidState().is(FluidTags.LAVA)) return false;
        if (settings.avoidFire() && (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE))) return false;
        if (settings.avoidPowderSnow() && state.is(Blocks.POWDER_SNOW)) return false;
        if (settings.avoidCobwebs() && state.is(Blocks.COBWEB)) return false;

        // Doors and fence gates can be opened.
        if (state.getBlock() instanceof DoorBlock && settings.allowOpenDoors()) return true;
        if (state.getBlock() instanceof FenceGateBlock && settings.allowOpenFenceGates()) return true;

        return state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isLiquid(BlockState state)
    {
        return !state.getFluidState().isEmpty();
    }

    private static boolean isIce(BlockState state)
    {
        return state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE) || state.is(Blocks.FROSTED_ICE);
    }

    /**
     * Checks if position is at the water surface (bot can breathe).
     * Surface = feet in water but the block above the player's head is NOT water.
     */
    private static boolean isWaterSurface(ServerLevel level, BlockPos feet)
    {
        BlockState aboveHead = level.getBlockState(feet.above(2));
        return !aboveHead.getFluidState().is(FluidTags.WATER);
    }

    // --- Path reconstruction returning positions + move types ---
    private static PathResult reconstructPath(Node goal, Map<Long, Node> best)
    {
        List<BlockPos> revPos = new ArrayList<>();
        List<MoveType> revMoves = new ArrayList<>();
        Node cur = goal;
        int guard = 0;
        while (cur != null && guard++ < 500_000)
        {
            revPos.add(new BlockPos(cur.x, cur.y, cur.z));
            revMoves.add(cur.moveType);
            if (cur.parent == 0L) break;
            cur = best.get(cur.parent);
        }

        List<BlockPos> outPos = new ArrayList<>(revPos.size());
        List<MoveType> outMoves = new ArrayList<>(revMoves.size());
        for (int i = revPos.size() - 1; i >= 0; i--)
        {
            outPos.add(revPos.get(i));
            outMoves.add(revMoves.get(i));
        }
        return new PathResult(outPos, outMoves);
    }

    // ====== Block search utilities (for mining feature) ======

    /**
     * Searches for the nearest instance of any of the given blocks within a radius.
     * Searches in expanding shells for best average-case performance.
     */
    public static BlockPos findNearestBlock(ServerLevel level, BlockPos center, List<Block> targets, int radius)
    {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int r = 0; r <= radius; r++)
        {
            for (int ddx = -r; ddx <= r; ddx++)
            {
                for (int ddz = -r; ddz <= r; ddz++)
                {
                    if (Math.abs(ddx) != r && Math.abs(ddz) != r) continue;

                    int x = center.getX() + ddx;
                    int z = center.getZ() + ddz;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;

                    for (int y = level.getMinY(); y <= level.getMaxY(); y++)
                    {
                        mutable.set(x, y, z);
                        BlockState state = level.getBlockState(mutable);
                        for (Block target : targets)
                        {
                            if (state.is(target))
                            {
                                double dist = center.distSqr(mutable);
                                if (dist < nearestDist)
                                {
                                    nearestDist = dist;
                                    nearest = mutable.immutable();
                                }
                            }
                        }
                    }
                }
            }
            if (nearest != null) return nearest;
        }
        return nearest;
    }
}
