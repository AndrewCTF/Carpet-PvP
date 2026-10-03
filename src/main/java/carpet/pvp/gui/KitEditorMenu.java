package carpet.pvp.gui;

import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The kit editor: a chest laid out like an inventory, which is the one menu where the player may move
 * items. What is in it is a layout of their own; closing it or pressing save hands it to
 * {@link BotGui} to be written out as a custom kit.
 *
 * <p>Nothing the player carries is taken from them or handed to them here. The panes that mark the
 * slots of the layout that nobody has filled in cannot be moved at all, so a click on one either puts
 * what the player is holding into that slot or does nothing; a pane can never reach an inventory.</p>
 */
final class KitEditorMenu extends BotMenu
{
    private static final Component TITLE = Component.literal("Kit editor");
    private static final int ROWS = 6;
    private static final int SAVE = 45;
    private static final int SAVE_AS = 47;
    private static final int DELETE = 49;
    private static final int BACK = 51;

    private final ServerPlayer viewer;
    private final String kit;
    private final boolean custom;

    private KitEditorMenu(int containerId, Inventory inventory, ServerPlayer viewer, String kit, boolean custom)
    {
        super(containerId, inventory, viewer, ROWS);
        this.viewer = viewer;
        this.kit = kit;
        this.custom = custom;
        KitStore store = store();
        Kit loaded = store.get(kit).orElse(null);
        if (loaded != null) KitLayout.write(chest(), loaded, viewer.level().getServer().registryAccess());
        button(SAVE, () -> save());
        button(SAVE_AS, () -> saveAs());
        button(DELETE, () -> delete());
        button(BACK, () -> KitListMenu.open(viewer));
        remember();
        repaint();
    }

    static void open(ServerPlayer viewer, String kit)
    {
        String name = kit == null ? BotGui.NEW_KIT : kit;
        KitStore store = KitStore.of(viewer.level().getServer());
        boolean custom = store.customNames().contains(name);
        viewer.openMenu(new SimpleMenuProvider((containerId, inventory, player) ->
                new KitEditorMenu(containerId, inventory, (ServerPlayer) player, name, custom),
                Component.literal(kit == null ? "New kit" : "Kit " + kit)));
    }

    @Override
    protected boolean otherClicksMoveItems()
    {
        return true;
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player)
    {
        // A slot that is only marked is a slot the player has not filled in, so a click on it with
        // something in hand puts that something there. With an empty hand there is nothing to do.
        if (input == ContainerInput.PICKUP && isMarker(slot) && !getCarried().isEmpty())
        {
            chest().setItem(slot, getCarried());
            setCarried(ItemStack.EMPTY);
            return;
        }
        super.clicked(slot, button, input, player);
    }

    @Override
    protected boolean shiftClickAllowed(int index)
    {
        return !isMarker(index);
    }

    private boolean isMarker(int slot)
    {
        return slot >= 0 && slot < KitLayout.SLOTS && MenuItems.isMarker(slot(slot));
    }

    @Override
    public void removed(Player player)
    {
        // The layout of a player who walks away from the editor is still theirs to save.
        remember();
        super.removed(player);
    }

    /** Keeps the layout where a chat command can still find it. */
    private void remember()
    {
        BotGui.remember(viewer, KitLayout.read(chest(), kit));
    }

    private void save()
    {
        remember();
        if (BotGui.saveAs(viewer, kit)) KitListMenu.open(viewer);
        else BotGui.report(viewer, "Could not save the kit " + kit + ".");
    }

    private void saveAs()
    {
        remember();
        BotGui.promptName(viewer);
    }

    private void delete()
    {
        if (!custom)
        {
            BotGui.report(viewer, kit + " is one of the kits of the mod, so it cannot be deleted. "
                    + "Save it under a name of your own to keep your changes.");
            return;
        }
        remember();
        if (store().delete(kit)) KitListMenu.open(viewer);
        else BotGui.report(viewer, "Could not delete the kit " + kit + ".");
    }

    private KitStore store()
    {
        return KitStore.of(viewer.level().getServer());
    }

    @Override
    protected void paint()
    {
        markEmptySlots();
        clear(SAVE);
        clear(SAVE_AS);
        clear(DELETE);
        clear(BACK);
        put(SAVE, MenuItems.button(Items.WRITABLE_BOOK, ChatFormatting.GREEN, "Save",
                "Writes this layout as a kit called", kit + ", in the world's kit folder."));
        put(SAVE_AS, MenuItems.button(Items.NAME_TAG, ChatFormatting.YELLOW, "Save as",
                "Asks in chat for a name of your own."));
        put(DELETE, MenuItems.button(Items.BARRIER, ChatFormatting.RED, "Delete",
                custom ? "Removes the kit " + kit + "." : kit + " is a kit of the mod, not yours to delete."));
        put(BACK, MenuItems.button(Items.ARROW, ChatFormatting.GRAY, "Back", "The list of kits."));
    }

    /** Marks the slots of the layout that nobody has put anything in yet. */
    private void markEmptySlots()
    {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++)
        {
            if (slot(i).isEmpty()) put(i, MenuItems.marker(i < 9 ? "Hotbar slot " + (i + 1) : "Inventory slot " + (i - 8)));
        }
        for (int i = 0; i < KitLayout.EQUIPMENT.length; i++)
        {
            int at = Inventory.INVENTORY_SIZE + i;
            if (slot(at).isEmpty()) put(at, MenuItems.marker(KitLayout.EQUIPMENT[i].getName()));
        }
    }

    @Override
    protected String signature()
    {
        // The layout changes as the player moves things, so it is part of what the menu watches.
        StringBuilder state = new StringBuilder("editor ").append(kit);
        for (int i = 0; i < KitLayout.SLOTS; i++) state.append(slot(i).isEmpty() ? '0' : '1');
        return state.toString();
    }
}