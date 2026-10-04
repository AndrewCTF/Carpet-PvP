package carpet.pvp.sim;

/**
 * Solves for the yaw and pitch that put a projectile through a moving target, and for the draw time of a bow shot.
 *
 * <p>{@link ProjectileSim#displacement} is an exact closed form of the game's per-tick update, so the solver does not
 * approximate a differential equation: for any whole number of flight ticks it can invert that update to find the one
 * launch velocity that arrives at the target's predicted position, and ask how much speed that costs. The cost is high
 * for a very short flight and for a very long one and lowest in between, so the cheapest tick splits the two arcs:
 * everything at or below it is a direct shot and everything above it is a lob.
 *
 * <p>The game only holds a position at whole ticks and tests a swept segment rather than a point, so a shot that ends up
 * beside the target can still cross its box. An arc is only reported as solved when some flight time crosses it, which
 * is the closest the whole tick launch can manage; {@link Aim#miss} then says how far past the box the flight ends.
 */
public final class ProjectileAim
{
    /**
     * How close a throw has to land to a point on the ground for the ground point queries to count it. A single tick of
     * flight covers most of a block, so half a block is about the finest the game can place an impact anyway.
     */
    public static final double GROUND_TOLERANCE = 0.4;

    /** No aim could reach the target. */
    public static final Aim OUT_OF_RANGE = new Aim(false, 0.0, 0.0, 0, -1, 0.0, null, Double.POSITIVE_INFINITY);

    /** One solution: where to look, how long the flight takes and how high it peaks. */
    public static final class Aim
    {
        public final boolean solved;
        public final double yaw;
        public final double pitch;
        /** Whole ticks between the throw and the hit. */
        public final int ticks;
        /** Bow draw ticks this aim needs, or -1 when the projectile is not a bow. */
        public final int drawTicks;
        /** Highest whole-tick height of the flight above the launch point. */
        public final double apex;
        /** The launch this aim was built from, so a caller can re-simulate exactly what the game will do. */
        public final ProjectileSim.Launch launch;
        /** How far the flight ends up past the target's box, which is zero when it crosses it. */
        public final double miss;

        Aim(boolean solved, double yaw, double pitch, int ticks, int drawTicks, double apex,
                ProjectileSim.Launch launch, double miss)
        {
            this.solved = solved;
            this.yaw = yaw;
            this.pitch = pitch;
            this.ticks = ticks;
            this.drawTicks = drawTicks;
            this.apex = apex;
            this.launch = launch;
            this.miss = miss;
        }
    }

    /** A shooter: where its feet are and how it is moving. */
    public static final class Shooter
    {
        public double x;
        public double y;
        public double z;
        public double vx;
        public double vy;
        public double vz;
        public boolean onGround = true;
        /** Bow draw ticks to aim for, or -1 to let {@link ProjectileAim#solveBow} choose. */
        public int drawTicks = -1;
    }

    /** Mirrors Entity.calculateViewVector: yaw 0 faces +z and a positive pitch looks down. */
    public static double[] direction(double yaw, double pitch)
    {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new double[] {-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch};
    }

    /**
     * The low and the high arc onto the target, or {@link #OUT_OF_RANGE} for an arc the item cannot reach. Both arcs
     * are the closest the whole tick launch can manage, so the choice between them is about the flight path.
     */
    public static Aim[] solve(ProjectileSim.Kind kind, double speed, double inaccuracy, double roll, Shooter shooter,
                              ProjectileSim.Target target, int limit)
    {
        return solve(kind, speed, inaccuracy, roll, shooter, target, limit, 0.0);
    }

