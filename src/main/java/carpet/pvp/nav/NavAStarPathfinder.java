package carpet.pvp.nav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import carpet.pvp.nav.BudgetedSearch.Budget;
import carpet.pvp.nav.BudgetedSearch.Status;

/**
 * Bounded voxel A* over foot positions, built so that one search can be spread over as many ticks as it takes.
 *
 * <p>A {@link Search} is started with {@link Search#begin} and then fed an expansion budget once per tick until
 * it reports itself {@link Search#done() done}, so no single tick pays for a whole path however long that path
 * is. While a search runs, the caller keeps doing what it was doing before: walking the path it already had, or
 * steering straight at the goal.
 *
 * <p>Moves:
 * <ul>
 *   <li>Walking, level or over a step, including diagonals that do not clip a corner</li>
 *   <li>Jumping a block up, and falling off one</li>
 *   <li>Climbing a ladder, a vine or a scaffolding up or down, where nothing else fits</li>
 *   <li>Parkour across a gap, plain or needing a run-up</li>
 *   <li>Pillar (place a block underfoot to go up), break-through (mine into a wall), descend-mine</li>
 *   <li>Swimming, on the surface by default or through the water when asked</li>
 * </ul>
 *
 * <p>Costs follow the usual conventions: a block walked is 1.0 and a diagonal is 1.414, sprinting is cheaper, a
 * jump and a parkour cost extra, mining and placing cost a lot, a fall past the safe height costs more the
 * further it goes, and cells near hostile mobs cost more when mob avoidance is on. Steps are measured between
 * real surfaces, so a half slab costs a walk and a full block costs a jump.
 *
 * <p>Nothing here reads a server setting or a block directly: the rules arrive in {@link Settings} and the world
 * through a {@link LevelWalkability}.
 */
public final class NavAStarPathfinder
{
    private static final int[] PARKOUR_DX = {1, -1, 0, 0};
    private static final int[] PARKOUR_DZ = {0, 0, 1, -1};
    private static final int NO_NODE = Integer.MIN_VALUE;

    public enum Traversal
    {
        LAND,
        WATER,
        AMPHIBIOUS
    }

    /**
     * Flags attached to each path node indicating the movement used to reach it, which the navigation executor
     * uses to perform the right action. The order matters: {@link #WALK} is first, so the ordinals double as the
     * move codes {@link PathSmoother} works with, where anything but a walk pins its waypoint.
     */
    public enum MoveType
    {
        WALK,
        JUMP,
        FALL,
        PARKOUR,
        PARKOUR_RUNUP,
        CLIMB_UP,
        CLIMB_DOWN,
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
            int parkourRunUpLength,
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
                    4,          // maxParkourLength, a four-block gap
                    3,          // parkourRunUpLength, the first gap too wide for a standing jump
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

    /** One node of a search: a foot position, what it cost to get there, and what it came from. */
    public static final class Node
    {
        public final long key;
        public final int x;
        public final int y;
        public final int z;
        public final float g;
        public final float f;
        public final MoveType moveType;
        private final Node parent;

