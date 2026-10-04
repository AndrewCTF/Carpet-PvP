package carpet.pvp.autosetup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What an arena replaced: the block state every position it wrote over held before, in the order
 * the positions were changed. Kept as plain numbers and written to the session file as it stands,
 * so a crash can be undone from the file alone. No Minecraft types, so the bookkeeping is unit
 * testable.
 */
public final class ArenaBlocks
{
    /** One block the arena overwrote: where it was and what used to be there. */
    public record Entry(int x, int y, int z, int state) {}

    /** The smallest box that holds every entry. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        /** True while the given box holds every entry of the list. */
        public boolean holds(Box other)
        {
            return other.minX >= minX && other.maxX <= maxX
                    && other.minY >= minY && other.maxY <= maxY
                    && other.minZ >= minZ && other.maxZ <= maxZ;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Remembers what a position held before the arena was built over it. */
    public void add(int x, int y, int z, int state)
    {
        entries.add(new Entry(x, y, z, state));
    }

    public List<Entry> entries()
    {
        return List.copyOf(entries);
    }

    public int size()
    {
        return entries.size();
    }

    public boolean isEmpty()
    {
        return entries.isEmpty();
    }

    public void clear()
    {
        entries.clear();
    }

    /** The smallest box holding every entry, or null while there are none. */
    public Box bounds()
    {
        if (entries.isEmpty()) return null;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Entry entry : entries)
        {
            minX = Math.min(minX, entry.x());
            minY = Math.min(minY, entry.y());
            minZ = Math.min(minZ, entry.z());
            maxX = Math.max(maxX, entry.x());
            maxY = Math.max(maxY, entry.y());
            maxZ = Math.max(maxZ, entry.z());
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public JsonArray toJson()
    {
        JsonArray array = new JsonArray();
        for (Entry entry : entries)
        {
            JsonObject block = new JsonObject();
            block.addProperty("x", entry.x());
            block.addProperty("y", entry.y());
            block.addProperty("z", entry.z());
            block.addProperty("state", entry.state());
            array.add(block);
        }
        return array;
    }

    /** Reads a list as {@link #toJson()} wrote it; an empty or missing list is an empty arena. */
    public static ArenaBlocks fromJson(JsonElement json)
    {
        ArenaBlocks blocks = new ArenaBlocks();
        if (json == null || json.isJsonNull()) return blocks;
        if (!json.isJsonArray()) throw new IllegalArgumentException("the blocks of a session file must be an array");
        for (JsonElement element : json.getAsJsonArray())
        {
            if (!element.isJsonObject()) throw new IllegalArgumentException("a block of a session file must be an object");
            JsonObject block = element.getAsJsonObject();
            blocks.add(requireInt(block, "x"), requireInt(block, "y"), requireInt(block, "z"),
                    requireInt(block, "state"));
        }
        return blocks;
    }

    private static int requireInt(JsonObject json, String key)
    {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            throw new IllegalArgumentException("a session file needs the number '" + key + "' on every block");
        }
        return value.getAsInt();
    }
}
