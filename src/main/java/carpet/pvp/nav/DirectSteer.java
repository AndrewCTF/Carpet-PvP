package carpet.pvp.nav;

/**
 * "Can I walk in a straight line from here to there, right now", used to skip path search when a target is
 * already in line of walk and by {@link PathSmoother} to decide whether a waypoint can be skipped.
 *
 * The walk is the one a player-sized box would take: centred between the two block centres and 0.6 blocks wide,
 * so it fits inside a one-block corridor but clips whatever juts into it. Every block the swept box touches has to
 * be standable, which also rules out crossing a hole or a drop the pathfinder never walked into.
 */
public final class DirectSteer
{
    /** Half the width of a player, so a centred box spans 0.2 to 0.8 of every block it covers. */
    private static final double HALF_WIDTH = 0.3D;
    /** Stands in for 1/dx when there is no x travel, so the span arithmetic needs no special case. */
    private static final double FLAT = 1.0E9D;

    private DirectSteer()
    {
    }

    public static boolean canWalkLine(Walkability view, int x0, int y0, int z0, int x1, int y1, int z1)
    {
        // Only a level walk is a straight walk; a change of height is a jump or a fall and has to be planned.
        if (y0 != y1)
        {
            return false;
        }
        double px = x0 + 0.5D;
        double pz = z0 + 0.5D;
        double dx = x1 - x0;
        double dz = z1 - z0;
        double xFrom = px + (dx < 0.0D ? dx : 0.0D);
        double xTo = px + (dx > 0.0D ? dx : 0.0D);
        double zFrom = pz + (dz < 0.0D ? dz : 0.0D);
        double zTo = pz + (dz > 0.0D ? dz : 0.0D);
        double stepX = dx == 0.0D ? FLAT : 1.0D / dx;

        int firstZ = cellFloor(zFrom - HALF_WIDTH);
        int lastZ = cellFloor(zTo + HALF_WIDTH);

        for (int x = cellFloor(xFrom - HALF_WIDTH); x <= cellFloor(xTo + HALF_WIDTH); x++)
        {
            // The box overlaps this column while its centre is within half a block of it, that is over this span
            // of the walk. The centre moves monotonically in z, so the columns it spans are exactly the ones it
            // touches while it is over x.
            double enter = (x - HALF_WIDTH - px) * stepX;
            double leave = (x + 1.0D + HALF_WIDTH - px) * stepX;
            double low = enter < leave ? enter : leave;
            double high = enter < leave ? leave : enter;
            if (low < 0.0D)
            {
                low = 0.0D;
            }
            if (high > 1.0D)
            {
                high = 1.0D;
            }
            if (low > high)
            {
                continue;
            }
            double zLow = pz + dz * low;
            double zHigh = pz + dz * high;
            if (zLow > zHigh)
            {
                double swap = zLow;
                zLow = zHigh;
                zHigh = swap;
            }
            int fromZ = Math.max(firstZ, cellFloor(zLow - HALF_WIDTH));
            int toZ = Math.min(lastZ, cellFloor(zHigh + HALF_WIDTH));

            for (int z = fromZ; z <= toZ; z++)
            {
                if (!view.canStand(x, y0, z))
                {
                    return false;
                }
            }
        }
        return true;
    }

    private static int cellFloor(double value)
    {
        return (int) Math.floor(value);
    }
}