        Node(long key, int x, int y, int z, Node parent, float g, float f, MoveType moveType)
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
     * One resumable search. Call {@link #begin}, then {@link #run} once per tick with that tick's expansion
     * budget until {@link #done()}, then read {@link #result()}.
     */
    public static final class Search implements BudgetedSearch.Step
    {
        private final Map<Long, Node> best = new HashMap<>();
        private final Set<Long> closed = new HashSet<>();
        private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        private final BudgetedSearch runner = new BudgetedSearch();

        private LevelWalkability view;
        private Settings settings;
        private Traversal traversal;
        private Set<Long> mobDangerZone = Set.of();
        private BlockPos start;
        private BlockPos goal;
        private long goalKey;
        private PathResult result;
        private boolean started;
        private boolean done;
        private int expanded;

        // Where the neighbour being considered ends up, so the hot loop does not have to allocate for it.
        private int nextY;
        private MoveType nextMove;

        /**
         * Starts a search from {@code start} to {@code goal}. Either may be anywhere: the nearest position a player
         * can occupy is found for both. Returns false when there is nothing to search for, which leaves the search
         * done with no result.
         */
        public boolean begin(LevelWalkability view, BlockPos start, BlockPos goal, Traversal traversal,
                Settings settings)
        {
            this.view = view;
            this.traversal = traversal;
            this.settings = settings;
            this.mobDangerZone = settings.avoidMobs() ? buildMobDangerMap(view, start, goal, settings) : Set.of();
            best.clear();
            closed.clear();
            open.clear();
            expanded = 0;
            result = null;
            started = false;
            done = false;

            BlockPos from = footOf(view, start, traversal, settings);
            BlockPos to = footOf(view, goal, traversal, settings);
            if (from == null || to == null)
            {
                done = true;
                return false;
            }
            this.start = from;
            this.goal = to;
            this.goalKey = to.asLong();
            Node startNode = new Node(from.asLong(), from.getX(), from.getY(), from.getZ(), null, 0.0F,
                    heuristic(from.getX(), from.getY(), from.getZ(), to, settings), MoveType.WALK);
            open.add(startNode);
            best.put(startNode.key, startNode);
            started = true;
            return true;
        }

        /** Runs the search with this search's own runner, for a caller that does not keep one. */
        public Status run(int maxExpansions)
        {
            return runner.run(this, maxExpansions);
        }

        @Override
        public boolean expand(Budget budget)
        {
            if (done || !started) return true;
            while (budget.left() && !open.isEmpty())
            {
                budget.spend();
                expanded++;
                Node cur = open.poll();
                if (cur == null) break;
                if (!best.containsKey(cur.key) || closed.contains(cur.key)) continue;

                if (cur.key == goalKey)
                {
                    result = reconstructPath(cur);
                    done = true;
                    return true;
                }
                closed.add(cur.key);
                expandNode(cur);

                if (expanded > settings.maxExpanded() || open.size() > settings.maxQueued())
                {
                    result = bestApproach();
                    done = true;
                    return true;
                }
            }
            if (open.isEmpty())
            {
                done = true;
            }
            return done;
        }

        /** True once the search has finished, whether it found a path or gave up. */
        public boolean done()
        {
            return done;
        }

        /** True while the search is unfinished, which is when the caller needs something else to do. */
        public boolean searching()
        {
            return started && !done;
        }

        /** The path found, or the closest approach to it, once {@link #done()}; null when there is none. */
        public PathResult result()
        {
            return result;
        }

        /** Node expansions this search has used in total, however many ticks they were spread over. */
        public int expansions()
        {
            return expanded;
        }

        /** Throws the search away, for a bot whose goal has changed or who has stopped navigating. */
        public void cancel()
        {
            best.clear();
            closed.clear();
            open.clear();
            result = null;
            started = false;
            done = true;
        }

        // --- expansion ---

        private void expandNode(Node cur)
        {
            boolean land = traversal != Traversal.WATER;
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    if (dx == 0 && dz == 0) continue;
                    boolean diagonal = dx != 0 && dz != 0;
                    if (!settings.allowDiagonal() && diagonal) continue;

                    int nx = cur.x + dx;
                    int nz = cur.z + dz;
                    if (!withinBounds(nx, cur.y, nz)) continue;

                    if (land && landNeighbour(cur, nx, nz))
                    {
                        if (diagonal && !diagonalClear(cur, nx, nz)) continue;
                        offer(cur, nx, nextY, nz, nextMove);
                    }
                    else
                    {
                        int ny = swimY(nx, cur.y, nz);
                        if (ny == NO_NODE) continue;
                        if (diagonal && !diagonalClear(cur, nx, nz)) continue;
                        offer(cur, nx, ny, nz, MoveType.SWIM);
                    }
                }
            }

            if (traversal == Traversal.WATER) return;

            expandClimb(cur);
            expandClimbOff(cur);

            if (settings.allowParkour() && settings.maxParkourLength() >= 2)
            {
                expandParkour(cur);
            }
            if (settings.allowPillar())
            {
                expandPillar(cur);
            }
            if (settings.allowDescendMine())
            {
                expandDescendMine(cur);
            }
        }

        /**
         * The position to move to in the next column and the move that gets there: a walk when the two surfaces
         * are within a step of each other, a jump for a block up, a fall for a drop. A walkable step beats a jump
         * and a jump beats a drop, whichever order the candidates come in.
         */
        private boolean landNeighbour(Node cur, int toX, int toZ)
        {
            double from = view.surfaceY(cur.x, cur.y, cur.z);
            int bestRank = 3;
            int bestY = 0;
            MoveType bestMove = MoveType.WALK;
            for (int dy = settings.maxStepUp(); dy >= -settings.maxFall(); dy--)
            {
                int ny = cur.y + dy;
                if (!withinBounds(toX, ny, toZ)) continue;
                if (!view.canStand(toX, ny, toZ)) continue;
                double delta = view.surfaceY(toX, ny, toZ) - from;
                int rank;
                if (delta > LevelWalkability.STEP_HEIGHT)
                {
                    if (delta > LevelWalkability.MAX_STEP_UP) continue;
                    rank = 1;
                }
                else if (delta < -LevelWalkability.STEP_HEIGHT)
                {
                    if (-delta > settings.maxFall() && !swimmable(toX, ny, toZ)) continue;
                    rank = 2;
                }
                else
                {
                    rank = 0;
                }
                if (rank < bestRank)
                {
                    bestRank = rank;
                    bestY = ny;
                    bestMove = rank == 0 ? MoveType.WALK : rank == 1 ? MoveType.JUMP : MoveType.FALL;
                    if (rank == 0) break;
                }
            }
            if (bestRank == 3)
            {
                return breakThrough(cur, toX, toZ);
            }
            nextY = bestY;
            nextMove = bestMove;
            return true;
        }

        /** A diagonal move has to squeeze between the two blocks it passes, whatever is in either of them. */
        private boolean diagonalClear(Node cur, int nx, int nz)
        {
            return view.canPass(nx, cur.y, cur.z) && view.canPass(cur.x, cur.y, nz);
        }

        /** Mining into a column that is in the way, which only a bot allowed to break blocks may do. */
        private boolean breakThrough(Node cur, int toX, int toZ)
        {
            if (!settings.allowBreakThrough()) return false;
            int ny = cur.y;
            if (!withinBounds(toX, ny, toZ) || !view.canStand(toX, ny, toZ)) return false;
            if (view.canPass(toX, ny, toZ) && view.canPass(toX, ny + 1, toZ)) return false;
            if (!view.breakable(toX, ny, toZ) || !view.breakable(toX, ny + 1, toZ)) return false;
            nextY = ny;
            nextMove = MoveType.BREAK_THROUGH;
            return true;
        }

        private void expandClimb(Node cur)
        {
            if (view.canPass(cur.x, cur.y + 1, cur.z)
                    && (view.climbable(cur.x, cur.y, cur.z) || view.climbable(cur.x, cur.y + 1, cur.z)))
            {
                offer(cur, cur.x, cur.y + 1, cur.z, MoveType.CLIMB_UP);
            }
            if (view.canPass(cur.x, cur.y - 1, cur.z) && view.climbable(cur.x, cur.y - 1, cur.z))
            {
                offer(cur, cur.x, cur.y - 1, cur.z, MoveType.CLIMB_DOWN);
            }
        }

        /**
         * Stepping off an edge into a ladder or a vine. The column has no floor in it, so there is nothing to walk
         * on to and this is the only way in - and the only way out of a platform that ends at a vine.
         */
        private void expandClimbOff(Node cur)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cur.x + dx;
                    int nz = cur.z + dz;
                    if (!withinBounds(nx, cur.y, nz)) continue;
                    if (!view.climbable(nx, cur.y, nz)) continue;
                    if (view.canStand(nx, cur.y, nz)) continue;
                    if (!view.canPass(nx, cur.y, nz)) continue;
                    offer(cur, nx, cur.y, nz, MoveType.CLIMB_DOWN);
                }
            }
        }

