package carpet.pvp.ranged;

import carpet.pvp.sim.ExplosionView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The world as the explosion models read it: which blocks stop a blast ray, which blocks are a crystal base and
 * which cells are empty. Nothing is cached: the plan that asks these questions runs a few times a second at
 * most, and a stale block would be worse than a second read.
 */
public final class LevelExplosionView implements ExplosionView
{
    private final ServerLevel level;
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    public LevelExplosionView(ServerLevel level)
    {
        this.level = level;
    }

    public ServerLevel level()
    {
        return level;
    }

    @Override
    public boolean blocksExplosion(int x, int y, int z)
    {
        // ClipContext.Block.COLLIDER, which is the collision shape, and a partial shape counts as blocking.
        return !state(x, y, z).getCollisionShape(level, scratch.set(x, y, z)).isEmpty();
    }

    @Override
    public boolean isCrystalBase(int x, int y, int z)
    {
        BlockState state = state(x, y, z);
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    @Override
    public boolean isAir(int x, int y, int z)
    {
        return state(x, y, z).isAir();
    }

    /** True where a rail would have something solid to be fixed to, which is what BaseRailBlock.canSurvive wants. */
    public boolean supportsRail(int x, int y, int z)
    {
        return state(x, y, z).isCollisionShapeFullBlock(level, scratch.set(x, y, z));
    }

    private BlockState state(int x, int y, int z)
    {
        return level.getBlockState(scratch.set(x, y, z));
    }
}
