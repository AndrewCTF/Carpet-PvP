package carpet.pvp.smp;

import carpet.pvp.sim.ProjectileAim;
import carpet.pvp.sim.ProjectileSim;

/**
 * The aim of an SMP bot's throws: the splash it drops on its own feet, the pearl that buys it room,
 * and the point it looks at when it puts a cobweb under a running target.
 *
 * <p>Every answer here comes out of {@link ProjectileAim} or {@link ProjectileSim} and is pure, so
 * the unit tests check the same arithmetic the bot flies with. The aim of a throw at the thrower's
 * own feet keeps the yaw the bot already faces and takes only the pitch from the solver, because the
 * throw it wants has no horizontal component at all and a solve that turned the bot to face north to
 * drop a potion on its own boots would be no more human than useless. That pitch is then flown and
 * checked before the bot throws anything.</p>
 */
public final class SmpAim
{
    /** How far a retreat throw aims for, as fractions of the distance the bot asked for. */
    private static final double[] PEEL_DISTANCE = {1.0, 0.7, 0.45};
    /** Ticks a throw is given to reach the ground it was aimed at. */
    public static final int THROW_LIMIT = 40;
    /** How close a potion has to burst to the thrower's feet to be worth throwing. */
    public static final double FEET_TOLERANCE = 1.2D;

    private SmpAim()
    {
    }

    /**
     * The pitch that drops a thrown splash onto the thrower's own feet at the yaw it is facing.
     *
     * @return the pitch in degrees, or {@link Double#NaN} while no pitch of that throw lands there
     */
    public static double feetPitch(ProjectileAim.Shooter shooter, double groundY, double yaw)
    {
        ProjectileAim.Aim aim = ProjectileAim.selfSplash(shooter, groundY, THROW_LIMIT)[0];
        return aim.solved && landsOnFeet(shooter, aim.pitch, yaw, groundY) ? aim.pitch : Double.NaN;
    }

    /**
     * The pitch that drops a thrown splash onto a point a little ahead of the thrower, which is where
     * a player walking forwards throws one: the splash covers four blocks, so it heals the thrower on
     * the way in and the throw does not have to be straight down.
     *
     * <p>The spot is in front of the thrower's own view rather than along its motion: a thrower's
     * velocity swings about as it fights, and a spot that swings with it is one the view never settles
     * on. The solve still undoes the motion, so the splash lands on the spot either way.</p>
     *
     * @param ahead how far in front of the thrower the splash should burst
     * @param yaw   the direction the thrower is facing
     * @return the pitch in degrees, or {@link Double#NaN} while nothing lands that close
     */
    public static double aheadPitch(ProjectileAim.Shooter shooter, double groundY, double ahead, double yaw)
    {
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians);
        double dz = Math.cos(radians);
        ProjectileAim.Aim aim = ProjectileAim.solveGroundPoint(ProjectileSim.Kind.SPLASH_POTION, shooter,
                shooter.x + dx * ahead, groundY, shooter.z + dz * ahead, THROW_LIMIT)[0];
        return aim.solved ? aim.pitch : Double.NaN;
    }

    /**
     * A pearl that lands a chosen distance away along the line the thrower is running away from. A
     * wall in the way does not matter to the solve: the pearl stops at the wall and teleports the
     * thrower to it, which is what a player gets.
     *
     * <p>Distances no pearl can reach are not solved for and the next shorter one is tried, so the
     * bot still gets out of reach when it would like more than it can throw.</p>
     *
     * @param fromX  the horizontal point the thrower is running away from
     * @param fromZ  the other horizontal coordinate of that point
     * @param groundY the height of the ground the pearl has to land on
     * @param wanted  the distance the thrower would like to buy
     * @return the aim, or {@link ProjectileAim#OUT_OF_RANGE} while nothing it can throw reaches
     */
    public static ProjectileAim.Aim peelAway(ProjectileAim.Shooter shooter, double fromX, double fromZ,
            double groundY, double wanted)
    {
        return peelAway(shooter, fromX, fromZ, groundY, wanted, false);
    }

    /**
     * As {@link #peelAway(ProjectileAim.Shooter, double, double, double, double)}, but preferring one
     * arc over the other. A throw that starts inside the other fighter's reach has to go over it, so
     * that is the case for the lob: a flat throw from arm's length is a throw into them.
     *
     * @param loft prefer the arc that goes up before it comes down, rather than the flat one
     */
    public static ProjectileAim.Aim peelAway(ProjectileAim.Shooter shooter, double fromX, double fromZ,
            double groundY, double wanted, boolean loft)
    {
        double dx = shooter.x - fromX;
        double dz = shooter.z - fromZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-6 || wanted <= 0.0)
        {
            return ProjectileAim.OUT_OF_RANGE;
        }
        for (double share : PEEL_DISTANCE)
        {
            double distance = wanted * share;
            ProjectileAim.Aim[] arcs = ProjectileAim.solveGroundPoint(ProjectileSim.Kind.PEARL, shooter,
                    shooter.x + dx / len * distance, groundY, shooter.z + dz / len * distance, THROW_LIMIT);
            for (int arc = 0; arc < 2; arc++)
            {
                ProjectileAim.Aim aim = arcs[loft ? 1 - arc : arc];
                if (aim.solved)
                {
                    return aim;
                }
            }
        }
        return ProjectileAim.OUT_OF_RANGE;
    }

    /**
     * Where a pearl thrown at the given yaw and pitch meets flat ground, written into {@code where}.
     *
     * @return the tick it lands on, or -1 while it stays up longer than the limit
     */
    public static int landing(ProjectileAim.Shooter shooter, double yaw, double pitch, double groundY,
            double[] where)
    {
        return ProjectileAim.pearlLandingTicks(shooter, yaw, pitch, groundY, THROW_LIMIT, where);
    }

    /**
     * The yaw and pitch of the Minecraft view that looks from one point at another, written into
     * {@code out} as {yaw, pitch}.
     */
    public static void lookAt(double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
            double[] out)
    {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double flat = Math.sqrt(dx * dx + dz * dz);
        out[0] = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
        out[1] = -Math.toDegrees(Math.atan2(dy, Math.max(flat, 1.0E-6D)));
    }

    /** True when a splash thrown at that pitch and yaw bursts within reach of the thrower's own feet. */
    private static boolean landsOnFeet(ProjectileAim.Shooter shooter, double pitch, double yaw, double groundY)
    {
        ProjectileSim.Launch launch = ProjectileSim.handLaunch(ProjectileSim.Kind.SPLASH_POTION, shooter.x,
                shooter.y, shooter.z, yaw, pitch, shooter.vx, shooter.vy, shooter.vz, shooter.onGround);
        ProjectileSim flight = new ProjectileSim(launch);
        double[] where = new double[3];
        if (!flight.groundPoint(groundY, THROW_LIMIT, where))
        {
            return false;
        }
        return ProjectileAim.boxDistance(where[0], where[1], where[2], shooter.x, groundY, shooter.z,
                0.0D, 0.0D) <= FEET_TOLERANCE;
    }
}