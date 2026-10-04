package carpet.pvp.kit;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Putting kits on players, and taking them off again.
 *
 * <p>Giving a kit clears the inventory, so what the player had is remembered first and handed back
 * by {@code /bot kit restore}. The first snapshot wins: giving a second kit does not overwrite what
 * the player owned before either of them.</p>
 *
 * <p>Snapshots live in memory for the server session; a player who logs out and back in with a kit
 * on keeps it.</p>
 */
public final class KitInventory
{
    private static final Logger LOG = LoggerFactory.getLogger(KitInventory.class);

    /** Armour and offhand, in the order a snapshot stores them. */
    private static final EquipmentSlot[] EQUIPMENT = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND
    };

    private static final Map<UUID, Snapshot> SNAPSHOTS = new HashMap<>();

    private KitInventory() {}

    /** What a player owned before their first kit. */
    public record Snapshot(int selected, List<ItemStack> slots)
    {
        public Snapshot
        {
            slots = List.copyOf(slots);
        }
    }

    /** Clears the inventory and hands out the kit, remembering what was there first. */
    public static void apply(ServerPlayer player, Kit kit, RegistryAccess registries)
    {
        save(player);
        overwrite(player, kit, registries);
    }

    /**
     * Clears the inventory and hands out the kit without remembering anything: for a caller that
     * keeps its own record of what the player owned, like an {@code /auto-setup} session, and only
     * wants the kit given out again.
     */
    public static void overwrite(ServerPlayer player, Kit kit, RegistryAccess registries)
    {
        // Every stack and slot is worked out before the player is touched: a kit that does not fit
        // must not cost them their inventory. An entry the registry of this version does not have is
        // skipped with one line: kit files are shared by every version, and an item one of them lacks
        // should cost that kit the item and nothing else.
        List<KitEntry> entries = new ArrayList<>(kit.entries().size());
        List<ItemStack> stacks = new ArrayList<>(kit.entries().size());
        for (KitEntry entry : kit.entries())
        {
            if (!entry.available(registries))
            {
                LOG.warn("Kit {}: skipping {}, which this version does not have", kit.name(), entry.item());
                continue;
            }
            entries.add(entry);
            stacks.add(entry.createStack(registries));
        }
        List<KitSlot> slots = plan(entries);

        Inventory inventory = player.getInventory();
        inventory.clearContent();
        for (int i = 0; i < stacks.size(); i++)
        {
            KitSlot slot = slots.get(i);
            if (slot.isEquipment()) player.setItemSlot(slot.equipment(), stacks.get(i));
            else inventory.setItem(slot.index(), stacks.get(i));
        }
        // The weapon goes in the first slot, so that is what the player is holding.
        inventory.setSelectedSlot(0);
        inventory.setChanged();
    }

    /**
     * Resolves the automatic slots of a kit. Entries that name a slot keep it, the rest take the
     * lowest free inventory slot, so that a kit reads top to bottom whichever way round it is.
     */
    private static List<KitSlot> plan(List<KitEntry> entries)
    {
        boolean[] taken = new boolean[Inventory.INVENTORY_SIZE];
        for (KitEntry entry : entries)
        {
            if (!entry.slot().isAutomatic() && !entry.slot().isEquipment()) taken[entry.slot().index()] = true;
        }

        List<KitSlot> slots = new ArrayList<>(entries.size());
        int next = 0;
        for (KitEntry entry : entries)
        {
            if (entry.slot().isEquipment())
            {
                slots.add(entry.slot());
                continue;
            }
            if (!entry.slot().isAutomatic())
            {
                slots.add(entry.slot());
                continue;
            }
            while (next < taken.length && taken[next]) next++;
            if (next >= taken.length) throw new IllegalArgumentException("a kit does not fit in the inventory");
            taken[next] = true;
            slots.add(KitSlot.ofIndex(next++));
        }
        return slots;
    }

    /** Remembers a player's inventory, unless it was already remembered. */
    public static boolean save(ServerPlayer player)
    {
        if (SNAPSHOTS.containsKey(player.getUUID())) return false;

        Inventory inventory = player.getInventory();
        List<ItemStack> slots = new ArrayList<>(Inventory.INVENTORY_SIZE + EQUIPMENT.length);
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) slots.add(copy(inventory.getItem(i)));
        for (EquipmentSlot slot : EQUIPMENT) slots.add(copy(player.getItemBySlot(slot)));
        SNAPSHOTS.put(player.getUUID(), new Snapshot(inventory.getSelectedSlot(), slots));
        return true;
    }

    /** Gives back a remembered inventory, if there is one. */
    public static boolean restore(ServerPlayer player)
    {
        Snapshot snapshot = SNAPSHOTS.remove(player.getUUID());
        if (snapshot == null) return false;

        Inventory inventory = player.getInventory();
        inventory.clearContent();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) inventory.setItem(i, snapshot.slots().get(i).copy());
        for (int i = 0; i < EQUIPMENT.length; i++)
        {
            player.setItemSlot(EQUIPMENT[i], snapshot.slots().get(Inventory.INVENTORY_SIZE + i).copy());
        }
        inventory.setSelectedSlot(snapshot.selected());
        inventory.setChanged();
        return true;
    }

    /** Turns a player's inventory into a kit, keeping every slot exactly as it is. */
    public static Kit capture(ServerPlayer player, String name)
    {
        Inventory inventory = player.getInventory();
        List<KitEntry> entries = new ArrayList<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++)
        {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) entries.add(KitEntry.ofStack(KitSlot.ofIndex(i), stack));
        }
        for (EquipmentSlot slot : EQUIPMENT)
        {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) entries.add(KitEntry.ofStack(KitSlot.ofEquipment(slot), stack));
        }
        return new Kit(name, entries);
    }

    private static ItemStack copy(ItemStack stack)
    {
        return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }
}