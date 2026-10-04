package carpet.logic.program;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bot programs kept as one JSON file each in a folder a person can look into, plus the built-in presets.
 * <p>
 * A file is named after its program, {@code <safe name>.json}, in the folder itself or in a subfolder one
 * level down, and is renamed when the program is: the file somebody looks for carries the name the editor
 * shows. What stays the same through a rename is the id inside the file, which is how the editor and a second
 * tab keep hold of a program. A program whose graph does not compile yet is kept as a draft: its file holds the
 * graph and the reason, and no actions.
 */
public class ProgramStorage
{
    private static final Logger LOG = LogManager.getLogger("CarpetLogic");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // An id may be asked for in a URL and is told apart from a path by having neither a separator nor a dot.
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_-]+");
    /** Names Windows keeps for devices, which no file there can have. */
    private static final Set<String> DEVICES = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5",
            "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    public static final String UNTITLED = "Untitled";
    private static final int MAX_FILE_NAME = 64;
    private static final int MAX_FOLDER_NAME = 32;
    private static final int MAX_FILES = 2000;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private static final String PRESETS = "/carpetlogic/presets.json";

    /**
     * What became of a save.
     *
     * @param program  the program as the storage now holds it, or as it held it when the save was refused
     * @param written  whether the file was written; a save that changes nothing leaves the file alone
     * @param conflict whether the save was refused because the program has been saved by somebody else since
     *                 the copy being saved was read
     */
    public record Saved(BotProgram program, boolean written, boolean conflict)
    {
    }

    private final Path programDir;
    private final ActionSchema schema;
    private final Map<String, BotProgram> programs = new LinkedHashMap<>();
    private final List<BotProgram> presets = new ArrayList<>();

    public ProgramStorage(Path programDir, ActionSchema schema)
    {
        this.programDir = programDir;
        this.schema = schema;
        try
        {
            Files.createDirectories(programDir);
        }
        catch (IOException e)
        {
            LOG.error("Failed to create program directory {}", programDir, e);
        }
        loadPresets();
    }

    // ── Reading the folder ──────────────────────────────────────

    /**
     * Reads the folder again: its own files and those of its subfolders, one level down. A file somebody put
     * there by hand is a program like any other.
     */
    public synchronized void loadAll()
    {
        programs.clear();
        if (!Files.isDirectory(programDir))
        {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.list(programDir))
        {
            for (Path path : paths.sorted().toList())
            {
                if (Files.isDirectory(path) && safe(path.getFileName().toString(), MAX_FOLDER_NAME).equals(path.getFileName().toString()))
                {
                    try (Stream<Path> inside = Files.list(path))
                    {
                        inside.sorted().filter(ProgramStorage::isProgramFile).forEach(files::add);
                    }
                }
                else if (isProgramFile(path))
                {
                    files.add(path);
                }
            }
        }
        catch (IOException e)
        {
            LOG.error("Failed to list programs in {}", programDir, e);
        }
        if (files.size() > MAX_FILES)
        {
            LOG.warn("{} holds {} programs; only the first {} are loaded", programDir, files.size(), MAX_FILES);
            files = files.subList(0, MAX_FILES);
        }
        List<BotProgram> read = new ArrayList<>();
        files.forEach(file -> readFile(file, read));
        // The file a save would have written keeps its id against a copy of it somebody made by hand, and a file
        // that carries an id keeps it against one that has to be given one.
        read.stream().filter(program -> hasId(program) && stem(program.getFile()).equals(fileName(program.getName()))).forEach(this::register);
        read.stream().filter(program -> hasId(program) && !programs.containsValue(program)).forEach(this::register);
        read.stream().filter(program -> !hasId(program)).forEach(this::register);
    }

    private static boolean isProgramFile(Path path)
    {
        String name = path.getFileName().toString();
        return name.endsWith(".json") && !name.startsWith(".") && Files.isRegularFile(path);
    }

    private static boolean hasId(BotProgram program)
    {
        return program.getId() != null && !program.getId().isEmpty();
    }

    private void readFile(Path path, List<BotProgram> read)
    {
        try
        {
            if (Files.size(path) > MAX_FILE_BYTES)
            {
                LOG.warn("Skipped program {}: larger than {} bytes", path, MAX_FILE_BYTES);
                return;
            }
            BotProgram program = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), BotProgram.class);
            if (program == null)
            {
                LOG.warn("Skipped program {}: the file is empty", path);
                return;
            }
            if (hasId(program) && (!isValidId(program.getId()) || isPresetId(program.getId())))
            {
                LOG.warn("Skipped program {}: its id is not one a program can have", path);
                return;
            }
            if (program.getName() == null || program.getName().isBlank())
            {
                program.setName(stem(path));
            }
            program.setPreset(false);
            program.setFolder(folderOf(path));
            program.setFile(path);
            if (program.getError() == null)
            {
                try
                {
                    schema.validate(program.getActions());
                }
                catch (IllegalArgumentException e)
                {
                    // Shown as a draft rather than hidden: somebody can open it and put it right.
                    program.setError("Its actions do not fit this server: " + e.getMessage());
                }
            }
            if (program.getError() != null)
            {
                program.setActions(new ArrayList<>());
            }
            read.add(program);
        }
        catch (JsonParseException | IllegalStateException e)
        {
            LOG.warn("Skipped program {}: {}", path, e.getMessage());
        }
        catch (IOException e)
        {
            LOG.warn("Failed to read program {}", path, e);
        }
    }

    private void register(BotProgram program)
    {
        if (!hasId(program) || programs.containsKey(program.getId()))
        {
            // A file written by hand, or a copy of another one: its place in the folder is what tells it apart.
            program.setId(derivedId(program.getFile()));
        }
        programs.put(program.getId(), program);
    }

    private String derivedId(Path path)
    {
        String folder = folderOf(path);
        String base = safe("file-" + (folder.isEmpty() ? "" : folder + "-") + stem(path), MAX_FILE_NAME);
        String id = base;
        for (int n = 2; programs.containsKey(id) || isPresetId(id); n++)
        {
            String suffix = "-" + n;
            id = base.substring(0, Math.min(base.length(), MAX_FILE_NAME - suffix.length())) + suffix;
        }
        return id;
    }

    private String folderOf(Path file)
    {
        Path parent = file.getParent();
        return parent == null || parent.equals(programDir) ? "" : parent.getFileName().toString();
    }

    private static String stem(Path file)
    {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }

    // ── Names ───────────────────────────────────────────────────

    public static boolean isValidId(String id)
    {
        return id != null && ID.matcher(id).matches();
    }

    private boolean isPresetId(String id)
    {
        return presets.stream().anyMatch(preset -> preset.getId().equalsIgnoreCase(id));
    }

    /**
     * A name as part of a path: letters, digits, '_' and '-' are kept and every run of anything else becomes
     * one '-', so that what comes out can hold neither a separator nor a dot.
     *
     * @return the safe name, or empty when nothing of the name was safe
     */
    public static String safe(String name, int longest)
    {
        String safe = UNSAFE.matcher(name == null ? "" : name).replaceAll("-");
        safe = safe.replaceAll("^-+|-+$", "");
        if (safe.length() > longest)
        {
            safe = safe.substring(0, longest).replaceAll("-+$", "");
        }
        return DEVICES.contains(safe.toLowerCase(Locale.ROOT)) ? safe + "-program" : safe;
    }

    /** The name of the file a program of that name is kept in, without its extension. */
    public static String fileName(String programName)
    {
        String safe = safe(programName, MAX_FILE_NAME);
        return safe.isEmpty() ? "program" : safe;
    }

    /** The name of a subfolder, or empty for the programs folder itself. */
    public static String folderName(String folder)
    {
        return safe(folder, MAX_FOLDER_NAME);
    }

    // No two programs share a file name, whatever folder they are in and whatever the case, so that a name finds
    // one program and a file system that ignores case can hold them all. A preset's id is taken as well.
    private boolean taken(String fileName, String exceptId)
    {
        if (isPresetId(fileName))
        {
            return true;
        }
        for (BotProgram other : programs.values())
        {
            if (!other.getId().equals(exceptId) && other.getFile() != null && stem(other.getFile()).equalsIgnoreCase(fileName))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the name itself if no other program has its file name, otherwise the name with the first number
     *         after it that is free
     */
    private String uniqueName(String name, String exceptId)
    {
        String unique = name;
        for (int n = 2; taken(fileName(unique), exceptId); n++)
        {
            unique = name + " " + n;
        }
        return unique;
    }

    // ── Saving ──────────────────────────────────────────────────

    /**
     * @throws IllegalArgumentException when the id is not a valid program id or belongs to a preset
     */
    public Saved save(BotProgram program)
    {
        return save(program, null, false);
    }

    /**
     * Saves a program under its id. Actions that do not fit the schema do not stop it: the program is kept as a
     * draft with the reason. Its name is made unique, and its file is named after it.
     *
     * @param baseUpdatedAt the {@code updatedAt} of the copy this save started from, or null to save regardless
     * @param force         saves over a newer copy
     * @throws IllegalArgumentException when the id is not a valid program id or belongs to a preset
     */
    public synchronized Saved save(BotProgram program, Long baseUpdatedAt, boolean force)
    {
        if (!isValidId(program.getId()))
        {
            throw new IllegalArgumentException("Program ids are 1-64 letters, digits, '_' or '-'");
        }
        if (isPresetId(program.getId()))
        {
            throw new IllegalArgumentException("'" + program.getId() + "' is a built-in preset");
        }
        BotProgram existing = programs.get(program.getId());
        if (existing != null && baseUpdatedAt != null && !force && existing.getUpdatedAt() != baseUpdatedAt)
        {
            return new Saved(existing, false, true);
        }
        if (program.getError() == null)
        {
            try
            {
                schema.validate(program.getActions());
            }
            catch (IllegalArgumentException e)
            {
                program.setError(e.getMessage());
            }
        }
        if (program.getError() != null)
        {
            program.setActions(new ArrayList<>());
        }
        String name = program.getName() == null || program.getName().isBlank() ? UNTITLED : program.getName().strip();
        program.setName(uniqueName(name, program.getId()));
        program.setFolder(existing != null ? existing.getFolder() : folderName(program.getFolder()));
        program.setPreset(false);
        program.setGraphData(withoutNulls(program.getGraphData()));
        if (existing != null && sameContent(existing, program))
        {
            return new Saved(existing, false, false);
        }
        long now = System.currentTimeMillis();
        program.setCreatedAt(existing != null ? existing.getCreatedAt() : now);
        // Later than the copy it replaces even within the same millisecond: this is what a stale save is told by.
        program.setUpdatedAt(existing != null ? Math.max(now, existing.getUpdatedAt() + 1) : now);
        Path folder = program.getFolder().isEmpty() ? programDir : programDir.resolve(program.getFolder());
        Path file = folder.resolve(fileName(program.getName()) + ".json");
        try
        {
            write(file, GSON.toJson(program));
            if (existing != null && existing.getFile() != null && !existing.getFile().equals(file))
            {
                Files.deleteIfExists(existing.getFile());
            }
        }
        catch (IOException e)
        {
            LOG.error("Failed to save program {} to {}", program.getName(), file, e);
            throw new IllegalStateException("The program could not be written to " + location(file) + ": " + e.getMessage());
        }
        program.setFile(file);
        programs.put(program.getId(), program);
        return new Saved(program, true, false);
    }

    // A file does not hold the members of the graph that are null, so the copy in memory does not either:
    // what is compared with the next save is what reading the file again would give.
    private static JsonElement withoutNulls(JsonElement json)
    {
        if (json == null || json.isJsonNull())
        {
            return null;
        }
        if (json.isJsonArray())
        {
            JsonArray array = new JsonArray();
            json.getAsJsonArray().forEach(item -> array.add(item.isJsonNull() ? item : withoutNulls(item)));
            return array;
        }
        if (json.isJsonObject())
        {
            JsonObject object = new JsonObject();
            json.getAsJsonObject().entrySet().forEach(member ->
            {
                if (!member.getValue().isJsonNull())
                {
                    object.add(member.getKey(), withoutNulls(member.getValue()));
                }
            });
            return object;
        }
        return json;
    }

    private static boolean sameContent(BotProgram a, BotProgram b)
    {
        return Objects.equals(a.getName(), b.getName()) && Objects.equals(a.getFolder(), b.getFolder())
                && Objects.equals(a.getError(), b.getError()) && Objects.equals(a.getDescription(), b.getDescription())
                && Objects.equals(a.getGraphData(), b.getGraphData())
                && GSON.toJson(a.getActions()).equals(GSON.toJson(b.getActions()));
    }

    // Written beside the file and moved over it, so a crash half way leaves the old program, not half of a new one.
    private static void write(Path file, String json) throws IOException
    {
        Files.createDirectories(file.getParent());
        Path fresh = Files.createTempFile(file.getParent(), ".saving-", ".tmp");
        try
        {
            Files.writeString(fresh, json, StandardCharsets.UTF_8);
            try
            {
                Files.move(fresh, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(fresh, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(fresh);
        }
    }

    /**
     * Puts a program into a subfolder of the programs folder, or back into the folder itself.
     *
     * @param folder the subfolder's name, made safe; empty for the programs folder
     * @return the program where it now is, or null when there is no such program
     */
    public synchronized BotProgram move(String id, String folder)
    {
        BotProgram program = isValidId(id) ? programs.get(id) : null;
        if (program == null)
        {
            return null;
        }
        String target = folderName(folder);
        if (target.equals(program.getFolder()))
        {
            return program;
        }
        Path from = program.getFile();
        Path to = (target.isEmpty() ? programDir : programDir.resolve(target)).resolve(from.getFileName());
        try
        {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
            removeIfEmpty(from.getParent());
        }
        catch (IOException e)
        {
            LOG.error("Failed to move program {} to {}", from, to, e);
            throw new IllegalStateException("The program could not be moved to " + location(to) + ": " + e.getMessage());
        }
        program.setFolder(target);
        program.setFile(to);
        return program;
    }

    public synchronized boolean delete(String id)
    {
        BotProgram program = isValidId(id) ? programs.remove(id) : null;
        if (program == null)
        {
            return false;
        }
        try
        {
            Files.deleteIfExists(program.getFile());
            removeIfEmpty(program.getFile().getParent());
            return true;
        }
        catch (IOException e)
        {
            LOG.error("Failed to delete program file {}", program.getFile(), e);
            return false;
        }
    }

    // A subfolder is there for the programs in it, and goes with the last of them.
    private void removeIfEmpty(Path folder) throws IOException
    {
        if (folder == null || folder.equals(programDir))
        {
            return;
        }
        try (Stream<Path> left = Files.list(folder))
        {
            if (left.findAny().isPresent())
            {
                return;
            }
        }
        Files.deleteIfExists(folder);
    }

    // ── Asking ──────────────────────────────────────────────────

    public synchronized BotProgram getById(String id)
    {
        if (!isValidId(id))
        {
            return null;
        }
        BotProgram program = programs.get(id);
        if (program != null)
        {
            return program;
        }
        return presets.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
    }

    /**
     * Finds a program by its name, or by {@code folder/name}. A name alone means the program of that name in
     * the programs folder itself before one in a subfolder, and a saved program before a preset.
     */
    public synchronized BotProgram getByName(String name)
    {
        BotProgram named = Stream.concat(
                        Stream.concat(programs.values().stream().filter(p -> p.getFolder().isEmpty()),
                                programs.values().stream().filter(p -> !p.getFolder().isEmpty())),
                        presets.stream())
                .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        int slash = name.indexOf('/');
        if (named != null || slash < 0)
        {
            return named;
        }
        String folder = name.substring(0, slash);
        String rest = name.substring(slash + 1);
        return programs.values().stream()
                .filter(p -> p.getFolder().equalsIgnoreCase(folder) && p.getName().equalsIgnoreCase(rest))
                .findFirst().orElse(null);
    }

    public synchronized List<BotProgram> getAllPrograms()
    {
        return new ArrayList<>(programs.values());
    }

    public List<BotProgram> getPresets()
    {
        return Collections.unmodifiableList(presets);
    }

    public synchronized int getCount()
    {
        return programs.size();
    }

    /** Where the programs are kept, as somebody on the server's machine would open it. */
    public String location()
    {
        return location(programDir);
    }

    /** Where a program's file is, or null for a preset, which has none. */
    public String location(BotProgram program)
    {
        return program.getFile() == null ? null : location(program.getFile());
    }

    // Relative to the directory the server runs in when it is inside it, which is how its admin thinks of it.
    private static String location(Path path)
    {
        Path absolute = path.toAbsolutePath().normalize();
        Path server = Path.of("").toAbsolutePath().normalize();
        Path shown = absolute.startsWith(server) ? server.relativize(absolute) : absolute;
        return shown.toString().replace('\\', '/');
    }

    private void loadPresets()
    {
        try (InputStream in = ProgramStorage.class.getResourceAsStream(PRESETS))
        {
            for (BotProgram preset : GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), BotProgram[].class))
            {
                schema.validate(preset.getActions());
                preset.setPreset(true);
                presets.add(preset);
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Cannot read " + PRESETS, e);
        }
    }
}
