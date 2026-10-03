package carpet.pvp.gui;

import carpet.pvp.BotPvpConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;

/** The page that spawns a bot: pick the style it fights with, the difficulty it plays at, and let it in. */
final class SpawnMenu extends BotMenu
{
    private static final Component TITLE = Component.literal("Spawn a bot");
    private static final int ROWS = 4;
    private static final int FIRST_MODE = 0;
    private static final int FIRST_DIFFICULTY = 9;
    private static final int BACK = 27;
    private static final int SPAWN = 31;

    private static final Item[] MODE_ICONS = {
            Items.IRON_SWORD, Items.NETHERITE_SWORD, Items.MACE, Items.END_CRYSTAL, Items.RESPAWN_ANCHOR, Items.CROSSBOW };

    private final ServerPlayer viewer;
    private BotPvpConfig.CombatStyle mode;
    private BotPvpConfig.Difficulty difficulty;

    private SpawnMenu(int containerId, Inventory inventory, ServerPlayer viewer)
    {
        super(containerId, inventory, viewer, ROWS);
        this.viewer = viewer;
        // The menu starts on what a bot spawned with a command would start on.
        BotPvpConfig defaults = new BotPvpConfig();
        this.mode = defaults.combatStyle;
        this.difficulty = defaults.difficulty;
        for (int i = 0; i < MODE_ICONS.length; i++)
        {
            int index = i;
            button(FIRST_MODE + i, () -> pickStyle(index));
        }
        for (int i = 0; i < BotPvpConfig.Difficulty.values().length; i++)
        {
            int index = i;
            button(FIRST_DIFFICULTY + i, () -> pickDifficulty(index));
        }
        button(BACK, () -> MainMenu.open(viewer));
        button(SPAWN, () -> spawn());
        repaint();
    }

    static void open(ServerPlayer viewer)
    {
        viewer.openMenu(new SimpleMenuProvider((containerId, inventory, player) ->
                new SpawnMenu(containerId, inventory, (ServerPlayer) player), TITLE));
    }

    private void pickStyle(int index)
    {
        BotPvpConfig.CombatStyle[] styles = BotPvpConfig.CombatStyle.values();
        if (index < styles.length) mode = styles[index];
    }

    private void pickDifficulty(int index)
    {
        BotPvpConfig.Difficulty[] difficulties = BotPvpConfig.Difficulty.values();
        if (index < difficulties.length) difficulty = difficulties[index];
    }

    private void spawn()
    {
        MainMenu.open(viewer);
        if (!BotGui.spawnBot(viewer, mode, difficulty.name().toLowerCase(Locale.ROOT)))
        {
            BotGui.report(viewer, "Could not spawn a bot here.");
        }
    }

    @Override
    protected void paint()
    {
        clearAll();
        BotPvpConfig.CombatStyle[] styles = BotPvpConfig.CombatStyle.values();
        for (int i = 0; i < MODE_ICONS.length && i < styles.length; i++)
        {
            boolean chosen = styles[i] == mode;
            put(FIRST_MODE + i, MenuItems.button(MODE_ICONS[i], chosen ? ChatFormatting.GREEN : ChatFormatting.GRAY,
                    (chosen ? MenuItems.CHOSEN : "") + "Style: " + BotGui.styleName(styles[i]),
                    chosen ? "What it fights with." : "Click to fight with " + BotGui.styleName(styles[i]) + "."));
        }
        BotPvpConfig.Difficulty[] difficulties = BotPvpConfig.Difficulty.values();
        for (int i = 0; i < difficulties.length; i++)
        {
            boolean chosen = difficulties[i] == difficulty;
            String label = difficulties[i].name().toLowerCase(Locale.ROOT);
            put(FIRST_DIFFICULTY + i, MenuItems.button(Items.IRON_SWORD, chosen ? ChatFormatting.GREEN : ChatFormatting.GRAY,
                    (chosen ? MenuItems.CHOSEN : "") + "Difficulty: " + label,
                    chosen ? "What it plays at." : "Click to play at " + label + "."));
        }
        put(BACK, MenuItems.button(Items.BARRIER, ChatFormatting.RED, "Back", "The main menu."));
        put(SPAWN, MenuItems.button(Items.ZOMBIE_HEAD, ChatFormatting.GREEN,
                "Spawn it here", "Style: " + BotGui.styleName(mode), "Difficulty: " + difficulty.name().toLowerCase(Locale.ROOT),
                "It joins where you stand and starts fighting."));
    }

    @Override
    protected String signature()
    {
        return "spawn " + mode + " " + difficulty;
    }
}