    /**
     * As {@link #solve}, but a flight whose swept segment comes within the given distance of the target's box counts as
     * a solution. Zero is the game's own test, which is what a living target needs; a target with no size, such as a
     * point on the ground, has to ask for a tolerance.
     */
    public static Aim[] solve(ProjectileSim.Kind kind, double speed, double inaccuracy, double roll, Shooter shooter,
                              ProjectileSim.Target target, int limit, double tolerance)
    {
        double[] need = new double[3];
        int split = 1;
        double least = Double.POSITIVE_INFINITY;
        for (int ticks = 1; ticks <= limit; ticks++)
        {
            double cost = requiredVelocity(kind, roll, shooter, target, ticks, need);
            if (cost < least)
            {
                least = cost;
                split = ticks;
            }
        }
        if (least > speed)
        {
            return new Aim[] {OUT_OF_RANGE, OUT_OF_RANGE};
        }
        Aim[] best = {null, null};
        double[] bestGap = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        for (int ticks = 1; ticks <= limit; ticks++)
        {
            double cost = requiredVelocity(kind, roll, shooter, target, ticks, need);
            // The yaw comes straight from the azimuth of the required velocity. The pitch has to be solved for, because
            // the roll moves the vertical term without moving the horizontal one.
            double yaw = Math.toDegrees(Math.atan2(-need[0], need[2]));
            double pitch = pitchFor(need[1] / cost, roll);
            ProjectileSim.Launch launch = ProjectileSim.handLaunch(kind, shooter.x, shooter.y, shooter.z, yaw, pitch,
                    shooter.vx, shooter.vy, shooter.vz, shooter.onGround);
            launch.speed = speed;
            launch.inaccuracy = inaccuracy;
            launch.roll = roll;
            int arc = ticks <= split ? 0 : 1;
            ProjectileSim flight = new ProjectileSim(launch);
            double fromX = flight.x;
            double fromY = flight.y;
            double fromZ = flight.z;
            for (int tick = 0; tick < ticks; tick++)
            {
                fromX = flight.x;
                fromY = flight.y;
                fromZ = flight.z;
                flight.step();
            }
            // The game tests the whole swept segment of a tick, so that is what a shot is judged on, and undoing the
            // target's own motion puts the flight and the box in one frame for the tick they meet.
            double ox = target.vx * ticks;
            double oy = target.vy * ticks;
            double oz = target.vz * ticks;
            double off = ProjectileSim.segmentGap(fromX - ox, fromY - oy, fromZ - oz,
                    flight.x - ox, flight.y - oy, flight.z - oz,
                    target.x, target.y, target.z, target.halfWidth, target.height);
            if (off >= bestGap[arc])
            {
                continue;
            }
            bestGap[arc] = off;
            best[arc] = new Aim(true, yaw, pitch, ticks, -1, apex(new ProjectileSim(launch), ticks), launch,
                    boxDistance(flight.x, flight.y, flight.z, target.centreX(ticks), target.centreY(ticks),
                            target.centreZ(ticks), target.halfWidth, target.height));
        }
        Aim[] result = {OUT_OF_RANGE, OUT_OF_RANGE};
        for (int arc = 0; arc < 2; arc++)
        {
            if (best[arc] != null && bestGap[arc] <= tolerance)
            {
                result[arc] = best[arc];
            }
        }
        return result;
    }

