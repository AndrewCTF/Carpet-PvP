package carpet.pvp.kit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One item of a kit.
 *
 * <p>A hand written entry names the item, how many of it, any enchantments, and, for potions,
 * the potion type. An entry captured from a player instead carries the finished stack, so that
 * everything a player owned survives the trip through JSON.</p>
 */
public record KitEntry(KitSlot slot, String item, int count, List<KitEnchantment> enchantments, String potion, ItemStack stack)
{
    public KitEntry
    {
        if (stack == null && count < 1) throw new IllegalArgumentException("count must be positive: " + count);
        enchantments = List.copyOf(enchantments);
    }

    public static KitEntry ofStack(KitSlot slot, ItemStack stack)
    {
        if (stack.isEmpty()) throw new IllegalArgumentException("cannot kit an empty stack");
        return new KitEntry(slot, null, stack.getCount(), List.of(), null, stack.copy());
    }

    static KitEntry fromJson(JsonObject json)
    {
        int count = json.has("count") ? json.get("count").getAsInt() : 1;
        if (count < 1) throw new IllegalArgumentException("count must be positive: " + count);

        KitSlot slot = KitSlot.NEXT_FREE;
        JsonElement rawSlot = json.get("slot");
        if (rawSlot != null && !rawSlot.isJsonNull()) slot = KitSlot.of(rawSlot);

        List<KitEnchantment> enchantments = new ArrayList<>();
        JsonElement rawEnchantments = json.get("enchantments");
        if (rawEnchantments != null && !rawEnchantments.isJsonNull())
        {
            for (JsonElement element : rawEnchantments.getAsJsonArray())
            {
                JsonObject enchantment = element.getAsJsonObject();
                enchantments.add(new KitEnchantment(
                        requireString(enchantment, "id"),
                        requireInt(enchantment, "level")));
            }
        }

        JsonElement rawPotion = json.get("potion");
        return new KitEntry(slot, requireString(json, "item"), count, enchantments,
                rawPotion == null || rawPotion.isJsonNull() ? null : rawPotion.getAsString(), null);
    }

    /**
     * True when this entry can be built on this version of the game: the item it names is in the registry.
     * A kit file is shared by every version the mod runs on, and an item one of them does not have is
     * something to skip rather than a kit that will not build.
     */
    public boolean available(RegistryAccess registries)
    {
        return stack != null || available(item, registries == null ? id -> false
                : id -> item(registries).isPresent());
    }

    /**
     * Whether a registry has the item an entry names, for a caller that has nothing but the name. Kept apart
     * from the lookup so the decision a kit is skipped on can be made without a running game.
     *
     * @param inRegistry what the registry of this version says about an item id
     */
    static boolean available(String id, java.util.function.Predicate<String> inRegistry)
    {
        return id != null && inRegistry.test(id);
    }

    /**
     * Builds the stack this entry gives out. Registry lookups happen here rather than when the kit
     * is read, so a kit can be parsed without a running game.
     *
     * @throws IllegalArgumentException if the item, a potion or an enchantment is not in the registry
     */
    public ItemStack createStack(RegistryAccess registries)
    {
        if (stack != null) return stack.copy();

        ItemStack result = new ItemStack(item(registries).orElseThrow(
                () -> new IllegalArgumentException("no such item: " + item)), count);

        if (potion != null)
        {
            result.set(DataComponents.POTION_CONTENTS, new PotionContents(potion(potion, registries)));
        }

        if (!enchantments.isEmpty())
        {
            ItemEnchantments.Mutable builder = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            for (KitEnchantment enchantment : enchantments)
            {
                builder.set(enchantment(enchantment.id(), registries), enchantment.level());
            }
            EnchantmentHelper.setEnchantments(result, builder.toImmutable());
        }

        return result;
    }

    /** The item the entry names, or nothing on a version that does not have it. */
    private Optional<Holder.Reference<Item>> item(RegistryAccess registries)
    {
        return registries.lookupOrThrow(Registries.ITEM).get(Identifier.parse(item));
    }

    private static Holder<Potion> potion(String id, RegistryAccess registries)
    {
        return registries.lookupOrThrow(Registries.POTION)
                .get(Identifier.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("no such potion: " + id));
    }

    private static Holder<Enchantment> enchantment(String id, RegistryAccess registries)
    {
        return registries.lookupOrThrow(Registries.ENCHANTMENT)
                .get(Identifier.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("no such enchantment: " + id));
    }

    private static String requireString(JsonObject json, String key)
    {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("expected a string '" + key + "'");
        return value.getAsString();
    }

    private static int requireInt(JsonObject json, String key)
    {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("expected a number '" + key + "'");
        return value.getAsInt();
    }
}