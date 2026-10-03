package carpet.pvp.look;

import java.util.Random;

/**
 * Human-like view rotation. Call {@link #aimAt} (or {@link #clearTarget}) then {@link #tick} once
 * per game tick, then read {@link #yaw}, {@link #pitch} and {@link #changed}. Allocation-free.
 *
 * <p>Model: reaction delay, Fitts's-law movement time, minimum-jerk trajectory, endpoint noise
 * proportional to distance, one corrective submovement, delay-free tracking of nearby targets,
 * speed limit and mouse-grid quantisation. Distances are Euclidean in (yaw, pitch) degrees.
 */
public final class LookController
{
    private static final int IDLE = 0;
    private static final int REACT = 1;
    private static final int MOVE = 2;
    /** Peak speed of a minimum-jerk movement is this multiple of the mean speed. */
    private static final double PEAK_FACTOR = 1.875;

    private final LookProfile profile;
    private final Random rng;
    private final double grid;
    private final int maxSteps;

    private double viewYaw;
    private double viewPitch;
    private boolean changed;

    private boolean hasTarget;
    private double aimYaw;
    private double aimPitch;
    private double radius = 0.5;

    private int state = IDLE;
    private int reactLeft;
    private boolean hasGoal;
    private double goalYaw;
    private double goalPitch;

    private double startYaw;
    private double startPitch;
    private double deltaYaw;
    private double deltaPitch;
    private int duration = 1;
    private int elapsed;
    private boolean isCorrection;
    private int corrections;

    public LookController(LookProfile profile, Random rng)
    {
        this.profile = profile;
        this.rng = rng;
        this.grid = profile.grid();
        this.maxSteps = Math.max(1, (int) Math.floor(profile.maxDegPerTick() / grid + 1e-9));
    }

    /** Sets the current view and drops any target and movement. */
    public void reset(float yaw, float pitch)
    {
        viewYaw = yaw;
        viewPitch = clampPitch(pitch);
        changed = false;
        clearTarget();
        corrections = 0;
    }

    /** Sets the desired aim point; the target is a circle of the given angular radius, degrees. */
    public void aimAt(float yaw, float pitch, float targetAngularRadiusDegrees)
    {
        hasTarget = true;
        aimYaw = yaw;
        aimPitch = clampPitch(pitch);
        radius = Math.max(targetAngularRadiusDegrees, 1e-3);
    }

    /** Removes the target; the view stops where it is. */
    public void clearTarget()
    {
        hasTarget = false;
        hasGoal = false;
        state = IDLE;
    }

    public float yaw()
    {
        return (float) viewYaw;
    }

    public float pitch()
    {
        return (float) viewPitch;
    }

    /** True if the last {@link #tick} moved the view on either axis. */
    public boolean changed()
    {
        return changed;
    }

    /** True while a reaction delay or movement is in progress. */
    public boolean busy()
    {
        return state != IDLE;
    }

    /** Number of corrective submovements planned since the last reset. */
    public int correctionsPlanned()
    {
        return corrections;
    }

    /** Advances the controller by one game tick. */
    public void tick()
    {
        changed = false;
        if (!hasTarget)
        {
            return;
        }
        if (state != REACT)
        {
            detect();
        }
        if (state == REACT)
        {
            if (reactLeft > 0)
            {
                reactLeft--;
                return;
            }
            plan(false);
        }
        if (state == MOVE)
        {
            advance();
        }
    }

    private void detect()
    {
        if (!hasGoal)
        {
            startReaction();
            return;
        }
        double gy = goalYaw + wrap(aimYaw - goalYaw);
        boolean jumped = Math.hypot(gy - goalYaw, aimPitch - goalPitch) > radius;
        double vy = viewYaw + wrap(aimYaw - viewYaw);
        double fromView = Math.hypot(vy - viewYaw, aimPitch - viewPitch);
        if (jumped)
        {
            if (fromView <= profile.trackRangeDegrees())
            {
                plan(false);
            }
            else
            {
                startReaction();
            }
        }
        else if (state == IDLE && fromView > radius)
        {
            plan(false);
        }
    }

    private void startReaction()
    {
        state = REACT;
        hasGoal = true;
        goalYaw = viewYaw + wrap(aimYaw - viewYaw);
        goalPitch = aimPitch;
        reactLeft = profile.reactionTicks(rng);
    }

    /** Plans a movement from the current view to the current aim point, with endpoint noise. */
    private void plan(boolean correction)
    {
        double ay = viewYaw + wrap(aimYaw - viewYaw);
        double ap = aimPitch;
        double dist = Math.hypot(ay - viewYaw, ap - viewPitch);
        double sigma = profile.noiseCoeff() * dist;
        double ey = ay + noise() * sigma;
        double ep = clampPitch(ap + noise() * sigma);

        startYaw = viewYaw;
        startPitch = viewPitch;
        deltaYaw = ey - viewYaw;
        deltaPitch = ep - viewPitch;
        double len = Math.hypot(deltaYaw, deltaPitch);
        int ticks = profile.movementTicks(dist, 2.0 * radius);
        ticks = Math.max(ticks, (int) Math.ceil(PEAK_FACTOR * len / profile.maxDegPerTick()));
        duration = Math.max(1, ticks);
        elapsed = 0;
        goalYaw = ay;
        goalPitch = ap;
        hasGoal = true;
        isCorrection = correction;
        if (correction)
        {
            corrections++;
        }
        state = MOVE;
    }

    private double noise()
    {
        double n = rng.nextGaussian();
        return n > 3.0 ? 3.0 : n < -3.0 ? -3.0 : n;
    }

    private void advance()
    {
        elapsed++;
        double tau = Math.min(1.0, (double) elapsed / duration);
        double t2 = tau * tau;
        double f = t2 * tau * (10.0 - 15.0 * tau + 6.0 * t2);
        double wantYaw = startYaw + f * deltaYaw;
        double wantPitch = startPitch + f * deltaPitch;

        int ny = clamp((int) Math.rint((wantYaw - viewYaw) / grid), -maxSteps, maxSteps);
        int np = (int) Math.rint((wantPitch - viewPitch) / grid);
        int lo = Math.max(-maxSteps, (int) Math.ceil((-90.0 - viewPitch) / grid - 1e-9));
        int hi = Math.min(maxSteps, (int) Math.floor((90.0 - viewPitch) / grid + 1e-9));
        np = clamp(np, lo, hi);

        if (ny != 0 || np != 0)
        {
            viewYaw += ny * grid;
            viewPitch += np * grid;
            changed = true;
        }
        if (elapsed >= duration)
        {
            state = IDLE;
            if (!isCorrection && Math.hypot(goalYaw - viewYaw, goalPitch - viewPitch) > radius)
            {
                plan(true);
            }
        }
    }

    private static int clamp(int v, int lo, int hi)
    {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double clampPitch(double p)
    {
        return p < -90.0 ? -90.0 : Math.min(p, 90.0);
    }

    /** Wraps an angle difference into [-180, 180]. */
    private static double wrap(double d)
    {
        return d - 360.0 * Math.rint(d / 360.0);
    }
}
