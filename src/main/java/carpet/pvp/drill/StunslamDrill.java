package carpet.pvp.drill;

import carpet.pvp.BotPvpConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * Stun-slam: the bot holds its shield up for the whole drill, and the player has to take the shield
 * down with an axe and land a mace swing while it is down.
 */
final class StunslamDrill implements Drill
{
    /** A mace slam throws what it hits into the air; anything above this is not a normal hit. */
    private static final double SLAM_LAUNCH = 0.35D;
    /** Slams the drill goes for before it calls it a run. */
    private static final int SLAMS = 3;

    /** What the drill keeps: the tick the shield last went down, which is the window the mace wants. */
    private static final class Window
    {
        private int brokenAt = -1;
    }

    @Override
    public String name()
    {
        return "stunslam";
    }

    @Override
    public String describe()
    {
        return "axe a blocking bot down and mace it inside the disabled window";
    }

    @Override
    public BotPvpConfig.CombatStyle style()
    {
        return BotPvpConfig.CombatStyle.MELEE;
    }

    @Override
    public String unavailable(MinecraftServer server, ServerPlayer player)
    {
        if (!DrillRun.carriesKind(player, ItemTags.AXES))
        {
            return "you need an axe to take the shield down";
        }
        if (!DrillRun.carries(player, Items.MACE))
        {
            return "you need a mace to slam with once the shield is down";
        }
        return null;
    }

    @Override
    public void begin(DrillRun run)
    {
        run.state(new Window());
        Drills.spawnBot(run, Drills.inFront(run.player(), 2.5D));
    }

    @Override
    public void tick(DrillRun run)
    {
        DrillBot bot = run.bot();
        if (bot == null) return;
        Window window = (Window) run.state();
        ServerPlayer player = run.player();
        bot.tick(player, 0, 0, false, false, true, false);
        if (bot.bot().getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD)))
        {
            if (window.brokenAt < 0)
            {
                window.brokenAt = run.ticks();
                run.attempt();
            }
        }
        else if (window.brokenAt > 0)
        {
            // The shield is usable again, so the next axe hit opens a new window.
            window.brokenAt = -1;
        }
        if (window.brokenAt > 0 && bot.bot().getDeltaMovement().y > SLAM_LAUNCH)
        {
            run.timed(run.ticks() - window.brokenAt);
            run.succeed();
            window.brokenAt = -1;
        }
    }

    @Override
    public boolean done(DrillRun run)
    {
        return run.successes() >= SLAMS || run.ticks() >= limit();
    }

    @Override
    public int limit()
    {
        return 600;
    }

    @Override
    public String score(DrillRun run)
    {
        return run.successes() + " of " + run.attempts() + " shields slammed";
    }

    @Override
    public String summary(DrillRun run)
    {
        int mean = run.meanTime();
        return String.format(Locale.ROOT, "%d of %d shields down were slammed inside the window%s",
                run.successes(), run.attempts(), mean < 0 ? "" : ", " + mean + " ticks after the axe hit");
    }
}