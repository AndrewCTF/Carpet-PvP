package carpet.pvp.sim;

/**
 * How often a bow shot that is aimed with a given error actually lands.
 *
 * <p>Two errors add up, both of them small and independent: the view of the shooter, which is quantised to
 * whole mouse steps and drifts while it pursues a moving target, and the spread of the projectile itself.
 * The game hands the launch direction to three independent {@code RandomSource.triangle} draws whose spread is
 * {@link ProjectileSim#INACCURACY_SCALE} times the draw power, so a full draw adds about four tenths of a degree
 * on each axis.</p>
 *
 * <p>The target is a box, so the shot has to land inside it: the angular half width of a player at a range is
 * {@code atan(0.3 / distance)} and the angular half height {@code atan(0.9 / distance)}. With a normally
 * distributed error on both axes the chance of landing inside is the product of the two one dimensional
 * chances, each of which is an error function.</p>
 */
public final class BowShot
{
    /** The half width of a player box, which is what the arrow has to cross side to side. */
    public static final double HALF_WIDTH = 0.3;
    /** The half height of a player box above its middle. */
    public static final double HALF_HEIGHT = 0.9;

    private BowShot()
    {
    }

    /**
     * The angular error a bow shot of the given draw power adds on each axis, one standard deviation, degrees.
     * {@code RandomSource.triangle(0, spread)} is the difference of two uniform draws, so it has the variance
     * {@code spread^2 / 6}.
     */
    public static double arrowSigmaDegrees(double power)
    {
        double spread = ProjectileSim.INACCURACY_SCALE * power;
        return Math.toDegrees(Math.sqrt(spread * spread / 6.0));
    }

    /**
     * The aim error a shot picks up from being let go as soon as the view is within a tolerance rather than
     * exactly on the point: the error is then spread evenly over that tolerance, which has the variance of a
     * uniform draw.
     */
    public static double releaseSigmaDegrees(double tolerance)
    {
        return Math.max(0.0D, tolerance) / Math.sqrt(12.0D);
    }

    /** Two independent errors of the same size on one axis, in quadrature. */
    public static double combined(double aimSigma, double arrowSigma)
    {
        return Math.sqrt(aimSigma * aimSigma + arrowSigma * arrowSigma);
    }

    /**
     * The chance that a shot whose total error is {@code sigmaDegrees} on each axis lands in a player standing
     * {@code distance} blocks away.
     */
    public static double hitChance(double sigmaDegrees, double distance)
    {
        if (distance <= 0.0)
        {
            return 1.0;
        }
        double across = Math.toDegrees(Math.atan2(HALF_WIDTH, distance));
        double up = Math.toDegrees(Math.atan2(HALF_HEIGHT, distance));
        return within(sigmaDegrees, across) * within(sigmaDegrees, up);
    }

    /**
     * The chance that a shot at a player {@code distance} blocks away lands with the error of the shooter's own
     * aim plus the spread of the shot itself, at the given draw power.
     */
    public static double hitChance(double aimSigmaDegrees, double distance, double power)
    {
        return hitChance(combined(aimSigmaDegrees, arrowSigmaDegrees(power)), distance);
    }

    /** Chance that a normally distributed error of {@code sigma} stays inside half an angle. */
    private static double within(double sigma, double halfAngleDegrees)
    {
        if (sigma <= 0.0)
        {
            return 1.0;
        }
        return erf(halfAngleDegrees / (sigma * Math.sqrt(2.0)));
    }

    /**
     * The error function, after Abramowitz and Stegun 7.1.26. The largest error of that approximation is
     * about 1.5e-7, which is far below anything a bot's aim is known to.
     */
    public static double erf(double x)
    {
        double sign = x < 0.0 ? -1.0 : 1.0;
        double a = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * a);
        double poly = (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t;
        return sign * (1.0 - poly * Math.exp(-a * a));
    }
}
