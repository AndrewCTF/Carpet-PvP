package carpet.paper;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBudget;
import carpet.pvp.BotSettings;
import carpet.pvp.PvpSystems;
import carpet.pvp.autosetup.AutoSetupManager;
import carpet.pvp.autosetup.AutoSetupSettings;
import carpet.pvp.bot.CombatCommands;
import carpet.pvp.gui.BotGui;
import carpet.pvp.selftest.SelfTest;
import carpet.utils.DelayedTasks;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;

/**
 * Carpet PvP as a Paper plugin: fake players, navigation, kits, the combat brain, the menus, the
 * drills and the matches, built from the same sources the Fabric mod is. Carpet's rules, Scarpet and
 * the mixins stay Fabric-only, so this side gets its settings from {@code config.yml} and its
 * per-tick work from a repeating task.
 */
public class CarpetPvpPlugin extends JavaPlugin
{
    /** Scenarios that need something this plugin does not ship; see {@link SelfTest.Platform}. */
    private static final Set<String> UNSUPPORTED = Set.of(
            "script_run", "fill_updates", "logic_program", "logic_forever_budget", "logic_bot_snapshot",
            "logic_combat_start_stop", "logic_fight_node", "logic_combat_option", "logic_on_kill_event",
            "logic_totem_pop_event", "logic_stop_program_stops_fight", "logic_admin_login",
            "logic_save_draft_and_autosave", "logic_expression_if_while",
            "sword_block", "explosion_rules", "xp_explosions", "scarpet_events", "scarpet_explosion",
            "update_suppression_block", "stackable_shulker_boxes", "structure_block_ignored", "persistent_parrots",
            "lag_free_spawning", "interaction_updates", "punish_wrong_tool_hits", "scarpet_item_use_events",
            "sculk_sensor_range", "summon_natural_lightning", "explosion_state_leak", "scarpet_world_data",
            "tick_synced_world_borders",
            // The mace model assumes the critical hit the fake player mixin grants a bot on any falling
            // swing; without it the style's plans, and the damage it then deals, are a different thing.
            "mace_smash_damage", "mace_no_fall_damage_on_miss");

    private BukkitTask tickTask;

    @Override
    public void onEnable()
    {
        saveDefaultConfig();
        loadSettings();
        hookSettings();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> PaperBotCommands.register(event.registrar()));
        getServer().getPluginManager().registerEvents(new Players(), this);

        if (SelfTest.requested())
        {
            SelfTest.use(new SelfTest.Platform("bot", UNSUPPORTED, PaperSelfTest::setup));
        }

        MinecraftServer server = server();
        PvpSystems.onServerStarted(server);

        // One task, once a tick: everything the shared code expects its host to drive, in the order
        // Fabric's server drives it in. The self-test goes before the practices, because a scenario
        // acts on the world and they read what the tick did to it; a fake player that dies in a
        // scenario drops to a hit point for one tick and is put back by DelayedTasks, so a session
        // has to be ticked on that tick too or it never sees the fighter die.
        tickTask = getServer().getScheduler().runTaskTimer(this, () -> {
            BotBudget.instance().beginTick(BotSettings.botSimBudget);
            DelayedTasks.tick(server);
            SelfTest.tick(server);
            PvpSystems.tick(server);
            AutoSetupManager.tick(server);
            CombatCommands.tick(server);
            BotGui.tick(server);
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
        // What the practices still hold goes back into the world folder before anything closes.
        PvpSystems.onServerStopped(server());
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

    /**
     * Answers the two questions {@code /auto-setup} asks its host about the settings a session needs.
     * On Fabric those are {@code /carpet} rules; here they are the {@link BotSettings} the whole shared
     * code reads, which is what {@code config.yml} filled in.
     */
    private void hookSettings()
    {
        AutoSetupSettings.reader = setting -> "fakePlayerNavigation".equals(setting)
                ? String.valueOf(BotSettings.fakePlayerNavigation) : null;
        AutoSetupSettings.writer = (setting, value) ->
        {
            if (!"fakePlayerNavigation".equals(setting)) return false;
            BotSettings.fakePlayerNavigation = Boolean.parseBoolean(value);
            return true;
        };
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

    /** The players a session of {@code /auto-setup} belongs to, as their own logins and logouts. */
    private class Players implements Listener
    {
        @EventHandler
        public void onJoin(PlayerJoinEvent event)
        {
            AutoSetupManager.onPlayerJoined(server(), ((CraftPlayer) event.getPlayer()).getHandle());
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event)
        {
            AutoSetupManager.onPlayerLeft(server(), ((CraftPlayer) event.getPlayer()).getHandle());
        }
    }
}