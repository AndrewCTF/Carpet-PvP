package carpet.pvp.autosetup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a player owned before a {@code /auto-setup} session, in the shape the session file
 * keeps it: where they stood, which game mode they played, every inventory slot as an encoded item
 * stack, the rules the session changed, and the blocks its arena wrote over.
 *
 * <p>The slots are the encoded stacks themselves, so that a custom name, a damage count or any
 * other data component survives the trip through the file. No Minecraft types here: the file is
 * read and written before a session exists and after the server is gone, which is what makes it
 * unit testable.</p>
 */
public record SavedState(Spot spot, String gamemode, int selected, List<String> slots, List<String> rules,
        ArenaBlocks blocks)
{
    /** Where a player stood before the session started. */
    public record Spot(String dimension, double x, double y, double z, float yaw, float pitch) {}

    public SavedState
    {
        slots = List.copyOf(slots);
        rules = List.copyOf(rules);
    }

    /** The same saved state with the blocks an arena has written over since. */
    public SavedState withBlocks(ArenaBlocks written)
    {
        return new SavedState(spot, gamemode, selected, slots, rules, written);
    }

    /** The same saved state with the rules a session has turned on since. */
    public SavedState withRules(List<String> changed)
    {
        return new SavedState(spot, gamemode, selected, slots, changed, blocks);
    }

    public JsonObject toJson()
    {
        JsonObject json = new JsonObject();
        JsonObject where = new JsonObject();
        where.addProperty("dimension", spot.dimension());
        where.addProperty("x", spot.x());
        where.addProperty("y", spot.y());
        where.addProperty("z", spot.z());
        where.addProperty("yaw", spot.yaw());
        where.addProperty("pitch", spot.pitch());
        json.add("spot", where);
        json.addProperty("gamemode", gamemode);
        json.addProperty("selected", selected);
        JsonArray items = new JsonArray();
        slots.forEach(items::add);
        json.add("slots", items);
        JsonArray changed = new JsonArray();
        rules.forEach(changed::add);
        json.add("rules", changed);
        json.add("blocks", blocks.toJson());
        return json;
    }

    public static SavedState fromJson(JsonElement json)
    {
        JsonObject saved = object(json, "a session file needs a saved state");
        Spot spot = spot(saved.get("spot"));
        return new SavedState(spot, requireString(saved, "gamemode"), requireInt(saved, "selected"),
                strings(saved.get("slots"), "slots"), strings(saved.get("rules"), "rules"),
                ArenaBlocks.fromJson(saved.get("blocks")));
    }

    private static Spot spot(JsonElement json)
    {
        JsonObject where = object(json, "a session file needs a spot to put a player back into");
        return new Spot(requireString(where, "dimension"), requireDouble(where, "x"), requireDouble(where, "y"),
                requireDouble(where, "z"), (float) requireDouble(where, "yaw"), (float) requireDouble(where, "pitch"));
    }

    private static JsonObject object(JsonElement json, String problem)
    {
        if (json == null || !json.isJsonObject()) throw new IllegalArgumentException(problem);
        return json.getAsJsonObject();
    }

    /** A list of strings, missing or empty when the file does not have it. */
    private static List<String> strings(JsonElement json, String what)
    {
        List<String> values = new ArrayList<>();
        if (json == null || json.isJsonNull()) return values;
        if (!json.isJsonArray()) throw new IllegalArgumentException("a session file needs '" + what + "' to be an array");
        for (JsonElement element : json.getAsJsonArray())
        {
            if (!element.isJsonPrimitive()) throw new IllegalArgumentException("a session file keeps '" + what + "' as text");
            values.add(element.getAsString());
        }
        return values;
    }

    private static String requireString(JsonObject json, String key)
    {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive())
        {
            throw new IllegalArgumentException("a session file needs '" + key + "'");
        }
        return value.getAsString();
    }

    private static int requireInt(JsonObject json, String key)
    {
        return (int) requireDouble(json, key);
    }

    private static double requireDouble(JsonObject json, String key)
    {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            throw new IllegalArgumentException("a session file needs the number '" + key + "'");
        }
        return value.getAsDouble();
    }
}
