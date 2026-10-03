package carpet.pvp.look;

import java.util.Random;

/**
 * Tunable parameters of a {@link LookController}.
 *
 * @param sensitivity      mouse sensitivity setting in [0,1]
 * @param reactionMinMs    shortest reaction time before a movement starts
 * @param reactionMaxMs    longest reaction time before a movement starts
 * @param fittsA           Fitts intercept, seconds
 * @param fittsB           Fitts slope, seconds per bit
 * @param noiseCoeff       endpoint standard deviation as a fraction of the movement distance
 * @param maxDegPerTick    Euclidean rotation limit per tick, degrees
 * @param trackRangeDegrees distance within which a moving target is pursued without a reaction delay
 * @param pursuitLagMs     lag of the target-velocity estimate used while pursuing, milliseconds
 * @param pursuitGain      fraction of the remaining position error corrected per tick while pursuing
 * @param pursuitNoise     pursuit noise standard deviation as a fraction of the tracked angular speed
 */
public record LookProfile(
        float sensitivity,
        double reactionMinMs,
        double reactionMaxMs,
        double fittsA,
        double fittsB,
        double noiseCoeff,
        double maxDegPerTick,
        double trackRangeDegrees,
        double pursuitLagMs,
        double pursuitGain,
        double pursuitNoise)
{
    /** Profile with typical-skill pursuit parameters. */
    public LookProfile(float sensitivity, double reactionMinMs, double reactionMaxMs, double fittsA,
            double fittsB, double noiseCoeff, double maxDegPerTick, double trackRangeDegrees)
    {
        this(sensitivity, reactionMinMs, reactionMaxMs, fittsA, fittsB, noiseCoeff, maxDegPerTick,
                trackRangeDegrees, 100.0, 0.35, 0.12);
    }

    /** Milliseconds per game tick. */
    public static final double MS_PER_TICK = 50.0;

    /**
     * Profile for a given skill: 0 is a beginner, 0.5 a typical human, 1 an expert.
     * Reaction time, Fitts constants, noise, speed limit and pursuit parameters are interpolated linearly.
     */
    public static LookProfile ofSkill(double skill, float sensitivity)
    {
        double s = Math.max(0.0, Math.min(1.0, skill));
        return new LookProfile(
                sensitivity,
                lerp(250.0, 150.0, s),
                lerp(350.0, 220.0, s),
                lerp(0.20, 0.05, s),
                lerp(0.22, 0.08, s),
                lerp(0.10, 0.03, s),
                lerp(30.0, 75.0, s),
                4.0,
                lerp(150.0, 50.0, s),
                lerp(0.20, 0.50, s),
                lerp(0.20, 0.04, s));
    }

    private static double lerp(double a, double b, double t)
    {
        return a + (b - a) * t;
    }

    /** Smallest rotation step of the simulated mouse, degrees. */
    public double grid()
    {
        double v = 0.6 * sensitivity + 0.2;
        return v * v * v * 1.2;
    }

    /** Fitts movement time (Shannon form) in ticks, at least 1. */
    public int movementTicks(double distance, double width)
    {
        double w = Math.max(width, 1e-3);
        double mt = fittsA + fittsB * Math.log(Math.max(distance, 0.0) / w + 1.0) / Math.log(2.0);
        return Math.max(1, (int) Math.ceil(mt * 1000.0 / MS_PER_TICK));
    }

    /** Samples a reaction delay in ticks, uniformly from the profile range, at least 1. */
    public int reactionTicks(Random rng)
    {
        double ms = reactionMinMs + rng.nextDouble() * Math.max(0.0, reactionMaxMs - reactionMinMs);
        return Math.max(1, (int) Math.round(ms / MS_PER_TICK));
    }
}
