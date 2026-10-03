package carpet.pvp.kit;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

/**
 * Where a kit entry ends up: a fixed index of the main inventory, an armour or offhand position,
 * or, when the kit does not say, the first free inventory slot.
 *
 * <p>In JSON a slot is either the inventory index or the name of the position. A name is what
 * distinguishes the two: {@code 0} and {@code "chest"} both come back as a fixed slot.</p>
 */
public record KitSlot(int index, EquipmentSlot equipment)
{
    private static final int AUTOMATIC = -1;

    /** For entries that do not name a slot; they are placed in the order they are listed. */
    public static final KitSlot NEXT_FREE = new KitSlot(AUTOMATIC, null);

    public static KitSlot ofIndex(int index)
    {
        if (index < 0 || index >= Inventory.INVENTORY_SIZE)
            throw new IllegalArgumentException("inventory slot out of range: " + index);
        return new KitSlot(index, null);
    }

    public static KitSlot ofEquipment(EquipmentSlot equipment)
    {
        if (!equipment.isArmor() && equipment != EquipmentSlot.OFFHAND)
            throw new IllegalArgumentException("not an armour or offhand slot: " + equipment.getName());
        return new KitSlot(AUTOMATIC, equipment);
    }

    public static KitSlot of(String name)
    {
        return switch (name)
        {
            case "head" -> ofEquipment(EquipmentSlot.HEAD);
            case "chest" -> ofEquipment(EquipmentSlot.CHEST);
            case "legs" -> ofEquipment(EquipmentSlot.LEGS);
            case "feet" -> ofEquipment(EquipmentSlot.FEET);
            case "offhand" -> ofEquipment(EquipmentSlot.OFFHAND);
            default -> throw new IllegalArgumentException("unknown slot: " + name);
        };
    }

    /** Reads a slot written as either an index or the name of a position. */
    public static KitSlot of(JsonElement json)
    {
        if (json == null || !json.isJsonPrimitive()) throw new IllegalArgumentException("a slot is an inventory index or a position name");
        return json.getAsJsonPrimitive().isNumber() ? ofIndex(json.getAsInt()) : of(json.getAsString());
    }

    public JsonElement toJson()
    {
        return isEquipment()
                ? new JsonPrimitive(equipment().name().toLowerCase(Locale.ROOT))
                : new JsonPrimitive(Integer.valueOf(index()));
    }

    public boolean isAutomatic()
    {
        return equipment == null && index == AUTOMATIC;
    }

    public boolean isEquipment()
    {
        return equipment != null;
    }
}