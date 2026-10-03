package carpet.pvp.nav;

/**
 * A world made of bytes, for the navigation tests: solid ground, walls, platforms and hazards where a test puts
 * them, and nothing at all outside, which reads as unpassable the way an unloaded chunk would.
 */
final class TestGrid implements Walkability
{
    static final int SOLID = 1;
    static final int HAZARD = 2;

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final byte[] cells;

    TestGrid(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ)
    {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        cells = new byte[sizeX * sizeY * sizeZ];
    }

    /** A plain world with solid ground at y = 0, so a player's feet are at y = 1 and its head at y = 2. */
    static TestGrid flat(int minX, int minZ, int sizeX, int sizeZ)
    {
        TestGrid grid = new TestGrid(minX, 0, minZ, sizeX, 6, sizeZ);
        grid.fill(minX, 0, minZ, minX + sizeX - 1, 0, minZ + sizeZ - 1, SOLID);
        return grid;
    }

    /** Sets the flags on an inclusive range of blocks. */
    TestGrid fill(int x0, int y0, int z0, int x1, int y1, int z1, int flags)
    {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
        {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
            {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                {
                    if (inside(x, y, z))
                    {
                        cells[index(x, y, z)] = (byte) flags;
                    }
                }
            }
        }
        return this;
    }

    TestGrid solid(int x0, int y0, int z0, int x1, int y1, int z1)
    {
        return fill(x0, y0, z0, x1, y1, z1, SOLID);
    }

    TestGrid hazard(int x0, int y0, int z0, int x1, int y1, int z1)
    {
        return fill(x0, y0, z0, x1, y1, z1, HAZARD);
    }

    TestGrid clear(int x0, int y0, int z0, int x1, int y1, int z1)
    {
        return fill(x0, y0, z0, x1, y1, z1, 0);
    }

    /** A wall from foot height to head height, which no walk can pass through. */
    TestGrid wall(int x0, int z0, int x1, int z1)
    {
        return solid(x0, 1, z0, x1, 2, z1);
    }

    /** A raised platform whose surface is at {@code surfaceY}, so a player on it stands at {@code surfaceY + 1}. */
    TestGrid platform(int x0, int z0, int x1, int z1, int surfaceY)
    {
        return fill(x0, 0, z0, x1, surfaceY - 1, z1, SOLID);
    }

    @Override
    public boolean canStand(int x, int y, int z)
    {
        return canPass(x, y, z) && canPass(x, y + 1, z) && solid(x, y - 1, z) && !hazard(x, y - 1, z);
    }

    @Override
    public boolean canPass(int x, int y, int z)
    {
        return inside(x, y, z) && cells[index(x, y, z)] == 0;
    }

    @Override
    public boolean hazard(int x, int y, int z)
    {
        return inside(x, y, z) && (cells[index(x, y, z)] & HAZARD) != 0;
    }

    private boolean solid(int x, int y, int z)
    {
        return inside(x, y, z) && (cells[index(x, y, z)] & SOLID) != 0;
    }

    private boolean inside(int x, int y, int z)
    {
        return x >= minX && x < minX + sizeX && y >= minY && y < minY + sizeY && z >= minZ && z < minZ + sizeZ;
    }

    private int index(int x, int y, int z)
    {
        return ((x - minX) * sizeZ + (z - minZ)) * sizeY + (y - minY);
    }
}