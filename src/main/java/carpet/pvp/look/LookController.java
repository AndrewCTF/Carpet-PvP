package carpet.pvp.look;

import java.util.Random;

/**
 * Human-like view rotation. Call {@link #aimAt} (or {@link #clearTarget}) then {@link #tick} once
 * per game tick, then read {@link #yaw}, {@link #pitch} and {@link #changed}. Allocation-free.
 *
 * <p>Model: reaction delay, Fitts's-law movement time, minimum-jerk trajectory, endpoint noise
 * proportional to distance, one corrective submovement, speed limit and mouse-grid quantisation.
 * A target that moves smoothly while the view is on or near it is pursued by velocity matching: the
 * smoothed aim-point velocity, delayed by the profile's pursuit lag, plus a proportional correction
 * of the position error and noise proportional to the tracked speed. A target that jumps, or is
 * far away, goes through reaction delay and a ballistic movement instead. Distances are in
 * (yaw * cos(pitch), pitch) degrees; the speed limit is the Euclidean per-tick rotation.
 */
public final class LookController
{
    private static final int IDLE = 0;
    private static final int REACT = 1;
    private static final int MOVE = 2;
    private static final int PURSUE = 3;
    /** Smoothing factor of the aim velocity estimate (about two ticks of averaging). */
    private static final double ALPHA = 0.5;
    /** Smoothed aim speed, degrees per tick, above which the target counts as moving. */
    private static final double MOVING_ON = 0.75;
    /** Smoothed aim speed below which pursuit may end. */
    private static final double MOVING_OFF = 0.25;
    private static final int STILL_TICKS = 3;
    private static final int HIST = 16;
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

