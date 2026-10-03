package carpet.pvp.sim;

/**
 * Tick-exact flight model of the projectiles PvP bots throw, shoot and fire. Pure Java: every constant below was read
 * out of the 26.3 game jar, and the update order mirrors the order of the game's own tick methods.
 *
 * <p>Positions are block coordinates with y up, rotations are degrees in the Minecraft convention (yaw 0 faces +z,
 * pitch is positive looking down), and velocity is in blocks per tick.
 *
 * <p>Two update orders exist in the game and they do not agree:
 * <ul>
 *   <li>{@code ThrowableProjectile.tick} applies gravity, then inertia, then moves: {@code v.y -= g; v *= drag; p += v}.</li>
 *   <li>{@code AbstractArrow.tick} moves first, then inertia, then gravity: {@code p += v; v *= drag; v.y -= g}.</li>
 * </ul>
 * Both are reproduced exactly, which is why {@link Kind#gravityFirst()} differs per kind.
 */
public final class ProjectileSim
{
    /** ThrowableProjectile.getDefaultGravity. */
    public static final double THROWABLE_GRAVITY = 0.03;
    /** AbstractThrownPotion.getDefaultGravity. */
    public static final double POTION_GRAVITY = 0.05;
    /** AbstractArrow.getDefaultGravity. */
    public static final double ARROW_GRAVITY = 0.05;
    /** ThrowableProjectile.getAirDrag and AbstractArrow.getAirDrag. */
    public static final double AIR_DRAG = 0.99;
    /** ThrowableProjectile.applyInertia drag while isInWater. */
    public static final double THROWABLE_WATER_DRAG = 0.8;
    /** AbstractArrow.getWaterInertia. */
    public static final double ARROW_WATER_DRAG = 0.6;
    /** ThrownTrident.getWaterInertia: a thrown trident barely slows in water. */
    public static final double TRIDENT_WATER_DRAG = 0.99;
    /** AbstractHurtingProjectile.getInertia. */
    public static final double HURTING_INERTIA = 0.95;
    /** AbstractHurtingProjectile.getLiquidInertia. */
    public static final double HURTING_LIQUID_INERTIA = 0.8;
    /** AbstractWindCharge.getInertia and getLiquidInertia: a wind charge keeps its speed. */
    public static final double WIND_CHARGE_INERTIA = 1.0;

    /** Projectile.getMovementToShoot scales each axis offset by this times the inaccuracy. */
    public static final double INACCURACY_SCALE = 0.0172275;
    /** The offset below the eyes that pearls, potions, arrows and tridents spawn at. */
    public static final double EYE_OFFSET = 0.1;
    /** LivingEntity eye height above the feet. */
    public static final double EYE_HEIGHT = 1.62;

    /** EnderpearlItem.PROJECTILE_SHOOT_POWER. */
    public static final double PEARL_SPEED = 1.5;
    /** ThrowablePotionItem.PROJECTILE_SHOOT_POWER. */
    public static final double POTION_SPEED = 0.5;
    /** WindChargeItem.PROJECTILE_SHOOT_POWER. */
    public static final double WIND_CHARGE_SPEED = 1.5;
    /** TridentItem.PROJECTILE_SHOOT_POWER. */
    public static final double TRIDENT_SPEED = 2.5;
    /** The inaccuracy every hand-thrown projectile is launched with. */
    public static final double HAND_INACCURACY = 1.0;
    /**
     * The pitch offset ThrowablePotionItem.use hands to Projectile.shootFromRotation as its roll, which makes a thrown
     * potion leave at twenty degrees above where the thrower looks.
     */
    public static final double POTION_ROLL = -20.0;
    /** BowItem.MAX_DRAW_DURATION. */
    public static final int BOW_FULL_DRAW = 20;
    /** BowItem.releaseUsing refuses to fire below this draw fraction. */
    public static final double BOW_MIN_POWER = 0.1;
    /** CrossbowItem.ARROW_POWER. */
    public static final double CROSSBOW_ARROW_SPEED = 3.15;
    /** CrossbowItem.FIREWORK_POWER. */
    public static final double CROSSBOW_FIREWORK_SPEED = 1.6;
    /** AbstractArrow.ARROW_BASE_DAMAGE. */
    public static final double ARROW_BASE_DAMAGE = 2.0;
    /** AbstractThrownPotion.SPLASH_RANGE. */
    public static final double SPLASH_RANGE = 4.0;
    /** AbstractThrownPotion.SPLASH_RANGE_SQ, the squared distance at which the strength reaches zero. */
    public static final double SPLASH_RANGE_SQ = 16.0;
    /** AbstractWindCharge.onHitBlock puts the burst this far inside the face that was hit. */
    public static final double WIND_CHARGE_FACE_INSET = 0.25;

