package carpet.logic.program;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramStorageTest
{
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "../evil", "..", ".", "a/b", "a\\b", "/etc/passwd", "a.json", "a b", "a\u0000b", "café",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void idsOutsideThePatternAreRejected(String id, @TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectory(root.resolve("programs"));
        ProgramStorage storage = new ProgramStorage(dir, ActionSchema.load());
        BotProgram program = new BotProgram(id, "name", "");

        assertFalse(ProgramStorage.isValidId(id));
        assertThrows(IllegalArgumentException.class, () -> storage.save(program));
        assertNull(storage.getById(id));
        assertFalse(storage.delete(id));
        assertEquals(0, storage.getCount());
        assertEquals(List.of(dir), list(root), "nothing may be written outside the program directory");
        assertEquals(List.of(), list(dir));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "walk_1", "A-b", "p7f3a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void idsInsideThePatternAreStored(String id, @TempDir Path dir)
    {
        ProgramStorage storage = new ProgramStorage(dir, ActionSchema.load());
        storage.save(new BotProgram(id, "name", ""));

        assertTrue(Files.isRegularFile(dir.resolve(id + ".json")));
        assertNotNull(storage.getById(id));
        assertTrue(storage.delete(id));
        assertFalse(Files.exists(dir.resolve(id + ".json")));
    }

    @Test
    void presetsCannotBeOverwritten(@TempDir Path dir)
    {
        ProgramStorage storage = new ProgramStorage(dir, ActionSchema.load());
        String presetId = storage.getPresets().getFirst().getId();

        assertThrows(IllegalArgumentException.class, () -> storage.save(new BotProgram(presetId, "mine", "")));
        assertTrue(storage.getById(presetId).isPreset());
    }

    @Test
    void filesCarryingAnInvalidIdAreNotLoaded(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("bad.json"), "{\"id\": \"../../escape\", \"name\": \"bad\", \"actions\": []}");
        Files.writeString(dir.resolve("good.json"), "{\"id\": \"good\", \"name\": \"good\", \"actions\": []}");
        ProgramStorage storage = new ProgramStorage(dir, ActionSchema.load());
        storage.loadAll();

        assertEquals(1, storage.getCount());
        assertNotNull(storage.getById("good"));
    }

    private static List<Path> list(Path dir) throws IOException
    {
        try (Stream<Path> paths = Files.list(dir))
        {
            return paths.toList();
        }
    }
}
