package carpet.pvp.nav;

import net.minecraft.server.MinecraftServer;

import carpet.pvp.nav.BudgetedSearch.Status;

/**
 * How much pathfinding the whole server is allowed to do in one tick, and what it actually did.
 *
 * <p>Every search - a bot's own A*, or the flow field several bots share - goes through {@link #run}, so two
 * rules hold at once: no single bot may expand more than its share of the tick, and all of them together may not
 * expand more than the shared cap. A search that runs out of budget keeps its state and finishes on a later
 * tick, so a long path costs a long time rather than a stalled server.
 *
 * <p>The counters are what the self-test reads to check the caps held. They describe the tick just gone, and are
 * complete once every bot has been ticked.</p>
 */
public final class NavSearchBudget
{
    private static MinecraftServer owner;
    private static long tick = Long.MIN_VALUE;
    private static int spent;
    private static int total;
    private static int maxBot;
    private static int perBotCap;
    private static int sharedCap;
    private static int peakTotal;
    private static int peakBot;

    private NavSearchBudget()
    {
    }

    /**
     * Gives {@code step} at most the smaller of this tick's per-bot cap and what is left of the shared cap, then
     * books whatever it used against both.
     */
    public static Status run(MinecraftServer server, int perBot, int shared, BudgetedSearch runner,
            BudgetedSearch.Step step)
    {
        beginTick(server, perBot, shared);
        Status status = runner.run(step, Math.min(perBotCap, Math.max(0, sharedCap - spent)));
        int used = runner.expansions();
        spent += used;
        total += used;
        if (used > maxBot)
        {
            maxBot = used;
            peakBot = Math.max(peakBot, used);
        }
        return status;
    }

    /**
     * The same for a search that is not one bot's own - the field a crowd of chasers shares, which is charged to
     * the shared cap alone and is not any single bot's share of a tick.
     */
    public static Status runShared(MinecraftServer server, int shared, BudgetedSearch runner,
            BudgetedSearch.Step step)
    {
        beginTick(server, 0, shared);
        Status status = runner.run(step, Math.max(0, sharedCap - spent));
        spent += runner.expansions();
        total += runner.expansions();
        return status;
    }

    /** Node expansions every bot together made in the last tick. */
    public static int expansionsLastTick()
    {
        return total;
    }

    /** The most node expansions any single bot made in the last tick. */
    public static int maxBotExpansionsLastTick()
    {
        return maxBot;
    }

    /** The most node expansions all the bots together have made in any one tick since the server started. */
    public static int peakExpansions()
    {
        peakTotal = Math.max(peakTotal, total);
        return peakTotal;
    }

    /** The most node expansions one bot has made in any one tick since the server started. */
    public static int peakBotExpansions()
    {
        peakBot = Math.max(peakBot, maxBot);
        return peakBot;
    }

    /** The per-bot cap that was in force in the last tick. */
    public static int perBotCap()
    {
        return perBotCap;
    }

    /** The shared cap that was in force in the last tick. */
    public static int sharedCap()
    {
        return sharedCap;
    }

    /** Starts the count again, for a server that has just started. */
    public static void reset()
    {
        owner = null;
        tick = Long.MIN_VALUE;
        spent = 0;
        total = 0;
        maxBot = 0;
        perBotCap = 0;
        sharedCap = 0;
        peakTotal = 0;
        peakBot = 0;
    }

    private static void beginTick(MinecraftServer server, int perBot, int shared)
    {
        if (server != owner)
        {
            reset();
            owner = server;
        }
        long now = server.overworld().getGameTime();
        if (now != tick)
        {
            tick = now;
            spent = 0;
            total = 0;
            maxBot = 0;
        }
        if (perBot > 0)
        {
            perBotCap = perBot;
        }
        sharedCap = shared;
    }
}