    /** How a kind integrates its velocity. The drag is the one used in air; {@link #inWater} selects the water drag. */
    public enum Kind
    {
        /** ThrownEnderpearl: ThrowableProjectile physics, gravity 0.03. */
        PEARL(true, THROWABLE_GRAVITY, AIR_DRAG, THROWABLE_WATER_DRAG, PEARL_SPEED),
        /** ThrownSplashPotion: AbstractThrownPotion raises the gravity to 0.05. */
        SPLASH_POTION(true, POTION_GRAVITY, AIR_DRAG, THROWABLE_WATER_DRAG, POTION_SPEED),
        /** ThrownLingeringPotion, identical flight to a splash potion. */
        LINGERING_POTION(true, POTION_GRAVITY, AIR_DRAG, THROWABLE_WATER_DRAG, POTION_SPEED),
        /** Arrow: moves before it is dragged, so its gravity is not scaled by the drag. */
        ARROW(false, ARROW_GRAVITY, AIR_DRAG, ARROW_WATER_DRAG, CROSSBOW_ARROW_SPEED),
        /** ThrownTrident: an arrow that keeps its speed in water. */
        TRIDENT(false, ARROW_GRAVITY, AIR_DRAG, TRIDENT_WATER_DRAG, TRIDENT_SPEED),
        /**
         * WindCharge: no gravity and inertia 1.0 in both air and water, so it flies dead straight. It also has no
         * self acceleration, because AbstractWindCharge resets AbstractHurtingProjectile.INITAL_ACCELERATION_POWER
         * (0.1) to zero in every one of its constructors.
         */
        WIND_CHARGE(false, 0.0, WIND_CHARGE_INERTIA, WIND_CHARGE_INERTIA, WIND_CHARGE_SPEED);

        private final boolean gravityFirst;
        private final double gravity;
        private final double airDrag;
        private final double waterDrag;
        private final double handSpeed;

        Kind(boolean gravityFirst, double gravity, double airDrag, double waterDrag, double handSpeed)
        {
            this.gravityFirst = gravityFirst;
            this.gravity = gravity;
            this.airDrag = airDrag;
            this.waterDrag = waterDrag;
            this.handSpeed = handSpeed;
        }

        /** True when the game subtracts gravity before scaling the velocity, as ThrowableProjectile does. */
        public boolean gravityFirst()
        {
            return gravityFirst;
        }

        public double gravity()
        {
            return gravity;
        }

        public double airDrag()
        {
            return airDrag;
        }

        public double waterDrag()
        {
            return waterDrag;
        }

        /** The launch speed of a full draw of this kind, or of the hand throw when there is one. */
        public double handSpeed()
        {
            return handSpeed;
        }

        /** The roll a hand throw of this kind passes to Projectile.shootFromRotation. */
        public double handRoll()
        {
            return this == SPLASH_POTION || this == LINGERING_POTION ? POTION_ROLL : 0.0;
        }

        /** True when a hand throw starts at the eyes instead of a tenth of a block below them. */
        public boolean spawnsAtEyes()
        {
            return this == WIND_CHARGE;
        }
    }

    /** Where a projectile is created and what it is aimed with. */
    public static final class Launch
    {
        public Kind kind;
        public double x;
        public double y;
        public double z;
        public double yaw;
        public double pitch;
        public double roll;
        public double speed;
        public double inaccuracy;
        /** The shooter's own velocity, added by Projectile.shootFromRotation after the launch speed is applied. */
        public double shooterVx;
        public double shooterVy;
        public double shooterVz;
        public boolean shooterOnGround = true;
        /**
         * The three RandomSource.triangle draws of Projectile.getMovementToShoot, each a fraction of the spread in
         * [-1, 1]. They default to zero, the middle of the range, so a launch is the one that was aimed for; a caller
         * that wants a particular unlucky throw moves them.
         */
        public double jitterX;
        public double jitterY;
        public double jitterZ;
    }

