package carpet.pvp;

/**
 * The simulated-tick budget every bot of the server shares within one server tick.
 *
 * <p>{@link #beginTick(int)} is called once per server tick before any bot ticks, so the whole
 * budget is available again from a known point and every fighter of the tick gets the same share of
 * it. The number of fighters of the previous tick decides how the budget is split, since the bots
 * of a tick are not all known before the first one asks. What one bot may spend is capped by what is
 * still left as well, so the total can never be exceeded even when the fighter count grows in the
 * middle of a tick.</p>
 */
public final class BotBudget
{
    private static final BotBudget INSTANCE = new BotBudget();

    public BotBudget()
    {
    }

    private int total;
    private int share;
    private int used;
    private int fighters;
    private int lastUsed;
    private int lastFighters;
    private int expectedFighters = 1;

    public static BotBudget instance()
    {
        return INSTANCE;
    }

    /**
     * Opens a new server tick with the given number of simulated ticks to spend. The fighter count of
     * the tick that just ended becomes the basis of the split, since the bots of this tick are not all
     * known before the first one asks.
     */
    public void beginTick(int totalTicks)
    {
        if (fighters > 0)
        {
            expectedFighters = fighters;
        }
        // The numbers of the tick that just ended, which is what an outside observer sees: the bots of
        // the new tick have not asked for anything yet.
        lastUsed = used;
        lastFighters = fighters;
        total = Math.max(0, totalTicks);
        used = 0;
        fighters = 0;
        share = total / expectedFighters;
    }

    /** Registers a bot that is in a fight this tick and returns its share of what is left. */
    public int join()
    {
        fighters++;
        return share();
    }

    /** Simulated ticks this bot may spend this tick. */
    public int share()
    {
        return Math.max(0, Math.min(share, total - used));
    }

    /** Books simulated ticks against this tick's budget. */
    public void spend(int ticks)
    {
        used += ticks;
    }

    public int total()
    {
        return total;
    }

    public int used()
    {
        return used;
    }

    /** Fighters that asked for a share in the tick just closed. */
    public int fighters()
    {
        return fighters;
    }

    /** Simulated ticks spent in the server tick before the one just opened. */
    public int usedLastTick()
    {
        return lastUsed;
    }

    /** Fighters that shared the server tick before the one just opened. */
    public int fightersLastTick()
    {
        return lastFighters;
    }

    /** Fighters of the tick before the current one; the basis of the current split. */
    public int expectedFighters()
    {
        return expectedFighters;
    }
}