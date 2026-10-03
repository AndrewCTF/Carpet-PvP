package carpet.pvp.crystal;

import carpet.pvp.BotPvpConfig;

/**
 * Which parts of the crystal game a bot plays, from its difficulty preset and the options its owner set
 * with {@code /bot option <name> crystal.<option> <value>}.
 *
 * <p>A technique a difficulty does not teach is off whatever the options say, so a beginner never gets
 * a bridge or an anchor no matter how it is configured; the option then takes the technique away from a
 * bot that would otherwise have had it. Pure: numbers in, booleans out.</p>
 */
public final class CrystalTechniques
{
    /** Placing end crystals at all; every other technique hangs off this one. */
    public final boolean crystals;
    /** Filling an empty base cell with obsidian so a crystal can stand on it. */
    public final boolean bridges;
    /** Placing, charging and detonating a respawn anchor. */
    public final boolean anchors;
    /** Chasing a target that pearls away, and pearling out of a hole. */
    public final boolean pearls;
    /** A sword hit for knockback, then the crystals timed around the damage window. */
    public final boolean doubleTap;
    /** A second totem in the main hand when two blasts are coming inside the window. */
    public final boolean handTotem;
    /** Moving a fresh totem into the offhand after a pop. */
    public final boolean reTotem;
    /** Ticks a bot waits after popping a totem before it moves another one into the offhand. */
    public final int reTotemDelay;
    /** Ticks between two searches for a placement; an expert looks again every tick. */
    public final int planInterval;
    /** Half-width, half-height and half-depth of the volume the search looks at, in blocks. */
    public final int halfX;
    public final int halfY;
    public final int halfZ;

    public CrystalTechniques(BotPvpConfig cfg)
    {
        this(cfg.difficulty, cfg.combatStyle == BotPvpConfig.CombatStyle.ANCHOR, cfg.flag("crystal.crystals"),
                cfg.flag("crystal.obsidian"), cfg.flag("crystal.anchors"), cfg.flag("crystal.pearls"),
                cfg.flag("crystal.doubletap"), cfg.flag("crystal.hand_totem"), cfg.flag("crystal.retotem"),
                cfg.number("crystal.retotem_delay"));
    }

    public CrystalTechniques(BotPvpConfig.Difficulty difficulty, boolean anchorStyle, boolean crystals,
            boolean obsidian, boolean anchors, boolean pearls, boolean doubleTap, boolean handTotem,
            boolean reTotem, double reTotemDelay)
    {
        int level = difficulty.ordinal();
        this.crystals = crystals;
        this.bridges = crystals && obsidian && level >= BotPvpConfig.Difficulty.SKILLED.ordinal();
        this.anchors = crystals && anchors && anchorStyle && level >= BotPvpConfig.Difficulty.EXPERT.ordinal();
        this.pearls = crystals && pearls && level >= BotPvpConfig.Difficulty.AVERAGE.ordinal();
        this.doubleTap = crystals && doubleTap && level >= BotPvpConfig.Difficulty.SKILLED.ordinal();
        this.handTotem = crystals && handTotem && level >= BotPvpConfig.Difficulty.EXPERT.ordinal();
        this.reTotem = crystals && reTotem;
        this.reTotemDelay = (int) Math.max(0.0, Math.round(reTotemDelay));
        // A harder bot re-reads the world sooner and over more of it, so its crystal goes down before the
        // opponent has moved out from under it. A beginner only looks at the three blocks around its feet.
        Pace pace = switch (difficulty)
        {
            case BEGINNER -> new Pace(5, 2, 1, 2);
            case CASUAL -> new Pace(4, 3, 1, 3);
            case AVERAGE -> new Pace(3, 4, 2, 4);
            case SKILLED -> new Pace(2, 5, 2, 5);
            case EXPERT -> new Pace(1, 6, 2, 6);
        };
        this.planInterval = pace.interval;
        this.halfX = pace.halfX;
        this.halfY = pace.halfY;
        this.halfZ = pace.halfZ;
    }

    /** How often and how far a difficulty looks for a placement. */
    private record Pace(int interval, int halfX, int halfY, int halfZ) {}

    /** True when nothing but plain crystals is on, which is all a beginner plays. */
    public boolean plain()
    {
        return !bridges && !anchors && !pearls && !doubleTap && !handTotem;
    }

    /** True when nothing at all is on, so the style should not even look for a crystal. */
    public boolean none()
    {
        return !crystals;
    }

    /** True when the two hold the same techniques, so the style does not have to be rebuilt. */
    public boolean sameAs(CrystalTechniques other)
    {
        return other != null && crystals == other.crystals && bridges == other.bridges
                && anchors == other.anchors && pearls == other.pearls && doubleTap == other.doubleTap
                && handTotem == other.handTotem && reTotem == other.reTotem
                && reTotemDelay == other.reTotemDelay && planInterval == other.planInterval;
    }
}
