package carpet.logic.program;

import carpet.logic.program.BotAction.ActionType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Bot programs kept as one JSON file each in a directory, plus the built-in presets.
 */
public class ProgramStorage
{
    private static final Logger LOG = LogManager.getLogger("CarpetLogic");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path programDir;
    private final Map<String, BotProgram> programs = new ConcurrentHashMap<>();
    private final List<BotProgram> presets = new ArrayList<>();

    public ProgramStorage(Path programDir)
    {
        this.programDir = programDir;
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
            if (program != null && program.getId() != null)
            {
                programs.put(program.getId(), program);
            }
        }
        catch (Exception e)
        {
            LOG.warn("Failed to load program {}", path, e);
        }
    }

    public void save(BotProgram program)
    {
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
        if (programs.remove(id) == null)
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
        presets.add(foreverPreset("preset_wtap", "W-Tap", "Sprint, attack, release sprint, re-sprint",
                new BotAction(ActionType.SPRINT).withParam("mode", "start"),
                new BotAction(ActionType.MOVE).withParam("direction", "forward").withDuration(3),
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.SPRINT).withParam("mode", "stop"),
                new BotAction(ActionType.DELAY).withParam("ticks", 2),
                new BotAction(ActionType.SPRINT).withParam("mode", "start"),
                new BotAction(ActionType.DELAY).withParam("ticks", 6)));
        presets.add(foreverPreset("preset_blockhit", "Block Hit", "Attack, sword block, repeat",
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.SWORD_BLOCK).withParam("duration", 4),
                new BotAction(ActionType.DELAY).withParam("ticks", 2)));
        presets.add(foreverPreset("preset_critchain", "Crit Chain", "Jump, falling critical attack, repeat",
                new BotAction(ActionType.SPRINT).withParam("mode", "start"),
                new BotAction(ActionType.ATTACK_CRIT).withParam("mode", "once"),
                new BotAction(ActionType.DELAY).withParam("ticks", 12)));
        presets.add(foreverPreset("preset_circlestrafe", "Circle Strafe", "Strafe around the target while attacking",
                new BotAction(ActionType.SPRINT).withParam("mode", "start"),
                new BotAction(ActionType.MOVE).withParam("direction", "left").withDuration(5),
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.MOVE).withParam("direction", "forward").withDuration(3),
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.MOVE).withParam("direction", "right").withDuration(5),
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.MOVE).withParam("direction", "forward").withDuration(3)));
        presets.add(foreverPreset("preset_shieldbreak", "Shield Break", "Axe hit to disable the shield, then sword hits",
                new BotAction(ActionType.HOTBAR).withParam("slot", 2),
                new BotAction(ActionType.SPRINT).withParam("mode", "start"),
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.DELAY).withParam("ticks", 3),
                new BotAction(ActionType.HOTBAR).withParam("slot", 1),
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.DELAY).withParam("ticks", 2),
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.DELAY).withParam("ticks", 10)));
        presets.add(foreverPreset("preset_combo", "Combo Practice", "W-tap, strafe and critical attacks combined",
                new BotAction(ActionType.SPRINT).withParam("mode", "start"),
                new BotAction(ActionType.MOVE).withParam("direction", "forward").withDuration(3),
                new BotAction(ActionType.ATTACK_CRIT).withParam("mode", "once"),
                new BotAction(ActionType.SPRINT).withParam("mode", "stop"),
                new BotAction(ActionType.DELAY).withParam("ticks", 1),
                new BotAction(ActionType.SPRINT).withParam("mode", "start"),
                new BotAction(ActionType.MOVE).withParam("direction", "left").withDuration(4),
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.MOVE).withParam("direction", "right").withDuration(4),
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.SWORD_BLOCK).withParam("duration", 3),
                new BotAction(ActionType.ATTACK).withParam("mode", "once"),
                new BotAction(ActionType.DELAY).withParam("ticks", 5)));

        BotProgram dummy = new BotProgram("preset_dummy", "Target Dummy", "Equips armor and stands still");
        dummy.setPreset(true);
        dummy.setActions(List.of(
                new BotAction(ActionType.EQUIP_ARMOR).withParam("set", "diamond"),
                new BotAction(ActionType.LOOK_DIRECTION).withParam("direction", "north")));
        presets.add(dummy);
    }

    private static BotProgram foreverPreset(String id, String name, String description, BotAction... body)
    {
        BotAction loop = new BotAction(ActionType.FOREVER);
        loop.setChildren(List.of(body));
        BotProgram program = new BotProgram(id, name, description);
        program.setPreset(true);
        program.setActions(List.of(loop));
        return program;
    }
}
