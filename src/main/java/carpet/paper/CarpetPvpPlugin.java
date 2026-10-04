package carpet.paper;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBudget;
import carpet.pvp.BotSettings;
import carpet.pvp.bot.CombatCommands;
import carpet.pvp.selftest.SelfTest;
import carpet.utils.DelayedTasks;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;

/**
 * Carpet PvP as a Paper plugin: fake players, navigation, kits and the combat brain, built from the
 * same sources the Fabric mod is. Carpet's rules, Scarpet and the mixins stay Fabric-only, so this
 * side gets its settings from {@code config.yml} and its per-tick work from a repeating task.
 */
public class CarpetPvpPlugin extends JavaPlugin
{
    /** Scenarios that need something this plugin does not ship; see {@link SelfTest.Platform}. */
    private static final Set<String> UNSUPPORTED = Set.of(
            "script_run", "fill_updates", "logic_program", "logic_forever_budget", "logic_bot_snapshot",
            "sword_block", "explosion_rules", "xp_explosions", "scarpet_events", "scarpet_explosion",
            "update_suppression_block", "stackable_shulker_boxes", "structure_block_ignored", "persistent_parrots",
            "lag_free_spawning", "interaction_updates", "punish_wrong_tool_hits", "scarpet_item_use_events",
            "sculk_sensor_range", "summon_natural_lightning", "explosion_state_leak", "scarpet_world_data",
            "tick_synced_world_borders");

    private BukkitTask tickTask;

    @Override
    public void onEnable()
    {
        saveDefaultConfig();
        loadSettings();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> PaperBotCommands.register(event.registrar()));

        if (SelfTest.requested())
        {
            SelfTest.use(new SelfTest.Platform("bot", UNSUPPORTED, PaperSelfTest::setup));
        }

        MinecraftServer server = server();
        // One task, once a tick: everything the shared code expects its host to drive.
        tickTask = getServer().getScheduler().runTaskTimer(this, () -> {
            BotBudget.instance().beginTick(BotSettings.botSimBudget);
            DelayedTasks.tick(server);
            CombatCommands.tick(server);
            SelfTest.tick(server);
        }, 1L, 1L);
    }

    @Override
    public void onDisable()
    {
        if (tickTask != null)
        {
            tickTask.cancel();
            tickTask = null;
        }
        // A server that stops with fake players online hangs: their connections never close.
        EntityPlayerMPFake.disconnectAll(server());
        DelayedTasks.clear();
    }

    /** Re-reads {@code config.yml} into the settings holder. */
    public void loadSettings()
    {
        reloadConfig();
        PaperBotSettings.load(getConfig());
    }

    /** The loaded plugin, which the command classes reach the server through. */
    public static CarpetPvpPlugin get()
    {
        return JavaPlugin.getPlugin(CarpetPvpPlugin.class);
    }

    /** The vanilla server, which the Paper API only reaches through the CraftBukkit wrapper. */
    public MinecraftServer server()
    {
        return ((org.bukkit.craftbukkit.CraftServer) getServer()).getServer();
    }
}
