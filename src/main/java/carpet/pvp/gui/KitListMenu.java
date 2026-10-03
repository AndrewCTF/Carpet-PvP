package carpet.pvp.gui;

import carpet.pvp.kit.KitStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

import java.util.List;

/** The kits of the server, each of which opens in the editor. */
final class KitListMenu extends BotMenu
{
    private static final Component TITLE = Component.literal("Kits");
    private static final int ROWS = 5;
    private static final int LAST_KIT = 36;
    private static final int BLANK = 37;
    private static final int RELOAD = 39;
    private static final int BACK = 41;

    private final ServerPlayer viewer;

    private KitListMenu(int containerId, Inventory inventory, ServerPlayer viewer)
    {
        super(containerId, inventory, viewer, ROWS);
        this.viewer = viewer;
        for (int slot = 0; slot < LAST_KIT; slot++)
        {
            int index = slot;
            button(slot, () -> openKit(index));
        }
        button(BLANK, () -> KitEditorMenu.open(viewer, null));
        button(RELOAD, () -> store().reload());
        button(BACK, () -> MainMenu.open(viewer));
        repaint();
    }

    static void open(ServerPlayer viewer)
    {
        viewer.openMenu(new SimpleMenuProvider((containerId, inventory, player) ->
                new KitListMenu(containerId, inventory, (ServerPlayer) player), TITLE));
    }

    private KitStore store()
    {
        return KitStore.of(viewer.level().getServer());
    }

    private void openKit(int index)
    {
        List<String> kits = store().names();
        if (index < kits.size()) KitEditorMenu.open(viewer, kits.get(index));
    }

    @Override
    protected void paint()
    {
        List<String> kits = store().names();
        int shown = Math.min(kits.size(), LAST_KIT);
        for (int i = 0; i < shown; i++)
        {
            String kit = kits.get(i);
            boolean custom = store().customNames().contains(kit);
            String problem = store().problems().get(kit);
            put(i, MenuItems.button(Items.CHEST, custom ? ChatFormatting.YELLOW : ChatFormatting.GRAY,
                    (custom ? "" : MenuItems.CHOSEN) + kit,
                    problem != null ? "cannot be used: " + problem : kitEntries(kit),
                    "click to open it in the editor"));
        }
        for (int i = shown; i < LAST_KIT; i++) clear(i);
        put(BLANK, MenuItems.button(Items.PAPER, ChatFormatting.YELLOW, "Start an empty kit",
                "An editor with nothing in it,", "for a loadout of your own."));
        put(RELOAD, MenuItems.button(Items.AMETHYST_SHARD, ChatFormatting.AQUA, "Read the kits again",
                "Looks in the world's carpet-kits folder", "for kits written outside the game."));
        put(BACK, MenuItems.button(Items.BARRIER, ChatFormatting.RED, "Back", "The main menu."));
    }

    private String kitEntries(String kit)
    {
        int count = store().get(kit).map(found -> found.entries().size()).orElse(0);
        return count + (count == 1 ? " item." : " items.");
    }

    @Override
    protected String signature()
    {
        KitStore store = store();
        return "kits " + store.names() + " " + store.problems().keySet();
    }
}