    /** A target: an upright box swept by a constant velocity. Its origin is the centre of its feet. */
    public static final class Target
    {
        public double x;
        public double y;
        public double z;
        public double vx;
        public double vy;
        public double vz;
        public double halfWidth = 0.3;
        public double height = 1.8;

        /** The middle of the box after the given number of ticks, which is what a bot aims at. */
        public double centreX(int ticks)
        {
            return x + vx * ticks;
        }

        public double centreY(int ticks)
        {
            return y + vy * ticks + height / 2.0;
        }

        public double centreZ(int ticks)
        {
            return z + vz * ticks;
        }
    }

    public double x;
    public double y;
    public double z;
    public double vx;
    public double vy;
    public double vz;
    public Kind kind;
    public boolean inWater;

    public ProjectileSim(Kind kind, double x, double y, double z, double vx, double vy, double vz)
    {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
    }

    public ProjectileSim(Launch launch)
    {
        kind = launch.kind;
        double[] velocity = new double[3];
        launchVelocity(launch, velocity);
        x = launch.x;
        y = launch.y;
        z = launch.z;
        vx = velocity[0];
        vy = velocity[1];
        vz = velocity[2];
    }

    public void copyFrom(ProjectileSim other)
    {
        kind = other.kind;
        x = other.x;
        y = other.y;
        z = other.z;
        vx = other.vx;
        vy = other.vy;
        vz = other.vz;
        inWater = other.inWater;
    }

    public double speed()
    {
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    /** The height a hand throw of this kind starts at, given the thrower's feet. */
    public static double handLaunchY(double feetY, Kind kind)
    {
        return feetY + EYE_HEIGHT - (kind.spawnsAtEyes() ? 0.0 : EYE_OFFSET);
    }

    /**
     * Mirrors Projectile.getMovementToShoot and the tail of Projectile.shootFromRotation: the direction is
     * normalised first, the inaccuracy is added per axis afterwards, the speed is applied to the result and only then is
     * the shooter's own velocity added.
     */
    public static void launchVelocity(Launch launch, double[] out)
    {
        double yaw = Math.toRadians(launch.yaw);
        double pitch = Math.toRadians(launch.pitch);
        double cosPitch = Math.cos(pitch);
        double dx = -Math.sin(yaw) * cosPitch;
        double dy = -Math.sin(pitch + Math.toRadians(launch.roll));
        double dz = Math.cos(yaw) * cosPitch;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-9)
        {
            dx = 0.0;
            dy = 0.0;
            dz = 0.0;
        }
        else
        {
            dx /= len;
            dy /= len;
            dz /= len;
        }
        if (launch.inaccuracy != 0.0)
        {
            double spread = INACCURACY_SCALE * launch.inaccuracy;
            dx += spread * launch.jitterX;
            dy += spread * launch.jitterY;
            dz += spread * launch.jitterZ;
        }
        out[0] = dx * launch.speed + launch.shooterVx;
        out[1] = dy * launch.speed + (launch.shooterOnGround ? 0.0 : launch.shooterVy);
        out[2] = dz * launch.speed + launch.shooterVz;
    }

    /** A hand throw from a thrower's feet, yaw, pitch and own velocity. */
    public static Launch handLaunch(Kind kind, double feetX, double feetY, double feetZ, double yaw, double pitch,
                                    double vx, double vy, double vz, boolean onGround)
    {
        Launch launch = new Launch();
        launch.kind = kind;
        launch.x = feetX;
        launch.y = handLaunchY(feetY, kind);
        launch.z = feetZ;
        launch.yaw = yaw;
        launch.pitch = pitch;
        launch.roll = kind.handRoll();
        launch.speed = kind.handSpeed();
        launch.inaccuracy = HAND_INACCURACY;
        launch.shooterVx = vx;
        launch.shooterVy = vy;
        launch.shooterVz = vz;
        launch.shooterOnGround = onGround;
        return launch;
    }

