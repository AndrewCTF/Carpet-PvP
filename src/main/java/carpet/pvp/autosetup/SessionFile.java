package carpet.pvp.autosetup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The file a {@code /auto-setup} session leaves on disk for as long as it runs: what the player
 * owned, written before their inventory is touched, so that a crash cannot cost them anything.
 * A clean stop deletes it; a login finds it still there and undoes the whole session from it.
 *
 * <p>One player per file, named after them, in the world's {@code carpet-autosetup} folder. The
 * format is JSON with no Minecraft types in it, so both ends of it are unit testable. What a session
 * is doing is of no use to whoever reads the file back: there is no session then, only a player to
 * give their things to.</p>
 */
public record SessionFile(int version, String player, SavedState saved)
{
    /** Bumped whenever the shape changes; a file of another version is not read. */
    public static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String toJson()
    {
        JsonObject json = new JsonObject();
        json.addProperty("version", version);
        json.addProperty("player", player);
        json.add("saved", saved.toJson());
        return GSON.toJson(json);
    }

    /**
     * Reads a session file.
     *
     * @throws IllegalArgumentException with the reason when the file is not one of ours, which is
     *         what keeps a broken file from being mistaken for an empty session
     */
    public static SessionFile parse(String text)
    {
        JsonElement parsed;
        try
        {
            parsed = JsonParser.parseString(text);
        }
        catch (RuntimeException e)
        {
            throw new IllegalArgumentException("a session file must be JSON: " + e.getMessage());
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("a session file must be a JSON object");
        JsonObject json = parsed.getAsJsonObject();
        int version = json.has("version") ? json.get("version").getAsInt() : 0;
        if (version != VERSION)
        {
            throw new IllegalArgumentException("a session file of version " + version + " cannot be read by this version");
        }
        JsonElement player = json.get("player");
        if (player == null || !player.isJsonPrimitive() || player.getAsString().isBlank())
        {
            throw new IllegalArgumentException("a session file needs the name of the player it belongs to");
        }
        return new SessionFile(version, player.getAsString(), SavedState.fromJson(json.get("saved")));
    }
}
