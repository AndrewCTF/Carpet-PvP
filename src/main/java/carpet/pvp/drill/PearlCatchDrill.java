package carpet.pvp.drill;

import carpet.pvp.BotPvpConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Pearl-catch: the bot throws a pearl at the player and then runs, and the player has to close the
 * distance and land a hit before the bot gets away. The score is the time that took.
 */
final class PearlCatchDrill implements Drill
{
    /** Ticks the bot waits before it throws, so the player is ready for it. */
    private static final int THROW_AT = 20;
    /** How far the drill bot runs, and how far away it throws the pearl from. */
    private static final double RUN = 12.0D;

    /** What the drill keeps: where the bot threw from and which way it ran. */
    private static final class Flight
    {
        private Vec3 away = Vec3.ZERO;
        private int thrownAt = -1;
        private int caughtAt = -1;
        private int caught = 0;
    }

    @Override
    public String name()
    {
        return "pearlcatch";
    }

    @Override
    public String describe()
    {
        return "chase a bot that throws a pearl at you and runs";
    }

    @Override
    public BotPvpConfig.CombatStyle style()
    {
        return BotPvpConfig.CombatStyle.MACE;
    }

    @Override
    public String unavailable(MinecraftServer server, ServerPlayer player)
    {
        return DrillRun.holdsWeapon(player) ? null : "hold a sword, an axe or a mace to catch it with";
    }

    @Override
    public void begin(DrillRun run)
    {
        run.state(new Flight());
        Drills.spawnBot(run, Drills.inFront(run.player(), 4.0D));
    }

    @Override
    public void tick(DrillRun run)
    {
        DrillBot bot = run.bot();
        if (bot == null) return;
        Flight flight = (Flight) run.state();
        ServerPlayer player = run.player();
        if (flight.thrownAt < 0)
        {
            // The bot waits a moment facing the player, throws, and only then turns and runs.
            bot.tick(player, 0, 0, false, false, false, false);
            if (run.ticks() >= THROW_AT)
            {
                int slot = DrillRun.give(bot, new ItemStack(Items.ENDER_PEARL));
                if (slot >= 0)
                {
                    bot.bot().getInventory().setSelectedSlot(slot);
                    bot.useOnce();
                }
                flight.away = Drills.inFront(bot.bot(), RUN);
                flight.thrownAt = run.ticks();
            }
            return;
        }
        double left = flight.away.distanceToSqr(bot.bot().position());
        bot.tick(player, left > 1.0D ? 1 : 0, 0, true, false, false, false);
        if (flight.caught == 0 && run.hits() > 0)
        {
            flight.caught = run.hits();
            flight.caughtAt = run.ticks();
        }
    }

    @Override
    public boolean done(DrillRun run)
    {
        return ((Flight) run.state()).caughtAt > 0 || run.ticks() >= limit();
    }

    @Override
    public int limit()
    {
        return 300;
    }

    @Override
    public String score(DrillRun run)
    {
        Flight flight = (Flight) run.state();
        return flight.thrownAt < 0 ? "get ready"
                : flight.caughtAt < 0 ? "run it down"
                : "caught in " + (flight.caughtAt - flight.thrownAt) + " ticks";
    }

    @Override
    public String summary(DrillRun run)
    {
        Flight flight = (Flight) run.state();
        if (flight.thrownAt < 0)
        {
            return "the bot never got its pearl away";
        }
        if (flight.caughtAt < 0)
        {
            return "not caught within " + (run.ticks() - flight.thrownAt) + " ticks";
        }
        return String.format(Locale.ROOT, "caught with %d hits %d ticks after the pearl went out",
                flight.caught, flight.caughtAt - flight.thrownAt);
    }
}