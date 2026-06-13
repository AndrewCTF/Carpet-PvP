package carpet.pvp;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Stateless helpers for bot survival reflexes: keeping a Totem of Undying or shield in the
 * offhand by shuffling items out of the main inventory.
 */
public final class CombatUtils
{
    private CombatUtils() {}

    /**
     * Ensures a Totem of Undying occupies the offhand, pulling one from the inventory if needed.
     * Returns true if a totem is in the offhand after the call.
     */
    public static boolean ensureTotemInOffhand(ServerPlayer player)
    {
        return ensureItemInOffhand(player, Items.TOTEM_OF_UNDYING);
    }

    /**
     * Ensures a shield occupies the offhand. Returns true if a shield is in the offhand.
     */
    public static boolean ensureShieldInOffhand(ServerPlayer player)
    {
        return ensureItemInOffhand(player, Items.SHIELD);
    }

    private static boolean ensureItemInOffhand(ServerPlayer player, net.minecraft.world.item.Item wanted)
    {
        ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (offhand.is(wanted))
        {
            return true;
        }

        Inventory inv = player.getInventory();
        int found = -1;
        for (int i = 0; i < inv.getContainerSize(); i++)
        {
            if (inv.getItem(i).is(wanted))
            {
                found = i;
                break;
            }
        }
        if (found < 0)
        {
            return false;
        }

        ItemStack totem = inv.getItem(found);
        // Move the wanted item into the offhand, displacing whatever was there.
        inv.setItem(found, offhand);
        player.setItemSlot(EquipmentSlot.OFFHAND, totem);
        return true;
    }

    /** True if the offhand currently holds the given item type. */
    public static boolean offhandHolds(ServerPlayer player, net.minecraft.world.item.Item item)
    {
        return player.getItemBySlot(EquipmentSlot.OFFHAND).is(item);
    }
}
