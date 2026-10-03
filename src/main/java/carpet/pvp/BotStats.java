package carpet.pvp;

/**
 * Counters of what a bot's body actually did, so that commands, tests and the self-test can assert
 * on behaviour instead of guessing at it. One instance per bot, reset by the combat brain.
 */
public final class BotStats
{
    /** Click requests the style asked for. */
    public int clicks;
    /** Clicks that passed the reach and aim checks and were handed to the vanilla attack. */
    public int hits;
    /** Clicks that were swung at nothing, or at something out of reach. */
    public int misses;
    /** Hits that landed with the crit conditions of {@code Player.canCriticalAttack} met. */
    public int crits;
    /** Hits that landed while sprinting, the ones that carry the extra knockback. */
    public int sprintHits;
    /** Clicks dropped by the clicks-per-second limit. */
    public int throttledClicks;
    /** Ticks with a raised shield. */
    public int blockTicks;
    /** Target shields disabled by an axe hit. */
    public int shieldBreaks;
    public double damageDealt;
    public double damageTaken;
    /** Planner calls that spent simulated ticks. */
    public int plannerCalls;
    /** Simulated ticks the planner spent. */
    public int simulatedTicks;
    /** Ticks the style wanted to plan but had no share of the simulation budget. */
    public int starvedTicks;
    /** Rotation steps that were not a whole number of mouse-grid steps. */
    public int offGridRotationSteps;
    /** The first rotation step that was off the grid, for finding out where it came from. */
    public double offGridYawStep;
    public double offGridPitchStep;
    /** Largest rotation step applied in a tick, degrees. */
    public double maxRotationStep;

    /** True if every recorded rotation step was on the mouse grid. */
    public boolean rotationOnGrid()
    {
        return offGridRotationSteps == 0;
    }

    /** Records the rotation the look controller applied this tick. */
    public void recordRotationStep(double yawStep, double pitchStep, double grid)
    {
        double step = Math.hypot(yawStep, pitchStep);
        if (step > maxRotationStep)
        {
            maxRotationStep = step;
        }
        if (!onGrid(yawStep, grid) || !onGrid(pitchStep, grid))
        {
            if (offGridRotationSteps == 0)
            {
                offGridYawStep = yawStep;
                offGridPitchStep = pitchStep;
            }
            offGridRotationSteps++;
        }
    }

    /** How far off the grid a rotation may be and still count as on it, degrees. */
    private static final double GRID_TOLERANCE = 1.0E-4;

    /**
     * True if the angle is a whole number of grid steps. The view is stored as a float while the grid is
     * a double, so reading a rotation back costs a little precision; the tolerance is orders of magnitude
     * above that and far below any step the mouse could actually produce.
     */
    public static boolean onGrid(double degrees, double grid)
    {
        if (grid <= 0.0)
        {
            return false;
        }
        return Math.abs(degrees - Math.rint(degrees / grid) * grid) < GRID_TOLERANCE;
    }

    public void clear()
    {
        clicks = 0;
        hits = 0;
        misses = 0;
        crits = 0;
        sprintHits = 0;
        throttledClicks = 0;
        blockTicks = 0;
        shieldBreaks = 0;
        damageDealt = 0.0;
        damageTaken = 0.0;
        plannerCalls = 0;
        simulatedTicks = 0;
        starvedTicks = 0;
        offGridRotationSteps = 0;
        offGridYawStep = 0.0;
        offGridPitchStep = 0.0;
        maxRotationStep = 0.0;
    }

    public String describe()
    {
        return "clicks=" + clicks + " hits=" + hits + " misses=" + misses + " crits=" + crits
                + " sprintHits=" + sprintHits + " throttled=" + throttledClicks
                + " shieldBreaks=" + shieldBreaks + " blockTicks=" + blockTicks
                + " dealt=" + round(damageDealt) + " taken=" + round(damageTaken)
                + " planner=" + plannerCalls + "/" + simulatedTicks + " ticks starved=" + starvedTicks
                + " rotation[onGrid=" + rotationOnGrid() + " maxStep=" + round(maxRotationStep)
                + "deg offGrid=" + offGridRotationSteps + " first=" + round(offGridYawStep) + "/"
                + round(offGridPitchStep) + "]";
    }

    private static String round(double value)
    {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}