    private boolean haveAim;
    private double prevAimYaw;
    private double prevAimPitch;
    private double estVy;
    private double estVp;
    private double estSpeed;
    private boolean jump;
    private final double[] histY = new double[HIST];
    private final double[] histP = new double[HIST];
    private int histIdx;
    private double delVy;
    private double delVp;
    private int stillTicks;

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
        haveAim = false;
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
        observeAim();
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
        else if (state == PURSUE)
        {
            pursue();
        }
    }

    /** Updates the smoothed aim velocity estimate and flags a jump of the aim point. */
    private void observeAim()
    {
        if (!haveAim)
        {
            haveAim = true;
            prevAimYaw = aimYaw;
            prevAimPitch = aimPitch;
            resetEstimate();
            return;
        }
        double dy = wrap(aimYaw - prevAimYaw);
        double dp = aimPitch - prevAimPitch;
        double step = angular(dy, dp, aimPitch, aimPitch);
        prevAimYaw = aimYaw;
        prevAimPitch = aimPitch;
        jump = step > 2.0 * (profile.trackRangeDegrees() + estSpeed);
        if (jump)
        {
            resetEstimate();
            return;
        }
        estVy += ALPHA * (dy - estVy);
        estVp += ALPHA * (dp - estVp);
        estSpeed = angular(estVy, estVp, aimPitch, aimPitch);
        histIdx = (histIdx + 1) & (HIST - 1);
        histY[histIdx] = estVy;
        histP[histIdx] = estVp;
        double lag = Math.min(profile.pursuitLagMs() / LookProfile.MS_PER_TICK, HIST - 2);
        int lo = (int) lag;
        double f = lag - lo;
        int i0 = (histIdx - lo) & (HIST - 1);
        int i1 = (histIdx - lo - 1) & (HIST - 1);
        delVy = histY[i0] * (1.0 - f) + histY[i1] * f;
        delVp = histP[i0] * (1.0 - f) + histP[i1] * f;
    }

    private void resetEstimate()
    {
        estVy = 0.0;
        estVp = 0.0;
        estSpeed = 0.0;
        delVy = 0.0;
        delVp = 0.0;
        for (int i = 0; i < HIST; i++)
        {
            histY[i] = 0.0;
            histP[i] = 0.0;
        }
    }

    /** One tick of velocity-matching pursuit: delayed velocity estimate, position correction, noise. */
    private void pursue()
    {
        double ex = wrap(aimYaw - viewYaw);
        double ep = aimPitch - viewPitch;
        double sigma = profile.pursuitNoise() * estSpeed;
        // The aim point has already moved this tick, so the velocity step is subtracted from the error.
        double cy = delVy + profile.pursuitGain() * (ex - delVy) + noise() * sigma;
        double cp = delVp + profile.pursuitGain() * (ep - delVp) + noise() * sigma;
        applyStep(cy, cp);
    }

    private void detect()
    {
        double vy = viewYaw + wrap(aimYaw - viewYaw);
        double fromView = angular(vy - viewYaw, aimPitch - viewPitch, viewPitch, aimPitch);
        if (!hasGoal)
        {
            if (fromView > radius)
            {
                startReaction();
                return;
            }
            hasGoal = true;
            goalYaw = vy;
            goalPitch = aimPitch;
        }
        if (jump)
        {
            if (fromView <= profile.trackRangeDegrees())
            {
                plan(false);
            }
            else
            {
                startReaction();
            }
            return;
        }
        if (state == PURSUE)
        {
            if (fromView > 3.0 * profile.trackRangeDegrees() + radius)
            {
                startReaction();
            }
            else if (estSpeed >= MOVING_OFF)
            {
                stillTicks = 0;
            }
            else if (++stillTicks >= STILL_TICKS)
            {
                state = IDLE;
                goalYaw = vy;
                goalPitch = aimPitch;
            }
            return;
        }
        if (estSpeed >= MOVING_ON)
        {
            if (fromView <= profile.trackRangeDegrees() + radius)
            {
                state = PURSUE;
                stillTicks = 0;
                goalYaw = vy;
                goalPitch = aimPitch;
            }
            else if (state == IDLE)
            {
                plan(false);
            }
            return;
        }
        double gy = goalYaw + wrap(aimYaw - goalYaw);
        boolean jumped = angular(gy - goalYaw, aimPitch - goalPitch, goalPitch, aimPitch) > radius;
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
        double dist = angular(ay - viewYaw, ap - viewPitch, viewPitch, ap);
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

        applyStep(wantYaw - viewYaw, wantPitch - viewPitch);
        if (elapsed >= duration)
        {
            state = IDLE;
            if (estSpeed >= MOVING_ON)
            {
                goalYaw = viewYaw + wrap(aimYaw - viewYaw);
                goalPitch = aimPitch;
            }
            else if (!isCorrection
                    && angular(goalYaw - viewYaw, goalPitch - viewPitch, viewPitch, goalPitch) > radius)
            {
                plan(true);
            }
        }
    }

    /**
     * Rotates the view by the requested change, rounded to whole mouse-grid steps, with the
     * Euclidean per-tick speed limit applied and the pitch kept within [-90, 90].
     */
    private void applyStep(double wantDy, double wantDp)
    {
        int ny = clamp((int) Math.rint(wantDy / grid), -maxSteps, maxSteps);
        int np = clamp((int) Math.rint(wantDp / grid), -maxSteps, maxSteps);
        double limit = profile.maxDegPerTick() / grid;
        double mag = Math.hypot(ny, np);
        if (mag > limit)
        {
            double k = limit / mag;
            ny = (int) (ny * k);
            np = (int) (np * k);
        }
        int lo = Math.max(-maxSteps, (int) Math.ceil((-90.0 - viewPitch) / grid - 1e-9));
        int hi = Math.min(maxSteps, (int) Math.floor((90.0 - viewPitch) / grid + 1e-9));
        np = clamp(np, lo, hi);
        if (ny != 0 || np != 0)
        {
            viewYaw += ny * grid;
            viewPitch += np * grid;
            changed = true;
        }
    }

    /** Angular size of a (yaw, pitch) difference, scaling yaw by the cosine of the mean pitch. */
    private static double angular(double dYaw, double dPitch, double pitchA, double pitchB)
    {
        return Math.hypot(dYaw * Math.cos(Math.toRadians(0.5 * (pitchA + pitchB))), dPitch);
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
