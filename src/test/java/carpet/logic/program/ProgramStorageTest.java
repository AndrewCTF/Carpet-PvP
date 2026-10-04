package carpet.logic.program;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramStorageTest
{
    private static final ActionSchema SCHEMA = ActionSchema.load();

    private static BotProgram program(String id, String name)
    {
        BotProgram program = new BotProgram(id, name, "");
        program.setActions(List.of(new BotAction("JUMP", Map.of())));
        JsonObject graph = new JsonObject();
        graph.addProperty("made for", name);
        program.setGraphData(graph);
        return program;
    }

    /** A copy of what the storage holds, changed the way an editor would before it saves again. */
    private static BotProgram edited(BotProgram stored, String name)
    {
        BotProgram program = program(stored.getId(), name);
        program.setGraphData(JsonParser.parseString("{\"nodes\": [\"edited\"]}"));
        return program;
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "../evil", "..", ".", "a/b", "a\\b", "/etc/passwd", "a.json", "a b", "a\u0000b", "café",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void idsOutsideThePatternAreRejected(String id, @TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectory(root.resolve("programs"));
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        BotProgram program = new BotProgram(id, "name", "");

        assertFalse(ProgramStorage.isValidId(id));
        assertThrows(IllegalArgumentException.class, () -> storage.save(program));
        assertNull(storage.getById(id));
        assertFalse(storage.delete(id));
        assertEquals(0, storage.getCount());
        assertEquals(List.of(dir), list(root), "nothing may be written outside the program directory");
        assertEquals(List.of(), list(dir));
    }

    // The file used to be named after the id. It is named after the program now; the id is what is in it.
    @ParameterizedTest
    @ValueSource(strings = {"a", "walk_1", "A-b", "p7f3a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void idsInsideThePatternAreStored(String id, @TempDir Path dir)
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        storage.save(new BotProgram(id, "name", ""));

        assertTrue(Files.isRegularFile(dir.resolve("name.json")));
        assertNotNull(storage.getById(id));
        assertTrue(storage.delete(id));
        assertFalse(Files.exists(dir.resolve("name.json")));
    }

    @Test
    void presetsCannotBeOverwritten(@TempDir Path dir)
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        String presetId = storage.getPresets().getFirst().getId();

        assertThrows(IllegalArgumentException.class, () -> storage.save(new BotProgram(presetId, "mine", "")));
        assertTrue(storage.getById(presetId).isPreset());
    }

    @Test
    void filesCarryingAnInvalidIdAreNotLoaded(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("bad.json"), "{\"id\": \"../../escape\", \"name\": \"bad\", \"actions\": []}");
        Files.writeString(dir.resolve("good.json"), "{\"id\": \"good\", \"name\": \"good\", \"actions\": []}");
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        storage.loadAll();

        assertEquals(1, storage.getCount());
        assertNotNull(storage.getById("good"));
    }

    // ── A file a person can find ──

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "W-Tap | W-Tap",
            "Patrol and Fight | Patrol-and-Fight",
            "walk_1 | walk_1",
            "../../evil | evil",
            "..\\..\\evil | evil",
            "/etc/passwd | etc-passwd",
            "a/b | a-b",
            ".hidden | hidden",
            "name.json | name-json",
            "C:\\Windows\\win.ini | C-Windows-win-ini",
            "  spaced   out  | spaced-out",
            "caf\u00e9 au lait | caf-au-lait",
            "\u65e5\u672c\u8a9e | program",
            "... | program",
            "CON | CON-program",
            "nul | nul-program",
            "a\u0000b | a-b"})
    void theFileIsNamedAfterTheProgramAndNothingElse(String name, String expected, @TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectory(root.resolve("programs"));
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        ProgramStorage.Saved saved = storage.save(program("p1", name));

        assertEquals(List.of(dir.resolve(expected + ".json")), list(dir));
        assertEquals(dir.resolve(expected + ".json"), saved.program().getFile());
        assertEquals(List.of(dir), list(root), "nothing may be written outside the program directory");
        assertEquals(name.strip(), storage.getById("p1").getName(), "the program keeps the name it was given");
    }

    @Test
    void aLongNameIsCutToAFileNameThatFits(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        storage.save(program("p1", "x".repeat(300)));

        assertEquals(List.of(dir.resolve("x".repeat(64) + ".json")), list(dir));
    }

    @Test
    void twoProgramsNeverShareAFileName(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        storage.save(program("p1", "Walk"));
        // The same name, the same name in another case, and another name that makes the same file name.
        assertEquals("Walk 2", storage.save(program("p2", "Walk")).program().getName());
        assertEquals("walk 3", storage.save(program("p3", "walk")).program().getName());
        storage.save(program("p4", "W Tap"));
        assertEquals("W-Tap 2", storage.save(program("p5", "W-Tap")).program().getName());

        assertEquals(List.of("W-Tap-2.json", "W-Tap.json", "Walk-2.json", "Walk.json", "walk-3.json"), names(dir));
        assertEquals("Walk", storage.save(edited(storage.getById("p1"), "Walk")).program().getName(), "a program keeps its own name");
    }

    @Test
    void aProgramDoesNotTakeAPresetsId(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        String presetId = storage.getPresets().getFirst().getId();

        ProgramStorage.Saved saved = storage.save(program("p1", presetId));
        assertEquals(presetId + " 2", saved.program().getName());
        assertEquals(List.of(presetId + "-2.json"), names(dir));

        // Nor does a file somebody put there under a preset's id.
        Files.writeString(dir.resolve("copy.json"), "{\"id\": \"" + presetId + "\", \"name\": \"copy\", \"actions\": []}");
        storage.loadAll();
        assertTrue(storage.getById(presetId).isPreset());
        assertEquals(1, storage.getCount());
    }

    @Test
    void aNewProgramWithoutANameGetsOne(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);

        assertEquals("Untitled", storage.save(program("p1", " ")).program().getName());
        assertEquals("Untitled 2", storage.save(program("p2", null)).program().getName());
        assertEquals("Untitled 3", storage.save(program("p3", "Untitled")).program().getName());
        assertEquals(List.of("Untitled-2.json", "Untitled-3.json", "Untitled.json"), names(dir));
    }

    @Test
    void renamingAProgramRenamesItsFileAndKeepsItsId(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        long created = storage.save(program("p1", "First name")).program().getCreatedAt();

        ProgramStorage.Saved renamed = storage.save(edited(storage.getById("p1"), "Second name"));
        assertTrue(renamed.written());
        assertEquals(List.of("Second-name.json"), names(dir));
        assertEquals("Second name", storage.getById("p1").getName());
        assertEquals(created, storage.getById("p1").getCreatedAt());

        storage.loadAll();
        assertEquals("Second name", storage.getById("p1").getName(), "and it is the same program after the folder is read again");
    }

    @Test
    void aProgramSavedUnderItsOldIdKeepsLoadingAndMovesToItsNameWhenSaved(@TempDir Path dir) throws IOException
    {
        // What the editor wrote before: the file is named after the id the server made up.
        Files.writeString(dir.resolve("p7f3a.json"),
                "{\"id\": \"p7f3a\", \"name\": \"My rounds\", \"actions\": [{\"type\": \"JUMP\"}], \"createdAt\": 5, \"updatedAt\": 7}");
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        storage.loadAll();

        BotProgram old = storage.getById("p7f3a");
        assertNotNull(old);
        assertEquals("My rounds", old.getName());
        assertNull(old.getError());
        assertEquals(1, old.getActionCount());
        assertEquals(List.of("p7f3a.json"), names(dir), "reading the folder rewrites nothing");

        storage.save(edited(old, "My rounds"));
        assertEquals(List.of("My-rounds.json"), names(dir));
        assertEquals(5, storage.getById("p7f3a").getCreatedAt());
    }

    @Test
    void nothingButTheProgramIsLeftInTheFolder(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        storage.save(program("p1", "Walk"));
        storage.save(edited(storage.getById("p1"), "Walk"));
        storage.save(edited(storage.getById("p1"), "Run"));

        assertEquals(List.of("Run.json"), names(dir));
    }

    // ── Drafts ──

    @Test
    void aProgramThatDoesNotCompileIsSavedAsADraft(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        BotProgram draft = program("p1", "Half done");
        draft.setError("An If / Else node has no condition connected");
        ProgramStorage.Saved saved = storage.save(draft);

        assertTrue(saved.written());
        JsonObject file = JsonParser.parseString(Files.readString(dir.resolve("Half-done.json"))).getAsJsonObject();
        assertEquals("An If / Else node has no condition connected", file.get("error").getAsString());
        assertEquals(draft.getGraphData(), file.get("graphData"), "the graph is what a draft is kept for");
        assertTrue(file.getAsJsonArray("actions").isEmpty(), "a draft has no actions to run");

        storage.loadAll();
        BotProgram listed = storage.getAllPrograms().getFirst();
        assertEquals("An If / Else node has no condition connected", listed.getError());
        assertEquals(draft.getGraphData(), listed.getGraphData());
    }

    @Test
    void actionsThatDoNotFitTheSchemaMakeADraftRatherThanARefusal(@TempDir Path dir)
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        BotProgram program = program("p1", "Odd");
        program.setActions(List.of(new BotAction("NO_SUCH_ACTION", Map.of())));

        BotProgram saved = storage.save(program).program();
        assertEquals("Unknown action type 'NO_SUCH_ACTION'", saved.getError());
        assertEquals(0, saved.getActionCount());
        assertNotNull(saved.getGraphData());
    }

    @Test
    void aDraftBecomesAProgramOnceItCompiles(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        BotProgram draft = program("p1", "Half done");
        draft.setError("not yet");
        storage.save(draft);

        storage.save(edited(storage.getById("p1"), "Half done"));
        assertNull(storage.getById("p1").getError());
        assertEquals(1, storage.getById("p1").getActionCount());
        assertFalse(Files.readString(dir.resolve("Half-done.json")).contains("\"error\""));
    }

    @Test
    void nothingInTheFolderStopsItBeingRead(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("draft.json"), "{\"id\": \"d1\", \"name\": \"draft\", \"error\": \"not yet\", \"graphData\": {\"nodes\": []}}");
        Files.writeString(dir.resolve("stale.json"), "{\"id\": \"s1\", \"name\": \"stale\", \"actions\": [{\"type\": \"GONE_IN_THIS_VERSION\"}]}");
        Files.writeString(dir.resolve("garbage.json"), "{ this is not json");
        Files.writeString(dir.resolve("empty.json"), "");
        Files.writeString(dir.resolve("array.json"), "[1, 2, 3]");
        Files.writeString(dir.resolve("notes.txt"), "not a program");
        Files.writeString(dir.resolve("good.json"), "{\"id\": \"g1\", \"name\": \"good\", \"actions\": [{\"type\": \"JUMP\"}]}");
        String stale = Files.readString(dir.resolve("stale.json"));
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        storage.loadAll();

        assertEquals(3, storage.getCount());
        assertEquals("not yet", storage.getById("d1").getError());
        assertNull(storage.getById("g1").getError());
        // A program whose actions this server does not know is shown as a draft, so that it can be put right.
        assertEquals("Its actions do not fit this server: Unknown action type 'GONE_IN_THIS_VERSION'", storage.getById("s1").getError());
        assertEquals(0, storage.getById("s1").getActionCount());
        assertEquals(stale, Files.readString(dir.resolve("stale.json")), "and its file is left as it was");
    }

    // ── Two editors on one program ──

    @Test
    void aSaveFromAnOlderCopyIsRefused(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        long first = storage.save(program("p1", "Walk")).program().getUpdatedAt();
        // One tab saves; the other still holds the copy from before.
        long second = storage.save(edited(storage.getById("p1"), "Walk"), first, false).program().getUpdatedAt();
        assertTrue(second > first);
        String onDisk = Files.readString(dir.resolve("Walk.json"));

        ProgramStorage.Saved stale = storage.save(program("p1", "Walk mine"), first, false);
        assertTrue(stale.conflict());
        assertFalse(stale.written());
        assertEquals(second, stale.program().getUpdatedAt(), "the answer says which copy the storage has");
        assertEquals(onDisk, Files.readString(dir.resolve("Walk.json")));
        assertEquals(List.of("Walk.json"), names(dir));

        ProgramStorage.Saved forced = storage.save(program("p1", "Walk mine"), first, true);
        assertFalse(forced.conflict());
        assertTrue(forced.written());
        assertEquals(List.of("Walk-mine.json"), names(dir));
        assertTrue(storage.save(edited(storage.getById("p1"), "Walk mine")).written(), "a save that names no copy is not checked");
    }

    @Test
    void savingWhatIsAlreadyThereWritesNothing(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        long updated = storage.save(program("p1", "Walk")).program().getUpdatedAt();
        Path file = dir.resolve("Walk.json");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000L));

        ProgramStorage.Saved again = storage.save(program("p1", "Walk"), updated, false);
        assertFalse(again.written());
        assertFalse(again.conflict());
        assertEquals(updated, again.program().getUpdatedAt());
        assertEquals(1_000_000L, Files.getLastModifiedTime(file).toMillis(), "the file was not touched");

        assertTrue(storage.save(edited(storage.getById("p1"), "Walk"), updated, false).written());
        assertNotEquals(1_000_000L, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void aGraphWithEmptySocketsIsTheSameGraphAfterTheFolderIsReadAgain(@TempDir Path dir) throws IOException
    {
        // What the editor sends for a socket nothing is wired to is null, which the file does not keep.
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        BotProgram program = program("p1", "Walk");
        program.setGraphData(JsonParser.parseString("{\"nodes\": [{\"inputs\": [{\"name\": \"in\", \"link\": null}], \"title\": null}], \"list\": [null, 1]}"));
        long updated = storage.save(program).program().getUpdatedAt();
        storage.loadAll();

        BotProgram again = program("p1", "Walk");
        again.setGraphData(JsonParser.parseString("{\"nodes\": [{\"inputs\": [{\"name\": \"in\", \"link\": null}], \"title\": null}], \"list\": [null, 1]}"));
        assertFalse(storage.save(again, updated, false).written(), "a save of the same graph is not taken for a change");
        assertEquals(JsonParser.parseString("{\"nodes\": [{\"inputs\": [{\"name\": \"in\"}]}], \"list\": [null, 1]}"), storage.getById("p1").getGraphData());
    }

    // ── Subfolders ──

    @Test
    void aProgramCanBePutInASubfolderAndBack(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        storage.save(program("p1", "Walk"));
        storage.save(program("p2", "Duel"));

        BotProgram moved = storage.move("p1", "Drills");
        assertEquals("Drills", moved.getFolder());
        assertEquals(dir.resolve("Drills").resolve("Walk.json"), moved.getFile());
        assertEquals(List.of("Drills", "Duel.json"), names(dir));

        storage.loadAll();
        assertEquals("Drills", storage.getById("p1").getFolder(), "a program is in the folder its file is in");
        assertEquals("", storage.getById("p2").getFolder());

        // Saving it again leaves it where it is.
        storage.save(edited(storage.getById("p1"), "Walk slowly"));
        assertEquals(List.of("Walk-slowly.json"), names(dir.resolve("Drills")));

        storage.move("p1", "");
        assertEquals(List.of("Duel.json", "Walk-slowly.json"), names(dir), "and an emptied subfolder goes");
        assertNull(storage.move("nobody", "Drills"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "../.. | ''", "../up | up", "a/b/c | a-b-c", "..\\up | up", "/etc | etc", ". | ''", "my drills | my-drills",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void aSubfolderIsOneSafeNameInsideTheProgramsFolder(String asked, String expected, @TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectory(root.resolve("programs"));
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        storage.save(program("p1", "Walk"));

        BotProgram moved = storage.move("p1", asked);
        assertEquals(expected, moved.getFolder());
        assertEquals(expected.isEmpty() ? dir.resolve("Walk.json") : dir.resolve(expected).resolve("Walk.json"), moved.getFile());
        assertTrue(Files.isRegularFile(moved.getFile()));
        assertEquals(List.of(dir), list(root), "nothing may be written outside the program directory");
    }

    @Test
    void aFilePutInTheFolderByHandIsAProgramAfterTheFolderIsReadAgain(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        storage.save(program("p1", "Walk"));

        Files.createDirectories(dir.resolve("Shared"));
        Files.writeString(dir.resolve("Shared").resolve("From a friend.json"), "{\"actions\": [{\"type\": \"JUMP\"}]}");
        Files.copy(dir.resolve("Walk.json"), dir.resolve("Shared").resolve("Walk copy.json"));
        Files.createDirectories(dir.resolve("Shared").resolve("deeper"));
        Files.writeString(dir.resolve("Shared").resolve("deeper").resolve("too deep.json"), "{\"actions\": []}");
        storage.loadAll();

        assertEquals(3, storage.getCount(), "one level of subfolders is read");
        BotProgram dropped = storage.getById("file-Shared-From-a-friend");
        assertNotNull(dropped, "a file without an id gets one from where it is");
        assertEquals("From a friend", dropped.getName());
        assertEquals("Shared", dropped.getFolder());
        // The copy carries the id of the program it was copied from, which that program keeps.
        assertEquals("", storage.getById("p1").getFolder());
        BotProgram copy = storage.getById("file-Shared-Walk-copy");
        assertNotNull(copy);
        assertEquals("Walk", copy.getName());

        storage.loadAll();
        assertNotNull(storage.getById("file-Shared-From-a-friend"), "and the same one every time");
        assertNotNull(storage.getById("file-Shared-Walk-copy"));
    }

    @Test
    void aNameFindsTheProgramInTheFolderItselfBeforeOneInASubfolder(@TempDir Path dir) throws IOException
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        Files.createDirectories(dir.resolve("Drills"));
        Files.writeString(dir.resolve("Drills").resolve("Walk.json"), "{\"id\": \"inner\", \"name\": \"Walk\", \"actions\": []}");
        Files.writeString(dir.resolve("Walk.json"), "{\"id\": \"outer\", \"name\": \"Walk\", \"actions\": []}");
        Files.writeString(dir.resolve("Drills").resolve("Crit.json"), "{\"id\": \"crit\", \"name\": \"Crit\", \"actions\": []}");
        storage.loadAll();

        assertEquals("outer", storage.getByName("walk").getId());
        assertEquals("inner", storage.getByName("Drills/Walk").getId());
        assertEquals("crit", storage.getByName("Crit").getId());
        assertNull(storage.getByName("Elsewhere/Walk"));
        assertTrue(storage.getByName(storage.getPresets().getFirst().getName()).isPreset(), "a preset is found by its name as before");
    }

    @Test
    void theFolderIsShownAsItsAdminWouldOpenIt(@TempDir Path dir)
    {
        ProgramStorage storage = new ProgramStorage(dir, SCHEMA);
        BotProgram saved = storage.save(program("p1", "Walk")).program();

        assertEquals(dir.toAbsolutePath().normalize().toString().replace('\\', '/'), storage.location());
        assertEquals(storage.location() + "/Walk.json", storage.location(saved));
        Path inside = Path.of("build", "programs-here");
        assertEquals("build/programs-here", new ProgramStorage(inside, SCHEMA).location(), "relative to where the server runs when it is inside");
        assertNull(storage.location(storage.getPresets().getFirst()));
    }

    private static List<Path> list(Path dir) throws IOException
    {
        try (Stream<Path> paths = Files.list(dir))
        {
            return paths.sorted().toList();
        }
    }

    private static List<String> names(Path dir) throws IOException
    {
        return list(dir).stream().map(path -> path.getFileName().toString()).toList();
    }
}
