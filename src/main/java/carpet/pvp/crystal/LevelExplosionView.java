package carpet.pvp.crystal;

import carpet.pvp.sim.ExplosionView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The little of a real level the crystal model needs: what blocks an explosion ray, what a crystal can
 * stand on and what is empty. Every question is answered by the level the bot is standing in, which is
 * the world a player would be reading the same way.
 *
 * <p>The rays of {@link carpet.pvp.sim.SeenPercent} ask about many blocks, so the position is reused
 * rather than allocated per cell. One view belongs to one bot and is only read from the server thread,
 * where the search runs.</p>
 */
public final class LevelExplosionView implements ExplosionView
{
    private final Level level;
    private final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();

    public LevelExplosionView(Level level)
    {
        this.level = level;
    }

    /**
     * A block with a collision shape, which is what the rays of ServerExplosion.getSeenPercent are
     * clipped against: ClipContext.Block.COLLIDER. A slab is a block as far as a blast is concerned.
     */
    @Override
    public boolean blocksExplosion(int x, int y, int z)
    {
        cell.set(x, y, z);
        return !level.getBlockState(cell).getCollisionShape(level, cell).isEmpty();
    }

    /** Obsidian and bedrock, the two blocks EndCrystalItem.useOn accepts. */
    @Override
    public boolean isCrystalBase(int x, int y, int z)
    {
        BlockState state = state(x, y, z);
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    /** Mirrors BlockState.isAir, which is the test Level.isEmptyBlock makes for the cell above a base. */
    @Override
    public boolean isAir(int x, int y, int z)
    {
        return state(x, y, z).isAir();
    }

    private BlockState state(int x, int y, int z)
    {
        cell.set(x, y, z);
        return level.getBlockState(cell);
    }
}
