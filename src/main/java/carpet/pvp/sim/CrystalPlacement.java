package carpet.pvp.sim;

/**
 * Where a crystal or a respawn anchor may be put, and what its explosion does. Placement mirrors the three
 * conditions of EndCrystalItem.useOn; the anchor is a plain block the bot puts down and charges, so it only
 * needs a free cell, and it detonates with power 5 at the block centre as RespawnAnchorBlock.explode does.
 */
public final class CrystalPlacement
{
    /** Power EndCrystal.hurtServer detonates with. */
    public static final float CRYSTAL_POWER = 6.0f;
    /** Power RespawnAnchorBlock.explode detonates with. */
    public static final float ANCHOR_POWER = 5.0f;

    private CrystalPlacement()
    {
    }

    /** The centre of the crystal EndCrystalItem.useOn spawns for a click on the block at (x, y, z). */
    public static double[] crystalCentre(int x, int y, int z)
    {
        return new double[] {x + 0.5, y + 1.0, z + 0.5};
    }

    /** The centre of a respawn anchor at (x, y, z), Vec3.atCenterOf. */
    public static double[] anchorCentre(int x, int y, int z)
    {
        return new double[] {x + 0.5, y + 0.5, z + 0.5};
    }

    /** The two block cell a crystal placed on (x, y, z) occupies. */
    public static Box crystalCell(int x, int y, int z)
    {
        return Box.column(x, y + 1, z);
    }

    /** The cell a respawn anchor at (x, y, z) occupies. */
    public static Box anchorCell(int x, int y, int z)
    {
        return Box.block(x, y, z);
    }

    /** Mirrors EndCrystalItem.useOn: an obsidian or bedrock base, an air cell above it, and no entity in that cell's column. */
    public static boolean canPlaceCrystal(ExplosionView view, int x, int y, int z, Box... entities)
    {
        if (!view.isCrystalBase(x, y, z) || !view.isAir(x, y + 1, z))
        {
            return false;
        }
        return clear(crystalCell(x, y, z), entities);
    }

    /**
     * The same crystal, but only after the bot fills the empty base cell with obsidian. The cell has to be
     * air and the crystal cell above it has to be free.
     */
    public static boolean canBridgeCrystal(ExplosionView view, int x, int y, int z, Box... entities)
    {
        if (!view.isAir(x, y, z) || !view.isAir(x, y + 1, z))
        {
            return false;
        }
        return clear(crystalCell(x, y, z), entities);
    }

    /** A respawn anchor is an ordinary block, so it needs an empty cell and nothing standing in it. */
    public static boolean canPlaceAnchor(ExplosionView view, int x, int y, int z, Box... entities)
    {
        return view.isAir(x, y, z) && clear(anchorCell(x, y, z), entities);
    }

    private static boolean clear(Box cell, Box[] entities)
    {
        for (Box entity : entities)
        {
            if (entity != null && entity.intersects(cell))
            {
                return false;
            }
        }
        return true;
    }
}