    /** Advances one tick the way the game's tick method for this kind does. */
    public void step()
    {
        double drag = inWater ? kind.waterDrag() : kind.airDrag();
        if (kind.gravityFirst())
        {
            if (kind.gravity() != 0.0)
            {
                vy -= kind.gravity();
            }
            vx *= drag;
            vy *= drag;
            vz *= drag;
            x += vx;
            y += vy;
            z += vz;
            return;
        }
        x += vx;
        y += vy;
        z += vz;
        vx *= drag;
        vy *= drag;
        vz *= drag;
        if (kind.gravity() != 0.0)
        {
            vy -= kind.gravity();
        }
    }

    /**
     * How far one block per tick of launch velocity carries a projectile horizontally in the given number of ticks.
     * A kind that drags before it moves loses the first drag to the update, hence the extra factor.
     */
    public double horizontalScale(int ticks)
    {
        double sum = dragSum(effectiveDrag(), ticks);
        return kind.gravityFirst() ? effectiveDrag() * sum : sum;
    }

    /**
     * How far gravity has pulled a projectile down over the given number of ticks, from a launch with no rise. A kind
     * that subtracts gravity before it drags has its gravity scaled by the drag as well, hence the extra factor.
     */
    public double verticalLoss(int ticks)
    {
        if (kind.gravity() == 0.0)
        {
            return 0.0;
        }
        double drag = effectiveDrag();
        double sum = dragSum(drag, ticks);
        double rise = kind.gravityFirst() ? drag * sum : sum;
        return kind.gravity() * (kind.gravityFirst() ? drag : 1.0) * (ticks - rise) / (1.0 - drag);
    }

    /**
     * The closed-form displacement from the current position after the given number of ticks, written into out as
     * {x, y, z}. Exact for the per-tick update of this kind while it stays in one medium, and the inverse of it is
     * what {@link #requiredVelocity} uses to make the aim solver a scan over whole ticks.
     */
    public void displacement(int ticks, double[] out)
    {
        double horizontal = horizontalScale(ticks);
        out[0] = vx * horizontal;
        out[1] = vy * horizontal - verticalLoss(ticks);
        out[2] = vz * horizontal;
    }

    /** The drag this projectile is currently losing speed to. */
    public double effectiveDrag()
    {
        return inWater ? kind.waterDrag() : kind.airDrag();
    }

    /** Sum of {@code drag^0 .. drag^(ticks-1)}, the distance covered by one block per tick moved before any drag. */
    public static double dragSum(double drag, int ticks)
    {
        // A wind charge has no drag at all, where the geometric sum degenerates into the plain tick count.
        return drag == 1.0 ? ticks : (1.0 - Math.pow(drag, ticks)) / (1.0 - drag);
    }

    /**
     * The launch velocity that puts this projectile at (dx, dy, dz) after the given number of ticks, written into out
     * as {x, y, z}. The exact inverse of {@link #displacement}.
     */
    public void requiredVelocity(int ticks, double dx, double dy, double dz, double[] out)
    {
        double horizontal = horizontalScale(ticks);
        out[0] = dx / horizontal;
        out[1] = (dy + verticalLoss(ticks)) / horizontal;
        out[2] = dz / horizontal;
    }

    /** The first tick at which this projectile reaches the given height, or -1 if it never gets there. */
    public int tickReaching(double groundY, int limit)
    {
        ProjectileSim probe = new ProjectileSim(kind, x, y, z, vx, vy, vz);
        probe.inWater = inWater;
        for (int ticks = 1; ticks <= limit; ticks++)
        {
            probe.step();
            if (probe.y <= groundY)
            {
                return ticks;
            }
        }
        return -1;
    }

    /**
     * Where this projectile meets flat ground at the given height, written into out as {x, y, z}. False if it does not
     * get there within limit ticks.
     */
    public boolean groundPoint(double groundY, int limit, double[] out)
    {
        ProjectileSim probe = new ProjectileSim(kind, x, y, z, vx, vy, vz);
        probe.inWater = inWater;
        for (int ticks = 1; ticks <= limit; ticks++)
        {
            probe.step();
            if (probe.y <= groundY)
            {
                out[0] = probe.x;
                out[1] = probe.y;
                out[2] = probe.z;
                return true;
            }
        }
        return false;
    }

