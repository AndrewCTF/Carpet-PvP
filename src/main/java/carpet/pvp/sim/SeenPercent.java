package carpet.pvp.sim;

/**
 * How much of a bounding box an explosion can see, mirroring ServerExplosion.getSeenPercent: a grid of
 * sample rays is cast from every grid point of the box towards the explosion centre and the fraction that
 * reaches the centre unclipped is returned. Each ray is walked with the voxel loop of
 * BlockGetter.traverseBlocks, both ray ends shrunk by -1e-7 first.
 */
public final class SeenPercent
{
    /** The shrink BlockGetter.traverseBlocks applies to both ends of the ray. */
    private static final double SHRINK = -1.0E-7;
    /**
     * AABB.clip ignores a face hit whose position along the ray is within 1e-7 of the end, so a ray that only
     * grazes a block on its last sliver of travel does not count as blocked.
     */
    private static final double GRAZE = 1.0E-7;
    /** Mth.lerp of a zero axis step is replaced by this in BlockGetter.traverseBlocks. */
    private static final double UNBOUNDED = 1.7976931348623157E308;

    private SeenPercent()
    {
    }

    /**
     * Number of rays {@link #of} casts for the given box, i.e. the number of grid points. A player box
     * is 3 * 5 * 3.
     */
    public static int rayCount(Box box)
    {
        return steps(box.minX, box.maxX) * steps(box.minY, box.maxY) * steps(box.minZ, box.maxZ);
    }

    /** Fraction of the box visible from (sx, sy, sz), in [0, 1]. */
    public static float of(ExplosionView view, double sx, double sy, double sz, Box box)
    {
        double dx = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double dy = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double dz = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double ox = (1.0 - Math.floor(1.0 / dx) * dx) / 2.0;
        double oz = (1.0 - Math.floor(1.0 / dz) * dz) / 2.0;
        if (dx < 0.0 || dy < 0.0 || dz < 0.0)
        {
            return 0.0f;
        }
        int seen = 0;
        int total = 0;
        for (double t = 0.0; t <= 1.0; t += dx)
        {
            double px = box.minX + t * (box.maxX - box.minX) + ox;
            for (double u = 0.0; u <= 1.0; u += dy)
            {
                double py = box.minY + u * (box.maxY - box.minY);
                for (double v = 0.0; v <= 1.0; v += dz)
                {
                    double pz = box.minZ + v * (box.maxZ - box.minZ) + oz;
                    if (!blocked(view, px, py, pz, sx, sy, sz))
                    {
                        seen++;
                    }
                    total++;
                }
            }
        }
        return (float) seen / (float) total;
    }

    /**
     * True when a block between the two points stops the ray, walking the voxels the way
     * BlockGetter.traverseBlocks walks them. A block only counts once the ray has more than GRAZE of the ray
     * left inside it, which is how AABB.clip drops a face it only touches.
     */
    public static boolean blocked(ExplosionView view, double fx, double fy, double fz, double tx, double ty, double tz)
    {
        if (fx == tx && fy == ty && fz == tz)
        {
            return false;
        }
        double startX = fx + SHRINK * (tx - fx);
        double startY = fy + SHRINK * (ty - fy);
        double startZ = fz + SHRINK * (tz - fz);
        double endX = tx + SHRINK * (fx - tx);
        double endY = ty + SHRINK * (fy - ty);
        double endZ = tz + SHRINK * (fz - tz);
        int x = (int) Math.floor(startX);
        int y = (int) Math.floor(startY);
        int z = (int) Math.floor(startZ);
        if (view.blocksExplosion(x, y, z))
        {
            return true;
        }
        int sx = sign(endX - startX);
        int sy = sign(endY - startY);
        int sz = sign(endZ - startZ);
        double stepX = sx == 0 ? UNBOUNDED : (double) sx / (endX - startX);
        double stepY = sy == 0 ? UNBOUNDED : (double) sy / (endY - startY);
        double stepZ = sz == 0 ? UNBOUNDED : (double) sz / (endZ - startZ);
        double tX = stepX * (sx > 0 ? 1.0 - frac(startX) : frac(startX));
        double tY = stepY * (sy > 0 ? 1.0 - frac(startY) : frac(startY));
        double tZ = stepZ * (sz > 0 ? 1.0 - frac(startZ) : frac(startZ));
        // The walk ends only once all three crossings are past the end of the ray.
        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0)
        {
            double enter = Math.min(tX, Math.min(tY, tZ));
            if (tX < tY)
            {
                if (tX < tZ)
                {
                    x += sx;
                    tX += stepX;
                }
                else
                {
                    z += sz;
                    tZ += stepZ;
                }
            }
            else
            {
                if (tY < tZ)
                {
                    y += sy;
                    tY += stepY;
                }
                else
                {
                    z += sz;
                    tZ += stepZ;
                }
            }
            if (1.0 - enter > GRAZE && view.blocksExplosion(x, y, z))
            {
                return true;
            }
        }
        return false;
    }

    private static int steps(double min, double max)
    {
        int n = 0;
        for (double t = 0.0; t <= 1.0; t += 1.0 / ((max - min) * 2.0 + 1.0))
        {
            n++;
        }
        return n;
    }

    /** Mirrors Mth.sign, which returns 0 only for exactly zero. */
    private static int sign(double value)
    {
        return value == 0.0 ? 0 : value > 0.0 ? 1 : -1;
    }

    /** Mirrors Mth.frac. */
    private static double frac(double value)
    {
        return value - Math.floor(value);
    }
}
