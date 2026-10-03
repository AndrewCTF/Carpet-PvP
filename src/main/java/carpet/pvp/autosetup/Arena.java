package carpet.pvp.autosetup;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The patch of world a fight is fought on: a floor and whatever the mode wants around it, built
 * next to the player. Every block it writes over is written down in an {@link ArenaBlocks}, so that
 * stopping the session puts the world back exactly as it was.
 *
 * <p>What is built is what the mode needs to fight in: a flat fenced floor for sword and SMP, open
 * sky over pillars to come down from for the mace, and an obsidian floor with a few holes in it for
 * the crystal. A block holding a block entity is never written over, so that a chest in the way
 * keeps its contents.</p>
 */
public final class Arena
{
    /** Half the width of the floor, so that the floor is seventeen blocks across. */
    public static final int RADIUS = 8;
    /** How far to the east of the player the arena is built. */
    private static final int DISTANCE = 20;
    /** How far from the centre the two fighters stand, six blocks apart, looking at each other. */
    private static final int OPPONENT_OFFSET = 3;
    /** Yaw is measured so that 270 looks along +x, at the opponent, and 90 along -x. */
    private static final float LOOK_EAST = 270.0F;
    private static final float LOOK_WEST = 90.0F;
    /** The mace pillars: two blocks across, at the corners, each one taller than the last. */
    private static final int[][] PILLARS = {{-5, -5, 4}, {5, -5, 6}, {5, 5, 8}, {-5, 5, 10}};
    /** The holes in the crystal floor, in blocks from the centre. */
    private static final int[][] HOLES = {{-3, -3}, {2, -4}, {4, 3}, {-4, 2}, {0, 5}};

    private final ResourceKey<Level> dimension;
    private final BlockPos centre;
    private final ArenaBlocks blocks;

    private Arena(ResourceKey<Level> dimension, BlockPos centre, ArenaBlocks blocks)
    {
        this.dimension = dimension;
        this.centre = centre;
        this.blocks = blocks;
    }

    /**
     * Builds the arena of a mode where the player is standing, writing down what it replaces.
     *
     * @param near   where the player is; the arena is built to the east of it
     * @param blocks the list the new blocks are remembered in, so that the session can undo them
     */
    public static Arena build(ServerLevel level, AutoMode mode, BlockPos near, ArenaBlocks blocks)
    {
        int x = near.getX() + DISTANCE;
        int z = near.getZ();
        // The floor goes into the top layer of the ground rather than over it: a floor laid on top
        // would take the light off the grass below it, which then dies and turns to dirt long after
        // the session is over.
        int floor = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        Arena arena = new Arena(level.dimension(), new BlockPos(x, floor, z), blocks);
        arena.write(level, mode);
        return arena;
    }

    /** Puts this arena's blocks back the way they were, where it stands. */
    public void restore(MinecraftServer server)
    {
        restore(server, dimension.identifier().toString(), blocks);
    }

    /**
     * Puts the blocks of a saved arena back the way they were.
     *
     * @param dimension where the arena was built, which is not where the player may be now
     */
    public static void restore(MinecraftServer server, String dimension, ArenaBlocks blocks)
    {
        if (blocks == null || blocks.isEmpty()) return;
        ServerLevel level = level(server, dimension);
        if (level == null) return;
        for (ArenaBlocks.Entry entry : blocks.entries())
        {
            BlockState was = Block.stateById(entry.state());
            level.setBlock(new BlockPos(entry.x(), entry.y(), entry.z()), was, Block.UPDATE_ALL);
        }
        blocks.clear();
    }

    /** The level a saved arena stands in, or null when this server has no such dimension. */
    public static ServerLevel level(MinecraftServer server, String dimension)
    {
        if (dimension == null || dimension.isBlank()) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
    }

    /** Where the player fights from, looking east at the bot. */
    public BlockPos playerSpawn()
    {
        return centre.above().offset(-OPPONENT_OFFSET, 0, 0);
    }

    /** Where the bot fights from, looking west at the player. */
    public BlockPos botSpawn()
    {
        return centre.above().offset(OPPONENT_OFFSET, 0, 0);
    }

    public float playerYaw()
    {
        return LOOK_EAST;
    }

    public float botYaw()
    {
        return LOOK_WEST;
    }

    public BlockPos centre()
    {
        return centre;
    }

    public ResourceKey<Level> dimension()
    {
        return dimension;
    }

    /** What the arena wrote over, which is what stopping the session has to put back. */
    public ArenaBlocks blocks()
    {
        return blocks;
    }

    private void write(ServerLevel level, AutoMode mode)
    {
        BlockState floorState = mode == AutoMode.CRYSTAL ? Blocks.OBSIDIAN.defaultBlockState()
                : Blocks.SMOOTH_STONE.defaultBlockState();
        for (int x = -RADIUS; x <= RADIUS; x++)
        {
            for (int z = -RADIUS; z <= RADIUS; z++)
            {
                if (!isHole(mode, x, z)) put(level, centre.offset(x, 0, z), floorState);
            }
        }
        switch (mode)
        {
            case MACE -> pillars(level);
            case CRYSTAL -> rim(level, Blocks.OBSIDIAN.defaultBlockState());
            default -> rim(level, Blocks.OAK_FENCE.defaultBlockState());
        }
    }

    /** The fence around a sword or SMP fight, or the obsidian wall of a crystal one. */
    private void rim(ServerLevel level, BlockState state)
    {
        for (int x = -RADIUS; x <= RADIUS; x++)
        {
            for (int z = -RADIUS; z <= RADIUS; z++)
            {
                if (Math.abs(x) == RADIUS || Math.abs(z) == RADIUS)
                {
                    put(level, centre.offset(x, 1, z), state);
                }
            }
        }
    }

    /** Open sky and somewhere to fall from: four pillars, each one higher than the last. */
    private void pillars(ServerLevel level)
    {
        for (int[] pillar : PILLARS)
        {
            for (int x = pillar[0]; x < pillar[0] + 2; x++)
            {
                for (int z = pillar[1]; z < pillar[1] + 2; z++)
                {
                    for (int y = 1; y <= pillar[2]; y++)
                    {
                        put(level, centre.offset(x, y, z), Blocks.STONE_BRICKS.defaultBlockState());
                    }
                }
            }
        }
    }

    /** Only the crystal floor has holes in it; a hole is left as the world had it. */
    private static boolean isHole(AutoMode mode, int x, int z)
    {
        if (mode != AutoMode.CRYSTAL) return false;
        for (int[] hole : HOLES)
        {
            if (hole[0] == x && hole[1] == z) return true;
        }
        return false;
    }

    /** Writes a block, remembering what was there, unless there is nothing to remember. */
    private void put(ServerLevel level, BlockPos pos, BlockState state)
    {
        BlockState before = level.getBlockState(pos);
        if (before == state || before.hasBlockEntity()) return;
        blocks.add(pos.getX(), pos.getY(), pos.getZ(), Block.getId(before));
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }
}
