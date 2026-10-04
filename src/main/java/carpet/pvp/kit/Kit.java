package carpet.pvp.kit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * A named loadout: the list of {@link KitEntry entries} a {@code /bot kit give} hands out.
 *
 * <p>Hand written kits are JSON like</p>
 * <pre>{@code
 * {
 *   "name": "sword",
 *   "items": [
 *     { "item": "minecraft:diamond_sword", "slot": 0,
 *       "enchantments": [ { "id": "minecraft:sharpness", "level": 2 } ] },
 *     { "item": "minecraft:golden_apple", "count": 4, "slot": 1 },
 *     { "item": "minecraft:shield", "slot": "offhand" }
 *   ]
 * }
 * }</pre>
 * <p>{@code slot} is a main inventory index, one of {@code head}, {@code chest}, {@code legs},
 * {@code feet} or {@code offhand}, or absent, in which case the entry takes the next free slot.</p>
 */
public record Kit(String name, List<KitEntry> entries)
{
    /** The longest a kit name may be; names double as file names in the world's kit folder. */
    private static final int MAX_NAME_LENGTH = 64;

    public Kit
    {
        if (!isValidName(name)) throw new IllegalArgumentException("invalid kit name: " + name);
        entries = List.copyOf(entries);
    }

    /** Kit names double as file names, so they are restricted to what is safe on disk. */
    public static boolean isValidName(String name)
    {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) return false;
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!allowed) return false;
        }
        return true;
    }

    public static Kit fromJson(String json, String defaultName)
    {
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject()) throw new IllegalArgumentException("a kit must be a JSON object");
        return fromJson(element.getAsJsonObject(), defaultName);
    }

    public static Kit fromJson(JsonObject json, String defaultName)
    {
        JsonElement name = json.get("name");
        JsonElement items = json.get("items");
        if (items == null || !items.isJsonArray()) throw new IllegalArgumentException("a kit needs an 'items' array");

        List<KitEntry> entries = new ArrayList<>();
        for (JsonElement item : items.getAsJsonArray())
        {
            if (!item.isJsonObject()) throw new IllegalArgumentException("a kit entry must be a JSON object");
            entries.add(KitEntry.fromJson(item.getAsJsonObject()));
        }
        return new Kit(name == null || name.isJsonNull() ? defaultName : name.getAsString(), entries);
    }
}