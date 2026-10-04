package carpet.pvp.sim;

/**
 * A small in-memory grid of block kinds for the crystal model tests. Anything outside the grid reads as air
 * and blocks nothing, so a test only has to describe the shape it cares about.
 */
final class GridWorld implements ExplosionView
{
    static final byte AIR = 0;
    static final byte OBSIDIAN = 1;
    static final byte BEDROCK = 2;
    static final byte STONE = 3;

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final byte[] cells;

    GridWorld(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        sizeX = maxX - minX + 1;
        sizeY = maxY - minY + 1;
        sizeZ = maxZ - minZ + 1;
        cells = new byte[sizeX * sizeY * sizeZ];
    }

    /** A grid spanning (0, 0, 0) to (sizeX - 1, sizeY - 1, sizeZ - 1). */
    static GridWorld of(int sizeX, int sizeY, int sizeZ)
    {
        return new GridWorld(0, 0, 0, sizeX - 1, sizeY - 1, sizeZ - 1);
    }

    void set(int x, int y, int z, byte kind)
    {
        cells[index(x, y, z)] = kind;
    }

    /** Every block of the inclusive box. */
    void fill(int fromX, int fromY, int fromZ, int toX, int toY, int toZ, byte kind)
    {
        for (int x = fromX; x <= toX; x++)
        {
            for (int y = fromY; y <= toY; y++)
            {
                for (int z = fromZ; z <= toZ; z++)
                {
                    set(x, y, z, kind);
                }
            }
        }
    }

    byte get(int x, int y, int z)
    {
        if (x < minX || y < minY || z < minZ || x >= minX + sizeX || y >= minY + sizeY || z >= minZ + sizeZ)
        {
            return AIR;
        }
        return cells[index(x, y, z)];
    }

    public boolean blocksExplosion(int x, int y, int z)
    {
        return get(x, y, z) != AIR;
    }

    public boolean isCrystalBase(int x, int y, int z)
    {
        byte kind = get(x, y, z);
        return kind == OBSIDIAN || kind == BEDROCK;
    }

    public boolean isAir(int x, int y, int z)
    {
        return get(x, y, z) == AIR;
    }

    private int index(int x, int y, int z)
    {
        return ((y - minY) * sizeX + (x - minX)) * sizeZ + (z - minZ);
    }
}
