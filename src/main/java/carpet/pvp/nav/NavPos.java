package carpet.pvp.nav;

/**
 * Block positions packed into a single long, laid out like {@code BlockPos.asLong()} (26 bits of x, 26 of z,
 * 12 of y) so a packed cell can be handed straight to a world once this code is wired into the mod.
 *
 * World coordinates fit the 26 bit fields; a position outside them cannot round-trip.
 */
public final class NavPos
{
    private static final long X_MASK = 0x3FFFFFFL;
    private static final long Y_MASK = 0xFFFL;
    private static final int Z_BITS = 12;
    private static final int X_BITS = 38;
    private static final int Y_BITS = 52;

    private NavPos()
    {
    }

    public static long pack(int x, int y, int z)
    {
        return ((x & X_MASK) << X_BITS) | ((z & X_MASK) << Z_BITS) | (y & Y_MASK);
    }

    public static int unpackX(long pos)
    {
        return (int) (pos >> X_BITS);
    }

    public static int unpackY(long pos)
    {
        return (int) ((pos << Y_BITS) >> Y_BITS);
    }

    public static int unpackZ(long pos)
    {
        return (int) ((pos << (X_BITS - Z_BITS)) >> X_BITS);
    }

    public static boolean canPack(int x, int z)
    {
        return x >= -X_MASK / 2 && x <= X_MASK / 2 && z >= -X_MASK / 2 && z <= X_MASK / 2;
    }
}