package carpet.pvp;

import java.util.ArrayList;
import java.util.List;

/**
 * Counters of what a bot's body actually did, so that commands, tests and the self-test can assert
 * on behaviour instead of guessing at it. One instance per bot, reset by the combat brain.
 */
public final class BotStats
{
    /**
     * What one click was worth. A click only counts as a {@link #HIT} when the game turned it into
     * a real attack: the target in the attack range, a ray from the eyes along the view the bot had
     * meeting the target, and the swing charged past the gate that guards crits and sprint hits.
     * Every other click is a miss, and says which of the three it failed.
     */
    public enum ClickOutcome { HIT, OUT_OF_REACH, OFF_AIM, UNCHARGED }

    /**
     * One click and what it was worth, kept so a scenario can say why a bot missed: how far away the target
     * was, how charged the swing was, and how far the view was off the target sideways and up and down.
     */
    public record Click(long tick, double distance, float charge, double yawError, double pitchError,
            ClickOutcome outcome)
    {
    }

    /** How many of the last clicks are kept for the report. */
    public static final int CLICK_LOG = 12;

    /** Clicks the click limiter let through and the body threw. */
    public int clicks;
    /** Clicks the game turned into a real hit on the target. */
    public int hits;
    /** Clicks that were not worth anything: {@code missesOutOfReach + missesOffAim + missesUncharged}. */
    public int misses;
    /** Misses because the target was further away than the attack range. */
    public int missesOutOfReach;
    /** Misses because the view the bot had was not pointed at the target. */
    public int missesOffAim;
    /** Misses because the swing was thrown before the charge gate. */
    public int missesUncharged;
    private final Click[] log = new Click[CLICK_LOG];
    private int logNext;
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
    /** End crystals put down. */
    public int crystalsPlaced;
    /** Blocks put down, bridging a base cell or blocking off a blast. */
    public int blocksPlaced;
    /** Respawn anchors put down. */
    public int anchorsPlaced;
    /** Blasts set off, whichever kind. */
    public int blasts;
    /** Of those, respawn anchors. */
    public int anchorsBlown;
    /** Blasts the model said would cost the bot a totem or its life, so it walked away from them. */
    public int refusedBlasts;
    /** Ticks the bot spent stepping out of a blast it was standing in. */
    public int backedOff;
    /** Fresh totems moved into the offhand. */
    public int reTotems;
    /** Ender pearls thrown. */
    public int pearlsThrown;
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

    /** Share of the clicks that were worth a hit, in percent; what a strong sword player lands. */
    public int hitRate()
    {
        return clicks == 0 ? 0 : (int) Math.round(100.0 * hits / clicks);
    }

    /** Books one click and books it under the counter of why it was what it was. */
    public void recordClick(long tick, double distance, float charge, double yawError, double pitchError,
            ClickOutcome outcome)
    {
        log[logNext] = new Click(tick, distance, charge, yawError, pitchError, outcome);
        logNext = (logNext + 1) % CLICK_LOG;
        if (outcome == ClickOutcome.HIT)
        {
            hits++;
            return;
        }
        misses++;
        switch (outcome)
        {
            case OUT_OF_REACH -> missesOutOfReach++;
            case OFF_AIM -> missesOffAim++;
            case UNCHARGED -> missesUncharged++;
            default -> { }
        }
    }

    /** The last clicks, oldest first, at most {@value #CLICK_LOG} of them. */
    public List<Click> clickLog()
    {
        int count = Math.min(CLICK_LOG, clicks);
        // Once the log has rolled over the oldest click is the one the next record goes over.
        int start = count < CLICK_LOG ? 0 : logNext;
        List<Click> out = new ArrayList<>(CLICK_LOG);
        for (int i = 0; i < count; i++)
        {
            out.add(log[(start + i) % CLICK_LOG]);
        }
        return out;
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
        missesOutOfReach = 0;
        missesOffAim = 0;
        missesUncharged = 0;
        logNext = 0;
        java.util.Arrays.fill(log, null);
        crits = 0;
        sprintHits = 0;
        throttledClicks = 0;
        blockTicks = 0;
        shieldBreaks = 0;
        crystalsPlaced = 0;
        blocksPlaced = 0;
        anchorsPlaced = 0;
        blasts = 0;
        anchorsBlown = 0;
        refusedBlasts = 0;
        backedOff = 0;
        reTotems = 0;
        pearlsThrown = 0;
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
        return "clicks=" + clicks + " hits=" + hits + " misses=" + misses
                + "[reach=" + missesOutOfReach + " aim=" + missesOffAim + " charge=" + missesUncharged + "]"
                + " hitRate=" + hitRate() + "%"
                + " crits=" + crits
                + " sprintHits=" + sprintHits + " throttled=" + throttledClicks
                + " shieldBreaks=" + shieldBreaks + " blockTicks=" + blockTicks
                + " blasts[" + crystalsPlaced + " crystals, " + anchorsPlaced + " anchors, " + blasts
                + " blown (" + anchorsBlown + " anchors), " + refusedBlasts + " refused, " + backedOff
                + " backed off, " + blocksPlaced + " blocks]"
                + " totems=" + reTotems + " pearls=" + pearlsThrown
                + " dealt=" + round(damageDealt) + " taken=" + round(damageTaken)
                + " planner=" + plannerCalls + "/" + simulatedTicks + " ticks starved=" + starvedTicks
                + " rotation[onGrid=" + rotationOnGrid() + " maxStep=" + round(maxRotationStep)
                + "deg offGrid=" + offGridRotationSteps + " first=" + round(offGridYawStep) + "/"
                + round(offGridPitchStep) + "]";
    }

    /** The last clicks as one line, for the reports that have to say why a bot missed. */
    public String clickLogLine()
    {
        StringBuilder out = new StringBuilder();
        for (Click click : clickLog())
        {
            if (out.length() > 0)
            {
                out.append(", ");
            }
            out.append(String.format(java.util.Locale.ROOT, "t%d %.2fm %.0f%% yaw %.1f pitch %.1f %s",
                    click.tick(), click.distance(), 100.0F * click.charge(), click.yawError(),
                    click.pitchError(), click.outcome()));
        }
        return out.toString();
    }

    private static String round(double value)
    {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}