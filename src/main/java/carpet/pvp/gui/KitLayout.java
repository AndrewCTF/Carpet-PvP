package carpet.pvp.gui;

import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitEntry;
import carpet.pvp.kit.KitSlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The kit editor's chest, laid out like the inventory a kit is worn on: the main inventory, the
 * hotbar under it, and the armour and offhand positions along the bottom.
 *
     * <p>An entry that does not name a slot takes the first free inventory slot, so a kit read from a
     * file that leaves the slots out still lands somewhere sensible. The panes that mark the slots
     * nobody has put anything in are decoration, not part of the kit.</p>
     */
final class KitLayout
{
    /** The armour and offhand positions, in the order the editor shows them. */
    static final EquipmentSlot[] EQUIPMENT = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND };

    /** How many chest slots the layout of a kit takes up. */
    static final int SLOTS = Inventory.INVENTORY_SIZE + EQUIPMENT.length;

    private KitLayout() {}

    /** What a kit is, read back out of an editor's chest. Empty slots are left out of the kit. */
    static Kit read(SimpleContainer chest, String name)
    {
        List<KitEntry> entries = new ArrayList<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++)
        {
            ItemStack stack = chest.getItem(i);
            if (!stack.isEmpty() && !MenuItems.isMarker(stack)) entries.add(KitEntry.ofStack(KitSlot.ofIndex(i), stack));
        }
        for (int i = 0; i < EQUIPMENT.length; i++)
        {
            ItemStack stack = chest.getItem(Inventory.INVENTORY_SIZE + i);
            if (!stack.isEmpty() && !MenuItems.isMarker(stack)) entries.add(KitEntry.ofStack(KitSlot.ofEquipment(EQUIPMENT[i]), stack));
        }
        return new Kit(name, entries);
    }

    /** Fills an editor's chest with a kit. A slot whose item the server does not have stays empty. */
    static void write(SimpleContainer chest, Kit kit, RegistryAccess registries)
    {
        boolean[] taken = new boolean[Inventory.INVENTORY_SIZE];
        for (KitEntry entry : kit.entries())
        {
            if (!entry.slot().isAutomatic() && !entry.slot().isEquipment()) taken[entry.slot().index()] = true;
        }
        int next = 0;
        for (KitEntry entry : kit.entries())
        {
            int slot;
            if (entry.slot().isEquipment())
            {
                slot = Inventory.INVENTORY_SIZE + indexOf(entry.slot().equipment());
            }
            else if (entry.slot().isAutomatic())
            {
                while (next < taken.length && taken[next]) next++;
                if (next >= taken.length) throw new IllegalArgumentException("kit " + kit.name() + " does not fit in the inventory");
                slot = next;
                taken[next++] = true;
            }
            else
            {
                slot = entry.slot().index();
            }
            try
            {
                chest.setItem(slot, entry.createStack(registries));
            }
            catch (IllegalArgumentException unreadable)
            {
                // A kit that names an item this server does not have leaves that slot empty rather
                // than refusing to open: the kit list says what is wrong with it.
                chest.setItem(slot, ItemStack.EMPTY);
            }
        }
    }

    private static int indexOf(EquipmentSlot equipment)
    {
        for (int i = 0; i < EQUIPMENT.length; i++)
        {
            if (EQUIPMENT[i] == equipment) return i;
        }
        throw new IllegalArgumentException("not an armour or offhand slot: " + equipment.getName());
    }
}