package carpet.logic.program;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bot programs kept as one JSON file each in a directory, plus the built-in presets.
 */
public class ProgramStorage
{
    private static final Logger LOG = LogManager.getLogger("CarpetLogic");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // An id names the program's file, so it may never contain a path separator or a dot.
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final String PRESETS = "/carpetlogic/presets.json";

    private final Path programDir;
    private final ActionSchema schema;
    private final Map<String, BotProgram> programs = new ConcurrentHashMap<>();
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

    public void loadAll()
    {
        programs.clear();
        if (!Files.isDirectory(programDir))
        {
            return;
        }
        try (Stream<Path> paths = Files.list(programDir))
        {
            paths.filter(p -> p.toString().endsWith(".json")).forEach(this::loadFile);
        }
        catch (IOException e)
        {
            LOG.error("Failed to list programs in {}", programDir, e);
        }
        LOG.info("Loaded {} bot programs", programs.size());
    }

    private void loadFile(Path path)
    {
        try
        {
            BotProgram program = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), BotProgram.class);
            if (program == null || !isValidId(program.getId()))
            {
                LOG.warn("Skipped program {}: missing or invalid id", path);
                return;
            }
            schema.validate(program.getActions());
            programs.put(program.getId(), program);
        }
        catch (IllegalArgumentException | JsonParseException e)
        {
            LOG.warn("Skipped program {}: {}", path, e.getMessage());
        }
        catch (IOException e)
        {
            LOG.warn("Failed to read program {}", path, e);
        }
    }

    public static boolean isValidId(String id)
    {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * @throws IllegalArgumentException when the id is not a valid program id or belongs to a preset,
     *                                  or the actions do not fit the action schema
     */
    public void save(BotProgram program)
    {
        if (!isValidId(program.getId()))
        {
            throw new IllegalArgumentException("Program ids are 1-64 letters, digits, '_' or '-'");
        }
        if (presets.stream().anyMatch(p -> p.getId().equals(program.getId())))
        {
            throw new IllegalArgumentException("'" + program.getId() + "' is a built-in preset");
        }
        schema.validate(program.getActions());
        programs.put(program.getId(), program);
        Path file = programDir.resolve(program.getId() + ".json");
        try
        {
            Files.writeString(file, GSON.toJson(program), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            LOG.error("Failed to save program {}", program.getId(), e);
        }
    }

    public boolean delete(String id)
    {
        if (!isValidId(id) || programs.remove(id) == null)
        {
            return false;
        }
        try
        {
            Files.deleteIfExists(programDir.resolve(id + ".json"));
            return true;
        }
        catch (IOException e)
        {
            LOG.error("Failed to delete program file {}", id, e);
            return false;
        }
    }

    public BotProgram getById(String id)
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

    public BotProgram getByName(String name)
    {
        return Stream.concat(programs.values().stream(), presets.stream())
                .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    public List<BotProgram> getAllPrograms()
    {
        return new ArrayList<>(programs.values());
    }

    public List<BotProgram> getPresets()
    {
        return Collections.unmodifiableList(presets);
    }

    public int getCount()
    {
        return programs.size();
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