    /**
     * The first tick whose swept segment passes through the target's box, or -1. The box has moved with the target's
     * velocity, so this accounts for a target that keeps moving while the projectile flies.
     */
    public int tickHitting(Target target, int limit)
    {
        ProjectileSim probe = new ProjectileSim(kind, x, y, z, vx, vy, vz);
        probe.inWater = inWater;
        for (int ticks = 1; ticks <= limit; ticks++)
        {
            double px = probe.x;
            double py = probe.y;
            double pz = probe.z;
            probe.step();
            if (segmentHitsBox(px, py, pz, probe.x, probe.y, probe.z, target, target.vx * ticks, target.vy * ticks, target.vz * ticks))
            {
                return ticks;
            }
        }
        return -1;
    }

    /**
     * True when the segment hits the target's box. The target's own velocity is undone on the segment first, so the
     * box itself is tested where it started.
     */
    public static boolean segmentHitsBox(double x0, double y0, double z0, double x1, double y1, double z1,
                                          Target target, double ox, double oy, double oz)
    {
        return segmentSlab(x0 - ox, y0 - oy, z0 - oz, x1 - ox, y1 - oy, z1 - oz,
                target.x, target.y, target.z, target.halfWidth, target.height);
    }

    /**
     * The gap between a segment and an upright box, zero when they touch. The slab test gives the separation of the
     * parameter ranges, and the segment length scales that into a distance, which is exact for a touch and a tight
     * bound otherwise.
     */
    public static double segmentGap(double x0, double y0, double z0, double x1, double y1, double z1,
                                    double bx, double by, double bz, double halfWidth, double height)
    {
        double[] from = {x0, y0, z0};
        double[] to = {x1, y1, z1};
        double[] lo = {bx - halfWidth, by, bz - halfWidth};
        double[] hi = {bx + halfWidth, by + height, bz + halfWidth};
        double tMin = 0.0;
        double tMax = 1.0;
        for (int axis = 0; axis < 3; axis++)
        {
            double d = to[axis] - from[axis];
            if (Math.abs(d) < 1.0E-12)
            {
                if (from[axis] < lo[axis] || from[axis] > hi[axis])
                {
                    return Math.min(Math.abs(from[axis] - lo[axis]), Math.abs(from[axis] - hi[axis]));
                }
                continue;
            }
            double t0 = (lo[axis] - from[axis]) / d;
            double t1 = (hi[axis] - from[axis]) / d;
            if (t0 > t1)
            {
                double t = t0;
                t0 = t1;
                t1 = t;
            }
            tMin = Math.max(tMin, t0);
            tMax = Math.min(tMax, t1);
        }
        if (tMin <= tMax)
        {
            return 0.0;
        }
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double dz = to[2] - from[2];
        return (tMin - tMax) * Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Slab test of a segment against an upright box whose min corner is (bx, by, bz). */
    public static boolean segmentSlab(double x0, double y0, double z0, double x1, double y1, double z1,
                                       double bx, double by, double bz, double halfWidth, double height)
    {
        double tMin = 0.0;
        double tMax = 1.0;
        double[] p = {x0, y0, z0};
        double[] q = {x1, y1, z1};
        double[] lo = {bx - halfWidth, by, bz - halfWidth};
        double[] hi = {bx + halfWidth, by + height, bz + halfWidth};
        for (int axis = 0; axis < 3; axis++)
        {
            double d = q[axis] - p[axis];
            if (Math.abs(d) < 1.0E-12)
            {
                if (p[axis] < lo[axis] || p[axis] > hi[axis])
                {
                    return false;
                }
                continue;
            }
            double t0 = (lo[axis] - p[axis]) / d;
            double t1 = (hi[axis] - p[axis]) / d;
            if (t0 > t1)
            {
                double t = t0;
                t0 = t1;
                t1 = t;
            }
            tMin = Math.max(tMin, t0);
            tMax = Math.min(tMax, t1);
            if (tMin > tMax)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The strength a splash potion thrown so that it bursts at (impactX, impactY, impactZ) applies to the target.
     * Mirrors ThrownSplashPotion.onHitAsPotion: the impact box is the hit point grown by SPLASH_RANGE sideways and by
     * half of it vertically, the target box is grown by the projectile margin, and the two are separated by the
     * distance between their corners. Anything inside the impact box gets the full strength, and anything outside
     * SPLASH_RANGE_SQ of it is not affected at all. The potion's own box is well under a quarter of a block and is
     * ignored here.
     */
    public static double splashStrengthAt(double impactX, double impactY, double impactZ, Target target)
    {
        double margin = splashMargin();
        double dx = boxGap(impactX - SPLASH_RANGE, impactX + SPLASH_RANGE,
                target.x - target.halfWidth - margin, target.x + target.halfWidth + margin);
        double dy = boxGap(impactY - SPLASH_RANGE / 2.0, impactY + SPLASH_RANGE / 2.0, target.y, target.y + target.height);
        double dz = boxGap(impactZ - SPLASH_RANGE, impactZ + SPLASH_RANGE,
                target.z - target.halfWidth - margin, target.z + target.halfWidth + margin);
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        // onHitAsPotion skips the entity outright once the squared distance reaches SPLASH_RANGE_SQ.
        return distanceSquared < SPLASH_RANGE_SQ ? splashStrength(distanceSquared) : 0.0;
    }

    /** The gap between two intervals, which is what AABB.distanceToSqr contributes on one axis. */
    private static double boxGap(double lo1, double hi1, double lo2, double hi2)
    {
        return Math.max(Math.max(lo2 - hi1, lo1 - hi2), 0.0);
    }

    /**
     * Mirrors ThrownSplashPotion.onHitAsPotion: the effect strength falls off linearly from one at the impact to zero
     * at AbstractThrownPotion.SPLASH_RANGE. Takes the squared distance between the impact box and the target box,
     * which is what AABB.distanceToSqr produces and what the game compares against SPLASH_RANGE_SQ.
     */
    public static double splashStrength(double distanceSquared)
    {
        return 1.0 - Math.sqrt(distanceSquared) / SPLASH_RANGE;
    }

    /**
     * Mirrors the duration lambda of ThrownSplashPotion.onHitAsPotion, which truncates to
     * {@code (int)(duration * strength * durationScale + 0.5)}.
     */
    public static int splashDuration(int duration, double strength, double durationScale)
    {
        return (int) (duration * strength * durationScale + 0.5);
    }

    /**
     * Mirrors AbstractArrow.onHitEntity: the damage is the speed at the moment of the hit times the arrow's base
     * damage, rounded up. Enchantments modify the base damage before this and are not modelled here.
     */
    public static int arrowDamage(double speed, double baseDamage)
    {
        double product = speed * baseDamage;
        if (product < 0.0)
        {
            product = 0.0;
        }
        return (int) Math.ceil(Math.min(product, 2.147483647E9));
    }

    /** The largest roll AbstractArrow draws for a critical hit of the given damage. */
    public static int arrowCritRollBound(int damage)
    {
        return damage / 2 + 2;
    }

    /** Mirrors the critical arrow branch of AbstractArrow.onHitEntity: {@code min(damage + roll, damage * 2)}. */
    public static int arrowCritDamage(int damage, int roll)
    {
        return Math.min(damage + roll, damage + damage);
    }

    /** Mirrors BowItem.getPowerForTime: the draw fraction of a bow held for the given number of ticks. */
    public static double bowPower(int drawTicks)
    {
        double f = drawTicks / 20.0;
        double power = (f * f + f * 2.0) / 3.0;
        return power > 1.0 ? 1.0 : power;
    }

    /** The launch speed of a bow shot at the given draw fraction, which the game scales by three. */
    public static double bowSpeed(double power)
    {
        return power * 3.0;
    }

    /** The inaccuracy of a bow shot at the given draw fraction. */
    public static double bowInaccuracy(double power)
    {
        return power;
    }

    /** True when BowItem.releaseUsing would fire, which needs a draw fraction of at least 0.1. */
    public static boolean bowFires(double power)
    {
        return power >= BOW_MIN_POWER;
    }

    /**
     * Mirrors ProjectileUtil.computeMargin, whose inner minimum caps every projectile at the same 0.3.
     */
    public static double splashMargin()
    {
        return 0.3;
    }
}