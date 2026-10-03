package carpet.pvp.kit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * Where kits live: the ones shipped with the mod, read from {@code assets/carpet/kits}, and the
 * ones a server made itself, read from {@code carpet-kits} in the world folder.
 *
 * <p>Built-in kits are read as plain item descriptions (see {@link Kit#fromJson}). A kit a player
 * saved keeps the finished item stacks instead, so that custom names, damage and any other data
 * component survive the round trip through JSON.</p>
 */
public final class KitStore
{
    /** One built-in kit per PvP mode. */
    private static final List<String> BUILT_IN = List.of("sword", "axe", "smp", "mace", "crystal");

    private static final String RESOURCE_FOLDER = "assets/carpet/kits/";
    private static final String CUSTOM_FOLDER = "carpet-kits";

    private static final Map<MinecraftServer, KitStore> STORES = new WeakHashMap<>();

    private final Map<String, Kit> builtIn = new LinkedHashMap<>();
    private final Map<String, Kit> custom = new TreeMap<>();
    private final Map<String, String> problems = new LinkedHashMap<>();
    private final Path folder;
    private final RegistryAccess registries;

    private KitStore(Path folder, RegistryAccess registries)
    {
        this.folder = folder;
        this.registries = registries;
        for (String name : BUILT_IN) readBuiltIn(name);
        readCustom();
    }

    public static KitStore of(MinecraftServer server)
    {
        return STORES.computeIfAbsent(server, s -> new KitStore(
                s.getWorldPath(LevelResource.ROOT).resolve(CUSTOM_FOLDER), s.registryAccess()));
    }

    /** Every kit name that can be given out; a custom kit shadows a built-in of the same name. */
    public List<String> names()
    {
        List<String> names = new ArrayList<>(BUILT_IN);
        for (String name : custom.keySet())
        {
            if (!names.contains(name)) names.add(name);
        }
        return names;
    }

    public List<String> builtInNames()
    {
        return BUILT_IN;
    }

    public List<String> customNames()
    {
        return List.copyOf(custom.keySet());
    }

    public Optional<Kit> get(String name)
    {
        Kit kit = custom.get(name);
        return Optional.ofNullable(kit != null ? kit : builtIn.get(name));
    }

    /** Kits that could not be read, keyed by name, with the reason as the value. */
    public Map<String, String> problems()
    {
        return Map.copyOf(problems);
    }

    public boolean delete(String name)
    {
        if (custom.remove(name) == null) return false;
        try
        {
            Files.deleteIfExists(folder.resolve(name + ".json"));
        }
        catch (IOException e)
        {
            problems.put(name, "could not delete the kit file: " + e.getMessage());
            return false;
        }
        return true;
    }

    public void save(Kit kit) throws IOException
    {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(kit.name() + ".json"), toJson(kit), StandardCharsets.UTF_8);
        custom.put(kit.name(), kit);
    }

    /** Reads the custom kits again, picking up kits written or edited outside the commands. */
    public void reload()
    {
        custom.clear();
        readCustom();
    }

    private String toJson(Kit kit)
    {
        JsonArray items = new JsonArray();
        for (KitEntry entry : kit.entries())
        {
            JsonObject item = new JsonObject();
            item.add("slot", entry.slot().toJson());
            item.add("stack", encode(kit.name(), entry.stack()));
            items.add(item);
        }
        JsonObject root = new JsonObject();
        root.addProperty("name", kit.name());
        root.add("items", items);
        return root.toString();
    }

    private void readBuiltIn(String name)
    {
        try (InputStream in = KitStore.class.getClassLoader().getResourceAsStream(RESOURCE_FOLDER + name + ".json"))
        {
            if (in == null)
            {
                problems.put(name, "the built-in kit file is missing");
                return;
            }
            builtIn.put(name, Kit.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), name));
        }
        catch (Exception e)
        {
            problems.put(name, e.getMessage());
        }
    }

    private void readCustom()
    {
        if (!Files.isDirectory(folder)) return;
        try (var files = Files.list(folder))
        {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList())
            {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".json")) continue;
                String name = fileName.substring(0, fileName.length() - ".json".length());
                try
                {
                    custom.put(name, readCustomKit(Files.readString(file, StandardCharsets.UTF_8), name));
                }
                catch (Exception e)
                {
                    problems.put(name, "cannot read the kit: " + e.getMessage());
                }
            }
        }
        catch (IOException | UncheckedIOException e)
        {
            throw new IllegalStateException("could not list the kits in " + folder, e);
        }
    }

    private Kit readCustomKit(String json, String name)
    {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) throw new IllegalArgumentException("a kit must be a JSON object");
        JsonObject object = root.getAsJsonObject();
        JsonElement items = object.get("items");
        if (items == null || !items.isJsonArray()) throw new IllegalArgumentException("a kit needs an 'items' array");

        List<KitEntry> entries = new ArrayList<>();
        for (JsonElement element : items.getAsJsonArray())
        {
            JsonObject item = element.getAsJsonObject();
            entries.add(KitEntry.ofStack(
                    KitSlot.of(item.get("slot")),
                    decode(name, item.get("stack"))));
        }
        JsonElement kitName = object.get("name");
        return new Kit(kitName == null ? name : kitName.getAsString(), entries);
    }

    private JsonElement encode(String kitName, ItemStack stack)
    {
        if (stack == null) throw new IllegalArgumentException("kit " + kitName + " has an entry that was never built");
        return ItemStack.CODEC.encodeStart(ops(), stack)
                .getOrThrow(message -> new IllegalArgumentException("cannot write " + kitName + ": " + message));
    }

    private ItemStack decode(String kitName, JsonElement json)
    {
        if (json == null) throw new IllegalArgumentException("a kit entry needs a 'stack'");
        return ItemStack.CODEC.parse(ops(), json)
                .getOrThrow(message -> new IllegalArgumentException("cannot read " + kitName + ": " + message));
    }

    private RegistryOps<JsonElement> ops()
    {
        return registries.createSerializationContext(JsonOps.INSTANCE);
    }
}