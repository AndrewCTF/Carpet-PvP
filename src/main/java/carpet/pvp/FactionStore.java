package carpet.pvp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The faction registry as it is written into the world folder and read back from it, so that the
 * factions of a server survive a restart.
 *
 * <p>The file is a plain JSON object of three lists: the names of the factions, who is in which, and
 * which ones are allied. A missing or unreadable file is an empty registry rather than a failure, so
 * a world that has never had a match still starts.</p>
 *
 * <p>No Minecraft types beyond the server it is handed, so the format is unit testable.</p>
 */
public final class FactionStore
{
    /** File inside the world folder the factions are kept in. */
    public static final String FILE = "carpet-factions.json";

    /**
     * @param factions the names that exist
     * @param members  who is in which faction, by entity id
     * @param allies   the alliances of each faction, symmetric as the registry keeps them
     */
    public record Snapshot(List<String> factions, Map<UUID, String> members, Map<String, List<String>> allies)
    {
        public Snapshot
        {
            factions = List.copyOf(factions);
            members = Map.copyOf(members);
            allies = copy(allies);
        }

        private static Map<String, List<String>> copy(Map<String, List<String>> allies)
        {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            allies.forEach((faction, set) -> copy.put(faction, List.copyOf(set)));
            return Map.copyOf(copy);
        }
    }

    public static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), Map.of());

    private FactionStore()
    {
    }

    /** The file the factions of this world are kept in. */
    public static Path file(MinecraftServer server)
    {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
    }

    public static String toJson(Snapshot snapshot)
    {
        JsonArray names = new JsonArray();
        for (String faction : snapshot.factions())
        {
            names.add(faction);
        }
        JsonObject members = new JsonObject();
        for (Map.Entry<UUID, String> entry : snapshot.members().entrySet())
        {
            members.addProperty(entry.getKey().toString(), entry.getValue());
        }
        JsonObject allies = new JsonObject();
        for (Map.Entry<String, List<String>> entry : snapshot.allies().entrySet())
        {
            JsonArray list = new JsonArray();
            for (String other : entry.getValue())
            {
                list.add(other);
            }
            allies.add(entry.getKey(), list);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("factions", names);
        root.add("members", members);
        root.add("allies", allies);
        return root.toString();
    }

    /**
     * Reads what {@link #toJson} wrote. Anything the file does not hold is read as empty, and a file
     * that is not a faction file at all raises {@link IllegalArgumentException}.
     */
    public static Snapshot fromJson(String json)
    {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject())
        {
            throw new IllegalArgumentException("the faction file is a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        List<String> factions = strings(object.get("factions"));
        Map<UUID, String> members = new LinkedHashMap<>();
        JsonElement memberList = object.get("members");
        if (memberList != null && memberList.isJsonObject())
        {
            for (Map.Entry<String, JsonElement> entry : memberList.getAsJsonObject().entrySet())
            {
                members.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString());
            }
        }
        Map<String, List<String>> allies = new LinkedHashMap<>();
        JsonElement allyList = object.get("allies");
        if (allyList != null && allyList.isJsonObject())
        {
            for (Map.Entry<String, JsonElement> entry : allyList.getAsJsonObject().entrySet())
            {
                allies.put(entry.getKey(), strings(entry.getValue()));
            }
        }
        return new Snapshot(factions, members, allies);
    }

    /** Writes the registry into the world folder. */
    public static void save(MinecraftServer server, Snapshot snapshot) throws IOException
    {
        write(file(server), snapshot);
    }

    public static void write(Path file, Snapshot snapshot) throws IOException
    {
        if (file.getParent() != null)
        {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, toJson(snapshot), StandardCharsets.UTF_8);
    }

    /** Reads the registry back, or an empty one when the world has never had factions. */
    public static Snapshot load(MinecraftServer server)
    {
        return read(file(server));
    }

    public static Snapshot read(Path file)
    {
        try
        {
            if (!Files.isRegularFile(file)) return EMPTY;
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        }
        catch (IOException | RuntimeException e)
        {
            return EMPTY;
        }
    }

    private static List<String> strings(JsonElement json)
    {
        List<String> out = new ArrayList<>();
        if (json == null || !json.isJsonArray()) return out;
        for (JsonElement element : json.getAsJsonArray())
        {
            out.add(element.getAsString());
        }
        return out;
    }
}