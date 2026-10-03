package carpet.pvp.drill;

import carpet.pvp.BotPvpConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * Re-totem: the bot knocks the player down to a totem at a rhythm, and the drill measures how long the
 * player takes to get a fresh totem back into the offhand every time one goes.
 */
final class ReTotemDrill implements Drill
{
    /** Ticks between the bot's swings, the beat the drill pops a totem on. */
    private static final int RHYTHM = 16;
    /** Popped totems the drill goes for before it ends. */
    private static final int POPS = 3;

    /** What the drill keeps: when the last totem went, and whether the player is out. */
    private static final class Pop
    {
        private boolean hadTotem;
        private int poppedAt = -1;
        private int pops;
        private boolean dead;
    }

    @Override
    public String name()
    {
        return "retotem";
    }

    @Override
    public String describe()
    {
        return "re-totem as fast as a bot can pop one, every %d ticks".formatted(RHYTHM);
    }

    @Override
    public BotPvpConfig.CombatStyle style()
    {
        return BotPvpConfig.CombatStyle.SMP;
    }

    @Override
    public String unavailable(MinecraftServer server, ServerPlayer player)
    {
        int totems = DrillRun.count(player, Items.TOTEM_OF_UNDYING);
        if (totems < 2)
        {
            return "you need a totem in your offhand and a spare to replace it with, you have " + totems;
        }
        return null;
    }

    @Override
    public void begin(DrillRun run)
    {
        run.state(new Pop());
        Drills.spawnBot(run, Drills.inFront(run.player(), 1.6D));
    }

    @Override
    public void tick(DrillRun run)
    {
        DrillBot bot = run.bot();
        if (bot == null) return;
        Pop pop = (Pop) run.state();
        ServerPlayer player = run.player();
        boolean armed = player.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING);
        // A totem only goes when the hit that would have killed the player lands, so the drill keeps
        // the bot on the player until there is nothing left to pop.
        boolean swing = run.ticks() % RHYTHM == 0 && DrillBot.flatDistance(bot.bot(), player) < 3.0D;
        bot.tick(player, 0, 0, false, false, false, swing);
        if (!player.isAlive())
        {
            pop.dead = true;
            return;
        }
        if (pop.poppedAt < 0)
        {
            if (pop.hadTotem && !armed)
            {
                pop.poppedAt = run.ticks();
                pop.pops++;
                run.attempt();
            }
            else
            {
                pop.hadTotem = armed;
            }
        }
        else if (armed)
        {
            run.timed(run.ticks() - pop.poppedAt);
            run.succeed();
            pop.poppedAt = -1;
            pop.hadTotem = true;
        }
    }

    @Override
    public boolean done(DrillRun run)
    {
        Pop pop = (Pop) run.state();
        return pop.dead || pop.pops >= POPS || run.ticks() >= limit();
    }

    @Override
    public int limit()
    {
        return 800;
    }

    @Override
    public String score(DrillRun run)
    {
        Pop pop = (Pop) run.state();
        int mean = run.meanTime();
        return pop.pops + " totems popped, re-totem " + (mean < 0 ? "never" : mean + " ticks");
    }

    @Override
    public String summary(DrillRun run)
    {
        Pop pop = (Pop) run.state();
        if (pop.dead)
        {
            return "you died after " + pop.pops + " totem(s), re-toteming " + run.successes() + " time(s)";
        }
        int mean = run.meanTime();
        return String.format(Locale.ROOT, "%d totems popped, %d re-totemed%s", pop.pops, run.successes(),
                mean < 0 ? "" : ", " + mean + " ticks on average");
    }
}