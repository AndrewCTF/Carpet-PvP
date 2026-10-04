package carpet.pvp.sim;

/**
 * Absolute block space box, the shape Entity.getBoundingBox returns. Mirrors AABB.ofSize for
 * {@link #player} and AABB.intersects for {@link #intersects}.
 */
public final class Box
{
    /** EntityType.PLAYER width. */
    public static final double PLAYER_WIDTH = 0.6;
    /** EntityType.PLAYER height. */
    public static final double PLAYER_HEIGHT = 1.8;

    public final double minX;
    public final double minY;
    public final double minZ;
    public final double maxX;
    public final double maxY;
    public final double maxZ;

    public Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
    {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    /** The box of a player standing with its feet at (x, y, z). */
    public static Box player(double x, double y, double z)
    {
        double half = PLAYER_WIDTH / 2.0;
        return new Box(x - half, y, z - half, x + half, y + PLAYER_HEIGHT, z + half);
    }

    /** The block at (x, y, z). */
    public static Box block(int x, int y, int z)
    {
        return new Box(x, y, z, x + 1.0, y + 1.0, z + 1.0);
    }

    /** The one by two by one column EndCrystalItem.useOn queries for entities when the block below is (x, y, z). */
    public static Box column(int x, int y, int z)
    {
        return new Box(x, y, z, x + 1.0, y + 2.0, z + 1.0);
    }

    public Box moved(double dx, double dy, double dz)
    {
        return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** Mirrors AABB.intersects, which is strict on all six faces. */
    public boolean intersects(Box other)
    {
        return minX < other.maxX && maxX > other.minX
                && minY < other.maxY && maxY > other.minY
                && minZ < other.maxZ && maxZ > other.minZ;
    }
}
