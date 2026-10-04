package carpet.pvp.drill;

import carpet.pvp.BotPvpConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Aim: a bot that walks sideways at a fixed distance and never hits back, for as many clicks as the
 * player cares to make. The score is the share of those clicks that landed.
 */
final class AimDrill implements Drill
{
    /** The distance the bot holds, inside the three blocks a sword reaches. */
    private static final double DISTANCE = 2.5D;
    /** How far the bot is allowed to drift from it. */
    private static final double TOLERANCE = 0.25D;
    /** Ticks the bot spends walking one way before it turns round. */
    private static final int PACE = 14;

    @Override
    public String name()
    {
        return "aim";
    }

    @Override
    public String describe()
    {
        return "hit a bot that strafes at %.1f blocks without fighting back".formatted(DISTANCE);
    }

    @Override
    public BotPvpConfig.CombatStyle style()
    {
        return BotPvpConfig.CombatStyle.MELEE;
    }

    @Override
    public String unavailable(MinecraftServer server, ServerPlayer player)
    {
        return DrillRun.holdsWeapon(player) ? null : "hold a sword, an axe or a mace to aim with";
    }

    @Override
    public void begin(DrillRun run)
    {
        Drills.spawnBot(run, Drills.inFront(run.player(), DISTANCE));
    }

    @Override
    public void tick(DrillRun run)
    {
        DrillBot bot = run.bot();
        if (bot == null) return;
        ServerPlayer player = run.player();
        double away = DrillBot.flatDistance(bot.bot(), player);
        int forward = away > DISTANCE + TOLERANCE ? 1 : away < DISTANCE - TOLERANCE ? -1 : 0;
        int strafe = (run.ticks() / PACE) % 2 == 0 ? 1 : -1;
        bot.tick(player, forward, strafe, true, false, false, false);
    }

    @Override
    public boolean done(DrillRun run)
    {
        return run.ticks() >= limit() || run.swings() >= 20;
    }

    @Override
    public int limit()
    {
        return 400;
    }

    @Override
    public String score(DrillRun run)
    {
        return run.swings() + " swings, " + run.hits() + " hits, " + DrillRun.percent(run.hits(), run.swings());
    }

    @Override
    public String summary(DrillRun run)
    {
        return String.format(java.util.Locale.ROOT, "%d of %d swings hit (%.0f%%) in %d ticks",
                run.hits(), run.swings(), 100.0 * run.hits() / Math.max(1, run.swings()), run.ticks());
    }
}