package carpet.pvp.sim;

/**
 * The little of the world the crystal model needs. The caller reads the real level here, tests back an
 * in-memory grid.
 */
public interface ExplosionView
{
    /**
     * True when the block has a non empty collision shape, which is what the rays of
     * ServerExplosion.getSeenPercent are clipped against (ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE).
     * Partial shapes are reported as blocking; the caller knows the block is not a full cube.
     */
    boolean blocksExplosion(int x, int y, int z);

    /** True for obsidian and bedrock, the two blocks EndCrystalItem.useOn accepts. */
    boolean isCrystalBase(int x, int y, int z);

    /** Mirrors BlockState.isAir, which is the test Level.isEmptyBlock makes for the cell above the base. */
    boolean isAir(int x, int y, int z);
}
