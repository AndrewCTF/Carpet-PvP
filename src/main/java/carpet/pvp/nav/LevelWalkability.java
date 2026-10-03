package carpet.pvp.nav;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The world as navigation sees it, and the only place navigation reads blocks: what a player can stand on, walk
 * through and climb, and how high off the floor its feet are.
 *
 * <p>A position is a foot position: {@code y} is the block the feet are in, the head is at {@code y + 1} and the
 * floor is at {@code y - 1}, as {@link Walkability} says. Where the block underfoot does not fill its own cell -
 * a slab, a stair, a carpet, a snow layer, farmland, a path block - the feet sit part way up the foot block
 * instead of at its floor, and {@link #surfaceY} reports where. Every height the search reasons about is a real
 * height, so the bot steps onto a half slab by walking and onto a full block by jumping.
 *
 * <p>Heights come from the block's collision shape, not from a list of block types, so a modded slab is a slab
 * too. A shape is kept as the lowest and highest sixteenths it occupies, which is exact for what a player stands
 * on: a bottom slab is 0 to 8, a carpet 0 to 1, one snow layer 0 to 2, farmland and a path block 0 to 15.
 *
 * <p>Every block is read once per tick and answered from the cache after that, since a search looks at the same
 * block from several directions and the smoothing, steering and flow field code look at it again. The cache
 * drops itself when the world's game time moves on, because blocks change.
 */
public final class LevelWalkability implements Walkability
{
    /** Height of a standing player, which is what has to fit under a ceiling. */
    public static final double BODY_HEIGHT = 1.8D;
    /** A rise of at most this much is walked over, being the step a player takes without jumping. */
    public static final double STEP_HEIGHT = 0.6D;
    /** How far above the feet a support may sit and still count as a step up. */
    public static final double MAX_STEP_UP = 1.05D;
    /** Returned by {@link #standYNear} for a column with nothing to stand on within reach. */
    public static final int NO_STAND = Integer.MIN_VALUE;

    private static final int TOP_SHIFT = 5;
    private static final int HAZARD_BIT = 1 << 10;
    private static final int CLIMB_BIT = 1 << 11;
    private static final int WATER_BIT = 1 << 12;
    private static final int ICE_BIT = 1 << 13;
    private static final int SOUL_SAND_BIT = 1 << 14;
    private static final int DOOR_BIT = 1 << 15;
    /** Nothing to collide with in this cell, which is not the same as a cell that happens to be free of feet. */
    private static final int OPEN_BIT = 1 << 16;
    /** A height in sixteenths of a block; 16 is a whole cell. */
    private static final int FULL = 16;
    /** What an out-of-world or unloaded block reads as: a cell filled from top to bottom. */
    private static final int UNREADABLE = FULL | FULL << TOP_SHIFT;

    private final ServerLevel level;
    private final Long2IntMap cache = new Long2IntOpenHashMap();
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private long cachedForTime = Long.MIN_VALUE;
    private boolean avoidLava = true;
    private boolean avoidFire = true;
    private boolean avoidPowderSnow = true;
    private boolean avoidCobwebs = true;

    public LevelWalkability(ServerLevel level)
    {
        this.level = level;
        cache.defaultReturnValue(-1);
    }

    /**
     * Records which hazards count as impassable, from the settings the bot is navigating with. Called once per
     * tick before anything is read, so a cached flag always matches the rules in force.
     */
    public void hazards(boolean avoidLava, boolean avoidFire, boolean avoidPowderSnow, boolean avoidCobwebs)
    {
        this.avoidLava = avoidLava;
        this.avoidFire = avoidFire;
        this.avoidPowderSnow = avoidPowderSnow;
        this.avoidCobwebs = avoidCobwebs;
    }

    public ServerLevel level()
    {
        return level;
    }

    @Override
    public boolean canStand(int x, int y, int z)
    {
        int here = code(x, y, z);
        int top = top(here);
        double surface;
        if (top > 0 && top < FULL)
        {
            // Standing on top of a block that fills only part of its own cell.
            surface = y + top / 16.0D;
            if (isHazard(here)) return false;
        }
        else
        {
            surface = y;
            int below = code(x, y - 1, z);
            // A floor has to reach the top of its cell, and a ladder or a vine is not one: you climb those,
            // you do not stand on them.
            if (top(below) != FULL || isHazard(below) || isClimbable(below)) return false;
        }
        return bodyFits(x, y, z, surface);
    }

    @Override
    public boolean canPass(int x, int y, int z)
    {
        return bodyFits(x, y, z, surfaceY(x, y, z));
    }

    @Override
    public boolean hazard(int x, int y, int z)
    {
        return isHazard(code(x, y, z));
    }

    /**
     * Where the feet are when standing at this position: the top of whatever is underfoot, which is the foot block
     * itself where that is a slab or a stair, and otherwise the block below it.
     */
    public double surfaceY(int x, int y, int z)
    {
        int top = top(code(x, y, z));
        return top > 0 && top < FULL ? y + top / 16.0D : y;
    }

    /**
     * True where a ladder, a vine or a piece of scaffolding is thin enough for a player to be inside its cell,
     * which is what climbing needs. Scaffolding that fills its cell is not thin: it is a floor like any other.
     */
    public boolean climbable(int x, int y, int z)
    {
        return isClimbable(code(x, y, z));
    }

    /**
     * The direction a player faces to hold a climbable here: into the wall a ladder is fixed to, or nothing at
     * all for a vine, which has no facing of its own.
     */
    public Direction climbFacing(int x, int y, int z)
    {
        BlockState state = state(x, y, z);
        return state.getBlock() instanceof LadderBlock ? state.getValue(LadderBlock.FACING).getOpposite() : null;
    }

    /**
     * The y of the nearest position in this column a player can stand on, looking a little above {@code nearY} and
     * then a long way below it, or {@link #NO_STAND} when there is none. Turns a position that was asked for into
     * one that can be reached.
     */
    public int standYNear(int x, int nearY, int z, int reach)
    {
        for (int up = 0; up <= 3; up++)
        {
            if (canStand(x, nearY + up, z)) return nearY + up;
        }
        for (int down = 1; down <= reach; down++)
        {
            if (canStand(x, nearY - down, z)) return nearY - down;
        }
        return NO_STAND;
    }

    /** True where a body standing here would be in water at its feet, which the water rules read. */
    public boolean inWater(int x, int y, int z)
    {
        return isWater(code(x, y, z));
    }

    /** True where the feet or the head of a body standing here would be in water. */
    public boolean bodyInWater(int x, int y, int z)
    {
        int here = code(x, y, z);
        if (isWater(here)) return true;
        double surface = surfaceY(x, y, z);
        return isWater(code(x, Mth.floor(surface + BODY_HEIGHT - 1.0D / 16.0D), z));
    }

    /** True where the feet are in water but the head is not, which is where a body floats. */
    public boolean waterSurface(int x, int y, int z)
    {
        return isWater(code(x, y, z)) && !isWater(code(x, y + 2, z));
    }

    /** True where a block can be mined out of the way, which breaking through and descending both need. */
    public boolean breakable(int x, int y, int z)
    {
        if (!loaded(x, y, z)) return false;
        BlockState state = state(x, y, z);
        if (state.isAir()) return true;
        if (!state.getFluidState().isEmpty()) return false;
        return state.getDestroySpeed(level, scratch.set(x, y, z)) >= 0.0F;
    }

    /** What mining this block is worth in path cost, or a large number for a block that cannot be mined. */
    public float breakCost(int x, int y, int z, float base)
    {
        if (!loaded(x, y, z)) return Float.MAX_VALUE;
        BlockState state = state(x, y, z);
        if (state.isAir()) return 0.0F;
        float hardness = state.getDestroySpeed(level, scratch.set(x, y, z));
        return hardness < 0.0F ? Float.MAX_VALUE : base + hardness * 2.0F;
    }

    /** True on ice, which the follower slows down on so that it does not slide past a waypoint. */
    public boolean onIce(int x, int y, int z)
    {
        return (code(x, y - 1, z) & ICE_BIT) != 0;
    }

    /** True on soul sand, which the search charges for when the rules say to. */
    public boolean onSoulSand(int x, int y, int z)
    {
        return (code(x, y - 1, z) & SOUL_SAND_BIT) != 0;
    }

    /** True where a door or a fence gate stands, which the follower opens rather than walks into. */
    public boolean isDoorOrGate(int x, int y, int z)
    {
        return (code(x, y, z) & DOOR_BIT) != 0;
    }

    /**
     * The block state here, for the parts of navigation that act on the world rather than read it. The position
     * object belongs to this view, so it must not be kept past the next call.
     */
    public BlockState state(int x, int y, int z)
    {
        scratch.set(x, y, z);
        return level.getBlockState(scratch);
    }

    // --- internals ---

    /**
     * Whether a body with its feet at {@code surface} fits in this column: no cell it covers may hold anything in
     * the player's height. A cell counts as clear when its collision shape stops below the feet or starts above
     * the head, which is what a slab underfoot and a low ceiling are.
     */
    private boolean bodyFits(int x, int y, int z, double surface)
    {
        double head = surface + BODY_HEIGHT;
        int lowest = Mth.floor(surface);
        int highest = Mth.floor(head - 1.0D / 16.0D);
        for (int b = lowest; b <= highest; b++)
        {
            int code = code(x, b, z);
            if (isOpen(code) || isClimbable(code)) continue;
            if (b + top(code) / 16.0D <= surface + 1.0E-6D) continue;
            if (b + bottom(code) / 16.0D >= head - 1.0E-6D) continue;
            return false;
        }
        return true;
    }

    private boolean loaded(int x, int y, int z)
    {
        return y >= level.getMinY() && y <= level.getMaxY() && level.hasChunk(x >> 4, z >> 4);
    }

    private int code(int x, int y, int z)
    {
        long time = level.getGameTime();
        if (time != cachedForTime)
        {
            cache.clear();
            cachedForTime = time;
        }
        long key = BlockPos.asLong(x, y, z);
        int packed = cache.get(key);
        if (packed >= 0) return packed - 1;
        int code = read(x, y, z);
        cache.put(key, code + 1);
        return code;
    }

    private int read(int x, int y, int z)
    {
        if (!loaded(x, y, z))
        {
            return UNREADABLE;
        }
        BlockState state = state(x, y, z);
        VoxelShape shape = state.getCollisionShape(level, scratch.set(x, y, z));
        // A vine has nothing to collide with at all and a ladder a bar along the wall; either way a player fits
        // beside it, which is what makes it something to climb rather than a floor.
        boolean thin = shape.isEmpty() || !fillsItsCell(shape.bounds());
        int code;
        if (shape.isEmpty())
        {
            code = OPEN_BIT;
        }
        else
        {
            code = sixteenths(shape.bounds().minY) | sixteenths(shape.bounds().maxY) << TOP_SHIFT;
        }
        if (state.is(BlockTags.CLIMBABLE) && thin)
        {
            code |= CLIMB_BIT;
        }
        if (hazardOf(state)) code |= HAZARD_BIT;
        if (state.getFluidState().is(FluidTags.WATER)) code |= WATER_BIT;
        if (isIce(state)) code |= ICE_BIT;
        if (state.is(Blocks.SOUL_SAND)) code |= SOUL_SAND_BIT;
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) code |= DOOR_BIT;
        return code;
    }

    private boolean hazardOf(BlockState state)
    {
        if (avoidLava && state.getFluidState().is(FluidTags.LAVA)) return true;
        if (avoidFire && (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE))) return true;
        if (avoidPowderSnow && state.is(Blocks.POWDER_SNOW)) return true;
        return avoidCobwebs && state.is(Blocks.COBWEB);
    }

    /**
     * True when the shape fills its whole cell, which is what makes a block a floor rather than something a player
     * fits beside. A ladder and a vine both stand the height of a cell but are thin sideways; scaffolding fills its
     * cell and is walked on like any other block.
     */
    private static boolean fillsItsCell(AABB bounds)
    {
        return bounds.minX <= 0.0D && bounds.maxX >= 1.0D && bounds.minZ <= 0.0D && bounds.maxZ >= 1.0D;
    }

    private static boolean isIce(BlockState state)
    {
        return state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.FROSTED_ICE);
    }

    private static int sixteenths(double value)
    {
        return Mth.clamp((int) Math.round(value * 16.0D), 0, FULL);
    }

    private static int top(int code)
    {
        return code >>> TOP_SHIFT & 31;
    }

    private static int bottom(int code)
    {
        return code & 31;
    }

    private static boolean isHazard(int code)
    {
        return (code & HAZARD_BIT) != 0;
    }

    private static boolean isClimbable(int code)
    {
        return (code & CLIMB_BIT) != 0;
    }

    private static boolean isWater(int code)
    {
        return (code & WATER_BIT) != 0;
    }

    private static boolean isOpen(int code)
    {
        return (code & OPEN_BIT) != 0;
    }
}
