package carpet.pvp.gui;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

/** The menu {@code /bot gui} opens: the bots of the server, and what can be done to them. */
final class MainMenu extends BotMenu
{
    private static final Component TITLE = Component.literal("Bots");
    private static final int ROWS = 6;
    /** The bots are listed from here up; the last row is the menu's own row. */
    private static final int FIRST_BUTTON = 45;
    private static final int SPAWN = 45;
    private static final int KITS = 47;
    private static final int QUICK_FIGHT = 49;
    private static final int STOP_ALL = 53;

    private final ServerPlayer viewer;

    private MainMenu(int containerId, Inventory inventory, ServerPlayer viewer)
    {
        super(containerId, inventory, viewer, ROWS);
        this.viewer = viewer;
        // The bots change while the menu is open, so each of their slots opens whatever bot is on it now.
        for (int slot = 0; slot < FIRST_BUTTON; slot++)
        {
            int index = slot;
            button(slot, () -> openBot(index));
        }
        button(SPAWN, () -> SpawnMenu.open(viewer));
        button(KITS, () -> KitListMenu.open(viewer));
        button(QUICK_FIGHT, () -> BotGui.quickFight(viewer));
        button(STOP_ALL, () -> BotGui.stopAll(viewer));
        repaint();
    }

    static void open(ServerPlayer viewer)
    {
        viewer.openMenu(new SimpleMenuProvider((containerId, inventory, player) ->
                new MainMenu(containerId, inventory, (ServerPlayer) player), TITLE));
    }

    @Override
    protected void paint()
    {
        List<EntityPlayerMPFake> bots = BotGui.bots(viewer);
        int shown = Math.min(bots.size(), FIRST_BUTTON);
        for (int i = 0; i < shown; i++) put(i, headOf(bots.get(i)));
        for (int i = shown; i < FIRST_BUTTON; i++) clear(i);

        put(SPAWN, MenuItems.button(Items.ZOMBIE_HEAD, ChatFormatting.GREEN, "Spawn a bot",
                "Pick a style and a difficulty,", "and the bot joins where you stand."));
        put(KITS, MenuItems.button(Items.CHEST, ChatFormatting.YELLOW, "Kits",
                "The loadouts of this server.", "Click one to open it in the editor."));
        put(QUICK_FIGHT, MenuItems.button(Items.IRON_SWORD, ChatFormatting.GOLD, "Start a quick fight",
                "Spawns two bots of the default style", "and sets them fighting each other."));
        put(STOP_ALL, MenuItems.button(Items.BARRIER, ChatFormatting.RED, "Stop all",
                bots.isEmpty() ? "There is no bot on this server." : "Stops every bot of this server."));
    }

    /** A bot's head, named after it, with what it is doing on the lore lines. */
    private ItemStack headOf(EntityPlayerMPFake bot)
    {
        BotPvpConfig cfg = bot.getPvpConfig();
        String kit = BotGui.kitOf(bot);
        return MenuItems.head(bot,
                MenuItems.text(bot.getName().getString(), ChatFormatting.WHITE),
                MenuItems.text(health(bot), bot.getHealth() < bot.getMaxHealth() ? ChatFormatting.RED : ChatFormatting.GREEN),
                MenuItems.text("style: " + BotGui.styleName(cfg.combatStyle), ChatFormatting.GRAY),
                MenuItems.text("difficulty: " + cfg.difficulty.name().toLowerCase(Locale.ROOT), ChatFormatting.GRAY),
                MenuItems.text("kit: " + (kit == null ? "none" : kit), ChatFormatting.GRAY),
                MenuItems.text(cfg.combat ? "fighting" : "idle", cfg.combat ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                MenuItems.text("click to open its settings", ChatFormatting.DARK_GRAY));
    }

    private static String health(EntityPlayerMPFake bot)
    {
        return String.format(Locale.ROOT, "health: %.1f / %.1f", bot.getHealth(), bot.getMaxHealth());
    }

    @Override
    protected String signature()
    {
        StringBuilder state = new StringBuilder("main");
        for (EntityPlayerMPFake bot : BotGui.bots(viewer))
        {
            BotPvpConfig cfg = bot.getPvpConfig();
            state.append('|').append(bot.getName()).append(' ').append(bot.getHealth()).append('/')
                    .append(bot.getMaxHealth()).append(' ').append(bot.isAlive()).append(' ')
                    .append(cfg.combatStyle).append(' ').append(cfg.difficulty).append(' ')
                    .append(cfg.combat).append(' ').append(BotGui.kitOf(bot));
        }
        return state.toString();
    }

    /** Opens the page of whichever bot is listed in that slot now. */
    private void openBot(int index)
    {
        List<EntityPlayerMPFake> bots = BotGui.bots(viewer);
        if (index < bots.size()) BotPage.open(viewer, bots.get(index).getName().getString());
    }
}