    /**
     * The pitch whose launch direction has the given vertical fraction.
     *
     * <p>{@code Projectile.shootFromRotation} builds its direction as
     * {@code (-sin(yaw)cos(pitch), -sin(pitch + roll), cos(yaw)cos(pitch))} and normalises it, so the vertical fraction a
     * pitch can reach is {@code -sin(pitch + roll) / hypot(cos(pitch), sin(pitch + roll))}. That runs from one at
     * minus ninety degrees to minus one at plus ninety without a break, so a bisection finds the pitch. It matters
     * because the horizontal part keeps its full length at the pitch the fraction alone would suggest, and a splash
     * potion with its twenty degree roll can never be thrown straight down.
     */
    public static double pitchFor(double verticalFraction, double roll)
    {
        double lo = -90.0;
        double hi = 90.0;
        double wanted = clamp(verticalFraction, -1.0, 1.0);
        for (int i = 0; i < 60; i++)
        {
            double mid = 0.5 * (lo + hi);
            if (verticalFractionOf(mid, roll) > wanted)
            {
                lo = mid;
            }
            else
            {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** The vertical fraction of the launch direction a pitch produces, after {@code Projectile.getMovementToShoot} normalises it. */
    private static double verticalFractionOf(double pitch, double roll)
    {
        double p = Math.toRadians(pitch);
        double r = Math.toRadians(roll);
        double vertical = -Math.sin(p + r);
        double horizontal = Math.cos(p);
        return vertical / Math.sqrt(vertical * vertical + horizontal * horizontal);
    }

    /**
     * The launch velocity that arrives at the target's predicted position after the given number of ticks, ignoring the
     * shooter's own motion, written into out as {x, y, z}. Returns how much speed that velocity needs.
     */
    private static double requiredVelocity(ProjectileSim.Kind kind, double roll, Shooter shooter,
                                           ProjectileSim.Target target, int ticks, double[] out)
    {
        ProjectileSim probe = new ProjectileSim(kind, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        probe.requiredVelocity(ticks, target.centreX(ticks) - shooter.x,
                target.centreY(ticks) - ProjectileSim.handLaunchY(shooter.y, kind),
                target.centreZ(ticks) - shooter.z, out);
        // Projectile.shootFromRotation adds the shooter's own motion after the launch speed, so it has to come off.
        out[0] -= shooter.vx;
        out[1] -= shooter.onGround ? 0.0 : shooter.vy;
        out[2] -= shooter.vz;
        return Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
    }

    /** The two arcs of a hand throw, which is how pearls, potions, wind charges and tridents are aimed. */
    public static Aim[] solveHandThrow(ProjectileSim.Kind kind, Shooter shooter, ProjectileSim.Target target, int limit)
    {
        return solve(kind, kind.handSpeed(), ProjectileSim.HAND_INACCURACY, kind.handRoll(), shooter, target, limit);
    }

    /**
     * The two arcs of a bow shot. A {@link Shooter#drawTicks} of -1 or a draw that cannot reach the target makes the
     * solver try shorter draws from full downwards, so a bot prefers the fastest shot that works.
     */
    public static Aim[] solveBow(Shooter shooter, ProjectileSim.Target target, int limit)
    {
        if (shooter.drawTicks >= 0)
        {
            double power = ProjectileSim.bowPower(shooter.drawTicks);
            // A draw that is committed to is used as it is, even when it cannot fire: the bot has to release and start
            // again rather than quietly shoot at a different draw than the one it is holding.
            if (!ProjectileSim.bowFires(power))
            {
                return new Aim[] {OUT_OF_RANGE, OUT_OF_RANGE};
            }
            return withDraw(solve(ProjectileSim.Kind.ARROW, ProjectileSim.bowSpeed(power),
                    ProjectileSim.bowInaccuracy(power), 0.0, shooter, target, limit), shooter.drawTicks);
        }
        for (int draw = ProjectileSim.BOW_FULL_DRAW; draw >= 1; draw--)
        {
            double power = ProjectileSim.bowPower(draw);
            if (!ProjectileSim.bowFires(power))
            {
                continue;
            }
            Aim[] aimed = solve(ProjectileSim.Kind.ARROW, ProjectileSim.bowSpeed(power), 0.0, 0.0, shooter, target, limit);
            if (aimed[0].solved || aimed[1].solved)
            {
                return withDraw(aimed, draw);
            }
        }
        return new Aim[] {OUT_OF_RANGE, OUT_OF_RANGE};
    }

    private static Aim[] withDraw(Aim[] aims, int drawTicks)
    {
        for (int i = 0; i < aims.length; i++)
        {
            Aim aim = aims[i];
            if (aim.solved)
            {
                aims[i] = new Aim(true, aim.yaw, aim.pitch, aim.ticks, drawTicks, aim.apex, aim.launch, aim.miss);
            }
        }
        return aims;
    }

    /**
     * The highest whole-tick height the flight of this launch reaches above its own start. The game only ever holds a
     * position at whole ticks, so the peak between two ticks is not observable and is not reported.
     */
    public static double apex(ProjectileSim flight, int limit)
    {
        ProjectileSim probe = new ProjectileSim(flight.kind, flight.x, flight.y, flight.z, flight.vx, flight.vy, flight.vz);
        probe.inWater = flight.inWater;
        double start = flight.y;
        double top = 0.0;
        for (int ticks = 0; ticks < limit; ticks++)
        {
            probe.step();
            top = Math.max(top, probe.y - start);
        }
        return top;
    }

    /** The distance from a point to a box whose centre is the given one, which is zero inside the box. */
    public static double boxDistance(double x, double y, double z, double centreX, double centreY, double centreZ,
                                     double halfWidth, double height)
    {
        double dx = Math.max(Math.max(centreX - halfWidth - x, x - (centreX + halfWidth)), 0.0);
        double dy = Math.max(Math.max(centreY - height / 2.0 - y, y - (centreY + height / 2.0)), 0.0);
        double dz = Math.max(Math.max(centreZ - halfWidth - z, z - (centreZ + halfWidth)), 0.0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The yaw and pitch that put a throw on a chosen point of flat ground at the given height. The point is treated as
     * a target with no size, which is the same solver with the box collapsed.
     */
    public static Aim[] solveGroundPoint(ProjectileSim.Kind kind, Shooter shooter, double pointX, double pointY,
                                         double pointZ, int limit)
    {
        ProjectileSim.Target point = new ProjectileSim.Target();
        point.x = pointX;
        point.y = pointY - GROUND_TOLERANCE / 2.0;
        point.z = pointZ;
        point.halfWidth = GROUND_TOLERANCE / 2.0;
        point.height = GROUND_TOLERANCE;
        return solve(kind, kind.handSpeed(), ProjectileSim.HAND_INACCURACY, kind.handRoll(), shooter, point, limit);
    }

    /**
     * How many ticks a pearl thrown at the given yaw and pitch takes to reach flat ground at the given height, and
     * where it is when it does. Writes {x, y, z} into landing and returns the tick, or -1 if it stays up longer.
     */
    public static int pearlLandingTicks(Shooter shooter, double yaw, double pitch, double groundY, int limit, double[] landing)
    {
        ProjectileSim.Launch launch = ProjectileSim.handLaunch(ProjectileSim.Kind.PEARL, shooter.x, shooter.y, shooter.z,
                yaw, pitch, shooter.vx, shooter.vy, shooter.vz, shooter.onGround);
        ProjectileSim pearl = new ProjectileSim(launch);
        int ticks = pearl.tickReaching(groundY, limit);
        if (ticks < 0)
        {
            return -1;
        }
        ProjectileSim landed = new ProjectileSim(launch);
        for (int tick = 0; tick < ticks; tick++)
        {
            landed.step();
        }
        landing[0] = landed.x;
        landing[1] = landed.y;
        landing[2] = landed.z;
        return ticks;
    }

    /**
     * Where a wind charge thrown at the given yaw and pitch bursts against flat ground at the given height. The charge
     * has no gravity and no drag, so this is the straight line from the eyes down to the ground. Null when the charge
     * is not thrown downwards.
     */
    public static double[] windChargeBurst(Shooter shooter, double yaw, double pitch, double groundY)
    {
        ProjectileSim.Launch launch = ProjectileSim.handLaunch(ProjectileSim.Kind.WIND_CHARGE, shooter.x, shooter.y,
                shooter.z, yaw, pitch, shooter.vx, shooter.vy, shooter.vz, shooter.onGround);
        double[] velocity = new double[3];
        ProjectileSim.launchVelocity(launch, velocity);
        if (velocity[1] >= 0.0)
        {
            return null;
        }
        double t = (groundY - launch.y) / velocity[1];
        return new double[] {launch.x + velocity[0] * t, groundY, launch.z + velocity[2] * t};
    }

    /**
     * The two throws that drop a splash potion onto the thrower's own feet. The impact is the foot position itself,
     * so a bot walking forwards has to solve for a throw that lands there despite moving.
     */
    public static Aim[] selfSplash(Shooter shooter, double groundY, int limit)
    {
        return solveGroundPoint(ProjectileSim.Kind.SPLASH_POTION, shooter, shooter.x, groundY, shooter.z, limit);
    }

    private static double clamp(double value, double min, double max)
    {
        return value < min ? min : Math.min(value, max);
    }
}