        private void expandParkour(Node cur)
        {
            for (int dir = 0; dir < PARKOUR_DX.length; dir++)
            {
                int dx = PARKOUR_DX[dir];
                int dz = PARKOUR_DZ[dir];
                for (int len = 2; len <= settings.maxParkourLength(); len++)
                {
                    int nx = cur.x + dx * len;
                    int nz = cur.z + dz * len;
                    if (!withinBounds(nx, cur.y, nz)) continue;
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        int ny = cur.y + dy;
                        if (!view.canStand(nx, ny, nz)) continue;
                        if (!jumpArcClear(cur, nx, ny, nz)) continue;
                        // A gap too wide for a standing jump, or one that has to be climbed out of, is only taken
                        // with a run up; paying for the run up is what stops the search choosing it over a step.
                        boolean runUp = len >= settings.parkourRunUpLength() || dy != 0;
                        offer(cur, nx, ny, nz, runUp ? MoveType.PARKOUR_RUNUP : MoveType.PARKOUR);
                    }
                }
            }
        }

        /** Nothing may stand in the way between the take-off and the landing, and there has to be headroom. */
        private boolean jumpArcClear(Node cur, int toX, int toY, int toZ)
        {
            if (!view.canPass(cur.x, cur.y + 1, cur.z)) return false;
            double arc = Math.max(view.surfaceY(cur.x, cur.y, cur.z), view.surfaceY(toX, toY, toZ));
            int y = Mth.floor(arc);
            int dx = Integer.signum(toX - cur.x);
            int dz = Integer.signum(toZ - cur.z);
            int steps = Math.max(Math.abs(toX - cur.x), Math.abs(toZ - cur.z));
            for (int i = 1; i < steps; i++)
            {
                int mx = cur.x + dx * i;
                int mz = cur.z + dz * i;
                if (!withinBounds(mx, y, mz)) return false;
                if (!view.canPass(mx, y, mz) || !view.canPass(mx, y + 1, mz)) return false;
            }
            return true;
        }

        private void expandPillar(Node cur)
        {
            int ny = cur.y + 1;
            if (!withinBounds(cur.x, ny, cur.z)) return;
            if (!view.canPass(cur.x, ny, cur.z) || !view.canPass(cur.x, ny + 1, cur.z)) return;
            if (!view.canStand(cur.x, ny - 1, cur.z)) return;
            offer(cur, cur.x, ny, cur.z, MoveType.PILLAR);
        }

        private void expandDescendMine(Node cur)
        {
            int ny = cur.y - 1;
            if (!withinBounds(cur.x, ny, cur.z)) return;
            if (!view.canStand(cur.x, ny, cur.z)) return;
            if (!view.breakable(cur.x, ny, cur.z)) return;
            if (!view.canStand(cur.x, ny - 1, cur.z) || view.hazard(cur.x, ny - 1, cur.z)) return;
            offer(cur, cur.x, ny, cur.z, MoveType.DESCEND_MINE);
        }

        private void offer(Node cur, int nx, int ny, int nz, MoveType move)
        {
            long key = BlockPos.asLong(nx, ny, nz);
            if (closed.contains(key)) return;
            float g = cur.g + cost(view, cur.x, cur.y, cur.z, nx, ny, nz, move, mobDangerZone, settings);
            Node previous = best.get(key);
            if (previous != null && g >= previous.g) return;
            float f = g + heuristic(nx, ny, nz, goal, settings);
            Node node = new Node(key, nx, ny, nz, cur, g, f, move);
            best.put(key, node);
            open.add(node);
        }

        /** The nearest position in this column a player can swim to, or {@link #NO_NODE}. */
        private int swimY(int toX, int fromY, int toZ)
        {
            if (settings.allowSwimming())
            {
                for (int dy = -2; dy <= 2; dy++)
                {
                    if (swimmable(toX, fromY + dy, toZ)) return fromY + dy;
                }
            }
            else
            {
                // Floating: the highest water a body fits in, so the bot swims at the surface.
                for (int dy = 1; dy >= -1; dy--)
                {
                    if (swimmable(toX, fromY + dy, toZ) && view.waterSurface(toX, fromY + dy, toZ))
                    {
                        return fromY + dy;
                    }
                }
                for (int dy = 1; dy >= -1; dy--)
                {
                    if (swimmable(toX, fromY + dy, toZ)) return fromY + dy;
                }
            }
            return NO_NODE;
        }

        private boolean swimmable(int x, int y, int z)
        {
            return withinBounds(x, y, z) && view.inWater(x, y, z)
                    && view.canPass(x, y, z) && view.canPass(x, y + 1, z);
        }

        private boolean withinBounds(int x, int y, int z)
        {
            return NavAStarPathfinder.withinBounds(x, y, z, start, goal, settings);
        }

        /** The reached node closest to the goal, for a search that ran out of budget rather than out of world. */
        private PathResult bestApproach()
        {
            Node closest = null;
            float closestDistance = Float.MAX_VALUE;
            for (Long key : closed)
            {
                Node node = best.get(key);
                if (node == null) continue;
                float distance = heuristic(node.x, node.y, node.z, goal, settings);
                if (distance < closestDistance)
                {
                    closestDistance = distance;
                    closest = node;
                }
            }
            return closest == null ? null : reconstructPath(closest);
        }
    }

    // --- shared helpers ---

    /** The nearest position a player can occupy to {@code around}: a standable one on land, a swimmable one in water. */
    private static BlockPos footOf(LevelWalkability view, BlockPos around, Traversal traversal, Settings settings)
    {
        ServerLevel level = view.level();
        if (traversal == Traversal.WATER)
        {
            return waterNear(view, around);
        }
        int y = view.standYNear(around.getX(), around.getY(), around.getZ(), 8);
        if (y != LevelWalkability.NO_STAND) return new BlockPos(around.getX(), y, around.getZ());
        if (traversal != Traversal.WATER && view.canPass(around.getX(), around.getY(), around.getZ()))
        {
            // Nowhere to stand within reach, but a body fits here: a bot on a ladder or a vine starts or ends its
            // search on the climbable it is holding.
            return around.immutable();
        }
        if (traversal == Traversal.LAND) return null;
        return waterNear(view, around);
    }

    private static BlockPos waterNear(LevelWalkability view, BlockPos around)
    {
        for (int dy = 4; dy >= -4; dy--)
        {
            int y = around.getY() + dy;
            if (view.inWater(around.getX(), y, around.getZ()) && view.canPass(around.getX(), y, around.getZ()))
            {
                return new BlockPos(around.getX(), y, around.getZ());
            }
        }
        return null;
    }

    /** The path from the start node down to {@code node}, walking the parent links. */
    private static PathResult reconstructPath(Node node)
    {
        List<BlockPos> reversedPos = new ArrayList<>();
        List<MoveType> reversedMoves = new ArrayList<>();
        Node cur = node;
        int guard = 0;
        while (cur != null && guard++ < 500_000)
        {
            reversedPos.add(new BlockPos(cur.x, cur.y, cur.z));
            reversedMoves.add(cur.moveType);
            cur = cur.parent;
        }
        List<BlockPos> positions = new ArrayList<>(reversedPos.size());
        List<MoveType> moves = new ArrayList<>(reversedMoves.size());
        for (int i = reversedPos.size() - 1; i >= 0; i--)
        {
            positions.add(reversedPos.get(i));
            moves.add(reversedMoves.get(i));
        }
        return new PathResult(positions, moves);
    }

    private static float cost(LevelWalkability view, int fx, int fy, int fz, int nx, int ny, int nz, MoveType move,
            Set<Long> mobDangerZone, Settings settings)
    {
        int dx = Math.abs(nx - fx);
        int dz = Math.abs(nz - fz);
        double rise = view.surfaceY(nx, ny, nz) - view.surfaceY(fx, fy, fz);

        float cost;
        switch (move)
        {
            case PARKOUR:
            case PARKOUR_RUNUP:
                cost = Mth.sqrt((nx - fx) * (nx - fx) + (nz - fz) * (nz - fz)) + settings.jumpPenalty() * 2.0F;
                if (move == MoveType.PARKOUR_RUNUP) cost += 0.5F;
                break;
            case PILLAR:
                cost = settings.pillarCost();
                break;
            case BREAK_THROUGH:
                cost = settings.breakCostBase()
                        + view.breakCost(nx, ny, nz, settings.breakCostBase())
                        + view.breakCost(nx, ny + 1, nz, settings.breakCostBase());
                break;
            case DESCEND_MINE:
                cost = settings.descendMineCost() + view.breakCost(nx, ny, nz, settings.breakCostBase());
                break;
            default:
                cost = (dx != 0 && dz != 0) ? 1.4142F : 1.0F;
                break;
        }

        if (rise > 0.0D)
        {
            cost += (float) (settings.jumpPenalty() * Math.min(1.0D, rise));
        }
        else if (rise < 0.0D)
        {
            float fall = (float) -rise;
            if (fall > settings.maxFallNoWater() && !view.bodyInWater(nx, ny, nz))
            {
                cost += settings.fallDamagePenalty() * (fall - settings.maxFallNoWater());
            }
            cost += 0.1F * fall;
        }

        // Sprinting is the fast way round, so a level walk is cheaper at a sprint.
        if (settings.allowSprint() && move == MoveType.WALK && Math.abs(rise) < 1.0E-3D)
        {
            cost *= settings.sprintCostMultiplier();
        }

        if (settings.avoidSoulSand() && view.onSoulSand(nx, ny, nz))
        {
            cost *= 2.5F;
        }
        if (view.onIce(nx, ny, nz))
        {
            cost *= 1.3F;
        }
        if (view.isDoorOrGate(nx, ny, nz))
        {
            cost += 1.0F;
        }

        cost += mobOverlay(view, nx, ny, nz, mobDangerZone, settings);
        return cost;
    }

    private static float mobOverlay(LevelWalkability view, int x, int y, int z, Set<Long> mobDangerZone,
            Settings settings)
    {
        if (!settings.avoidMobs() || mobDangerZone.isEmpty()) return 0.0F;
        return mobDangerZone.contains(BlockPos.asLong(x, y, z)) ? settings.mobAvoidanceCost() : 0.0F;
    }

    private static float heuristic(int x, int y, int z, BlockPos goal, Settings settings)
    {
        float dx = x - goal.getX();
        float dy = y - goal.getY();
        float dz = z - goal.getZ();
        float distance = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        return settings.allowSprint() ? distance * settings.sprintCostMultiplier() : distance;
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

    private static Set<Long> buildMobDangerMap(LevelWalkability view, BlockPos start, BlockPos goal, Settings settings)
    {
        Set<Long> dangerZone = new HashSet<>();
        int radius = settings.mobAvoidanceRadius();
        int minX = Math.min(start.getX(), goal.getX()) - settings.maxRangeXZ();
        int maxX = Math.max(start.getX(), goal.getX()) + settings.maxRangeXZ();
        int minZ = Math.min(start.getZ(), goal.getZ()) - settings.maxRangeXZ();
        int maxZ = Math.max(start.getZ(), goal.getZ()) + settings.maxRangeXZ();
        ServerLevel level = view.level();
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

    /** How often a raw path is thinned out when only the turns matter. */
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

    /**
     * Searches for the nearest instance of any of the given blocks within a radius, in expanding shells.
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
