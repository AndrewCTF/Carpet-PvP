package carpet.pvp.crystal;

import carpet.pvp.sim.DoubleTap;

/**
 * The decisions of the crystal game that need no world to make: how far along putting one blast down is,
 * whether that blast may be set off, when to hit it around the damage window, and where the totems go.
 *
 * <p>Everything here is arithmetic on what {@code carpet.pvp.sim} worked out, so the unit tests can
 * drive every branch without a server. The style turns the answers into actions through
 * {@link CrystalHand}.</p>
 */
public final class CrystalTactics
{
    /** What the bot still has to do to the placement it picked. */
    public enum Stage { NONE, BRIDGE, PLACE, CHARGE, READY }

    /** What the bot may do about a blast that is on the ground and about to be set off. */
    public enum Blast { GO, TRADE, BLOCK_OFF, BACK_OFF, LEAVE }

    /** Glowstone a respawn anchor needs before it explodes. */
    public static final int ANCHOR_CHARGES = 4;
    /** Ticks a bot stays on a placement that is not coming along before it looks for another one. */
    public static final int PATIENCE = 60;
    /** Ticks a bot waits after popping a totem before it moves another one into the offhand. */
    public static final int NO_POP = 1000;

    private final int planInterval;
    private Stage stage = Stage.NONE;
    private int charge;
    private int ticksToPlan;
    private int onThis;
    private int ticksSincePop = NO_POP;
    private int ticksSinceMeleeHit = NO_POP;

    public CrystalTactics(int planInterval)
    {
        this.planInterval = Math.max(1, planInterval);
        this.ticksToPlan = 0;
    }

    /**
     * The stage a fresh placement starts in: a crystal whose base cell is still empty starts with the
     * obsidian that fills it, everything else starts by putting the blast down.
     */
    public static Stage opening(boolean bridge)
    {
        return bridge ? Stage.BRIDGE : Stage.PLACE;
    }

    /**
     * The stage one click on from {@code stage}. A respawn anchor is placed, charged with glowstone and
     * then set off; an end crystal is placed and then set off, so it is ready as soon as it is down.
     */
    public static Stage after(Stage stage, boolean anchor, int charges)
    {
        return switch (stage)
        {
            case BRIDGE -> Stage.PLACE;
            case PLACE -> anchor ? Stage.CHARGE : Stage.READY;
            case CHARGE -> charges >= ANCHOR_CHARGES ? Stage.READY : Stage.CHARGE;
            default -> Stage.NONE;
        };
    }

    /**
     * What to do about a blast that is on the ground and about to be hit.
     *
     * @param canBlock   whether the bot has obsidian and a free cell to put between itself and the blast
     * @param canBackOff whether the bot has ground to step onto that the blast cannot see
     */
    public static Blast judge(float selfDamage, float selfHealth, float targetDamage, float targetHealth,
            boolean targetTotem, boolean canBlock, boolean canBackOff)
    {
        if (selfDamage < selfHealth)
        {
            return Blast.GO;
        }
        // Losing the exchange is only worth it when the other side does not walk away from it.
        if (targetDamage >= targetHealth && !targetTotem)
        {
            return Blast.TRADE;
        }
        if (canBlock)
        {
            return Blast.BLOCK_OFF;
        }
        return canBackOff ? Blast.BACK_OFF : Blast.LEAVE;
    }

    /**
     * Ticks to wait after a melee hit before a blast of {@code incoming} lands for its full amount.
     *
     * <p>Inside the damage window a second hit only applies the excess over the last one, so a crystal
     * worth less than the sword hit that went in first is better held until the window closes. A blast
     * bigger than the first hit is not worth waiting for.</p>
     *
     * @param ticksSinceHit ticks since the melee hit landed
     */
    public static int doubleTapDelay(float incoming, float firstHit, int ticksSinceHit)
    {
        int window = DoubleTap.earliestFullDamageTick();
        if (ticksSinceHit >= window || incoming >= firstHit)
        {
            return 0;
        }
        return window - ticksSinceHit;
    }

    /**
     * Whether a totem belongs in the main hand: the bot carries a second one and the blast it is
     * standing in would finish it, so popping the offhand totem would leave nothing in hand for the
     * blast behind that one.
     */
    public static boolean handTotemWanted(boolean secondTotemCarried, boolean blastWouldFinish)
    {
        return secondTotemCarried && blastWouldFinish;
    }

    /** Ticks before the bot may move a fresh totem into the offhand again. */
    public int ticksToReTotem(int delay)
    {
        return Math.max(0, delay - ticksSincePop);
    }

    /** Ticks since the bot last landed a melee hit on its target. */
    public int ticksSinceMeleeHit()
    {
        return ticksSinceMeleeHit;
    }

    public Stage stage()
    {
        return stage;
    }

    /** How many of the anchor's charges are in it, as the last look at the block reported. */
    public int charges()
    {
        return charge;
    }

    /** Starts a placement at the given stage, with {@code charges} of an anchor already in it. */
    public void begin(Stage stage, int charges)
    {
        if (stage != this.stage)
        {
            onThis = 0;
        }
        this.stage = stage;
        this.charge = charges;
    }

    /**
     * Counts a tick on the placement under way. A click that never lands, a crystal the opponent keeps
     * knocking out of reach or a line of sight that never opens would otherwise hold the bot on one spot for
     * ever, so after so many ticks it is told to look for another one.
     */
    public void onTick()
    {
        onThis++;
    }

    /** True when the bot has been on this placement for longer than it is worth. */
    public boolean stale()
    {
        return onThis > PATIENCE;
    }

    /** Forgets the placement the bot was working on. */
    public void reset()
    {
        stage = Stage.NONE;
        charge = 0;
        onThis = 0;
    }

    /** Books a melee hit on the target, which opens the damage window the crystals are timed around. */
    public void onMeleeHit()
    {
        ticksSinceMeleeHit = 0;
    }

    /** Counts a totem pop, which starts the wait before another one may be moved into the offhand. */
    public void onPop()
    {
        ticksSincePop = 0;
    }

    /** True once the bot may look for a new placement again. */
    public boolean planDue()
    {
        return ticksToPlan <= 0;
    }

    /** Restarts the wait between two searches. */
    public void planned()
    {
        ticksToPlan = planInterval;
    }

    /** Counts one tick. Call once per game tick, before the style asks anything. */
    public void tick()
    {
        if (ticksSincePop < NO_POP)
        {
            ticksSincePop++;
        }
        if (ticksSinceMeleeHit < NO_POP)
        {
            ticksSinceMeleeHit++;
        }
        if (ticksToPlan > 0)
        {
            ticksToPlan--;
        }
    }
}
