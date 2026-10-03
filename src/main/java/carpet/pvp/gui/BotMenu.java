package carpet.pvp.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
//~ if <26.1 'ContainerInput' -> 'ClickType' {
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * A chest screen the server owns. The items in it are the buttons, and every click is turned into the
 * action the menu put in that slot.
 *
 * <p>Nothing in a button menu can be taken out: the click handling is replaced instead of added to,
 * so no click of the player ever reaches the game's own item handling and the inventory they came
 * with is left exactly as it was. The kit editor is the one menu that lets items move, and it only
 * lets them move on the slots of its own layout.</p>
 */
public abstract class BotMenu extends ChestMenu
{
    private final Map<Integer, Runnable> buttons = new HashMap<>();
    private final SimpleContainer content;
    private final int grid;
    private String signature;
    private boolean closed;
    private int paints;

    protected BotMenu(int containerId, Inventory inventory, ServerPlayer viewer, int rows)
    {
        super(type(rows), containerId, inventory, new SimpleContainer(rows * 9), rows);
        this.content = (SimpleContainer) getContainer();
        this.grid = rows * 9;
    }

    private static MenuType<ChestMenu> type(int rows)
    {
        return switch (rows)
        {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /**
     * Repaints every menu of the server whose state has moved on: a bot that died or was changed by a
     * command, a kit that was saved or deleted, the page a player is looking at.
     */
    static void tick(MinecraftServer server)
    {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (player.containerMenu instanceof BotMenu menu) menu.refreshIfChanged();
        }
    }

    /** How many chest slots this menu's buttons live in. */
    protected final int grid()
    {
        return grid;
    }

    /** Makes a chest slot a button. */
    protected final void button(int slot, Runnable action)
    {
        buttons.put(slot, action);
    }

    /** True while that chest slot is one of the menu's buttons. */
    protected final boolean isButton(int slot)
    {
        return slot >= 0 && slot < grid && buttons.containsKey(slot);
    }

    /** The chest behind the menu, which is where a layout or a set of buttons lives. */
    protected final SimpleContainer chest()
    {
        return content;
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player)
    {
        if (isButton(slot))
        {
            press(slot, input);
            return;
        }
        // Everything that is not a button is swallowed: no click of the player's can reach the game's
        // own item handling, so an item cannot leave the chest and their inventory cannot be touched.
        if (otherClicksMoveItems()) super.clicked(slot, button, input, player);
    }

    /** Whether a click that is not on a button is the player's to move items with. */
    protected boolean otherClicksMoveItems()
    {
        return false;
    }

    /** Runs the action of a button. Only a plain click on it is one; any other kind of click is not. */
    private void press(int slot, ContainerInput input)
    {
        if (input != ContainerInput.PICKUP) return;
        Runnable action = buttons.get(slot);
        if (action == null) return;
        action.run();
        // A button that opened another screen has already left this one, and that screen paints itself.
        if (!closed) repaint();
    }
//~}

    @Override
    public ItemStack quickMoveStack(Player player, int index)
    {
        return shiftClickAllowed(index) ? super.quickMoveStack(player, index) : ItemStack.EMPTY;
    }

    /** Whether the game's own shift click may move what is in that slot. Only the editor says yes. */
    protected boolean shiftClickAllowed(int index)
    {
        return false;
    }

    @Override
    public void removed(Player player)
    {
        super.removed(player);
        // The items of a button menu are not anything, so nothing is handed back.
        content.clearContent();
        closed = true;
    }

    /**
     * Repaints the menu if what it shows has moved on since the last time it was painted: a bot that
     * died, a setting a command changed, a kit that was saved.
     */
    final void refreshIfChanged()
    {
        String now = signature();
        if (now.equals(signature)) return;
        repaint();
    }

    /** Repaints the menu now, whether or not anything has changed. */
    final void repaint()
    {
        signature = signature();
        paint();
        paints++;
    }

    /** How often this menu has painted itself, which tells a repaint from a click that changed nothing. */
    public final int paints()
    {
        return paints;
    }

    /** The state the menu shows, as text: the same state that decides whether it has to be painted. */
    protected abstract String signature();

    /** Writes the buttons of the menu into the chest. */
    protected abstract void paint();

    /** Puts a button into a chest slot. */
    protected final void put(int slot, ItemStack stack)
    {
        content.setItem(slot, stack);
    }

    /** Empties a chest slot. */
    protected final void clear(int slot)
    {
        content.setItem(slot, ItemStack.EMPTY);
    }

    /** What sits in a chest slot, for a menu that has to look at what the player did to it. */
    protected final ItemStack slot(int index)
    {
        return content.getItem(index);
    }

    /** Empties the chest, which a repaint that does not know where its buttons are needs. */
    protected final void clearAll()
    {
        for (int i = 0; i < grid; i++) content.setItem(i, ItemStack.EMPTY);
    }
}