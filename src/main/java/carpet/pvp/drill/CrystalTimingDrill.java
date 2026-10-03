package carpet.pvp.drill;

import carpet.pvp.BotPvpConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Crystal timing: the bot paces about on a patch of obsidian, and the player has to put a crystal on
 * the block it is leaving. The score is how long that took.
 */
final class CrystalTimingDrill implements Drill
{
    /** How wide the obsidian the bot paces on is. */
    private static final int PAD = 5;
    /** Ticks the bot stands on its first spot before it starts walking. */
    private static final int SETTLE = 20;
    /** Ticks it walks one way before it turns round. */
    private static final int PACE = 12;

    /** What the drill keeps: the obsidian it laid, the spot the bot left and when the crystal went off. */
    private static final class Pad
    {
        private final List<BlockPos> laid;
        private final ServerLevel level;
        private BlockPos start;
        private int movingAt = -1;
        private int blownAt = -1;
        private int hits;

        private Pad(List<BlockPos> laid, ServerLevel level)
        {
            this.laid = laid;
            this.level = level;
        }
    }

    @Override
    public String name()
    {
        return "crystaltiming";
    }

    @Override
    public String describe()
    {
        return "put a crystal under a bot pacing on obsidian, before it steps off";
    }

    @Override
    public BotPvpConfig.CombatStyle style()
    {
        return BotPvpConfig.CombatStyle.CRYSTAL;
    }

    @Override
    public String unavailable(MinecraftServer server, ServerPlayer player)
    {
        if (!(player.level() instanceof ServerLevel))
        {
            return "there is no world to lay obsidian in";
        }
        if (Drills.kit(server, style()) == null)
        {
            return "there is no crystal kit on this server";
        }
        return DrillRun.count(player, Items.END_CRYSTAL) > 0 ? null : "you need an end crystal to place";
    }

    @Override
    public void begin(DrillRun run)
    {
        ServerPlayer player = run.player();
        ServerLevel level = (ServerLevel) player.level();
        // The pad goes where the player stands, one block down, so the bot has something solid under it
        // and something to walk across.
        int x = (int) Math.floor(player.getX()) - PAD / 2;
        int z = (int) Math.floor(player.getZ()) - PAD / 2;
        int y = (int) Math.floor(player.getY()) - 1;
        run.state(new Pad(Drills.lay(level, new Vec3i(x, y, z), PAD, y), level));
        Drills.spawnBot(run, new Vec3(x + 0.5D, y + 1.0D, z + 0.5D));
    }

    @Override
    public void tick(DrillRun run)
    {
        DrillBot bot = run.bot();
        if (bot == null) return;
        Pad pad = (Pad) run.state();
        ServerPlayer player = run.player();
        pad.hits = run.hits();
        if (pad.start == null)
        {
            pad.start = bot.bot().blockPosition();
            return;
        }
        if (run.ticks() < SETTLE)
        {
            bot.tick(player, 0, 0, false, false, false, false);
            return;
        }
        if (pad.movingAt < 0 && !bot.bot().blockPosition().equals(pad.start))
        {
            pad.movingAt = run.ticks();
            run.attempt();
        }
        int forward = (run.ticks() / PACE) % 2 == 0 ? 1 : -1;
        bot.tick(player, forward, 0, false, false, false, false);
        // The crystal goes off when the bot is off the block it stood on, which shows up as a hit on
        // the bot that no swing of the player's could have made.
        if (pad.movingAt > 0 && pad.hits > 0 && pad.blownAt < 0)
        {
            pad.blownAt = run.ticks();
            run.timed(pad.blownAt - pad.movingAt);
            run.succeed();
        }
    }

    @Override
    public boolean done(DrillRun run)
    {
        return ((Pad) run.state()).blownAt > 0 || run.ticks() >= limit();
    }

    @Override
    public int limit()
    {
        return 400;
    }

    @Override
    public String score(DrillRun run)
    {
        Pad pad = (Pad) run.state();
        return pad.movingAt < 0 ? "wait for it to move"
                : pad.blownAt < 0 ? "put a crystal under it"
                : "detonated in " + (pad.blownAt - pad.movingAt) + " ticks";
    }

    /** Reports the result and takes the obsidian back, since a summary is written once per drill. */
    @Override
    public String summary(DrillRun run)
    {
        Pad pad = (Pad) run.state();
        Drills.putBack(pad.level, pad.laid);
        if (pad.movingAt < 0)
        {
            return "the bot never left its spot";
        }
        int mean = run.meanTime();
        return String.format(Locale.ROOT, "%d of %d crystals landed%s", run.successes(), run.attempts(),
                mean < 0 ? "" : ", " + mean + " ticks on average");
    }
}