package carpet.pvp;

import carpet.pvp.FactionStore.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactionStoreTest
{
    private static final UUID RED = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BLUE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach
    void empty()
    {
        FactionManager.reset();
    }

    @Test
    void theRegistryReadsBackAsItWasSaved()
    {
        FactionManager.reset();
        FactionManager.create("red");
        FactionManager.create("blue");
        FactionManager.join("red", RED);
        FactionManager.join("blue", BLUE);
        FactionManager.ally("red", "blue");

        FactionManager.restore(FactionStore.fromJson(FactionStore.toJson(FactionManager.snapshot())));

        assertEquals(java.util.Set.of("red", "blue"), FactionManager.allFactions());
        assertEquals("red", FactionManager.factionOf(RED));
        assertEquals("blue", FactionManager.factionOf(BLUE));
        assertTrue(FactionManager.areFriendly(RED, BLUE));
    }

    @Test
    void everyShapeOfTheSnapshotSurvivesTheRoundTrip()
    {
        Snapshot written = new Snapshot(List.of("red", "blue", "green"),
                Map.of(RED, "red", BLUE, "blue"),
                Map.of("red", List.of("blue"), "blue", List.of("red")));

        Snapshot read = FactionStore.fromJson(FactionStore.toJson(written));

        assertEquals(written, read);
    }

    @Test
    void alliesAreASetAndMembersAChoiceOfOneFaction()
    {
        Snapshot written = new Snapshot(List.of("a", "b"), Map.of(RED, "a"),
                Map.of("a", List.of("b", "b", "c")));

        Snapshot read = FactionStore.fromJson(FactionStore.toJson(written));

        assertEquals(List.of("b", "b", "c"), read.allies().get("a"));
        assertEquals("a", read.members().get(RED));
    }

    @Test
    void theFileIsWrittenIntoTheWorldFolderAndReadBack(@TempDir Path world) throws IOException
    {
        Snapshot written = new Snapshot(List.of("red"), Map.of(RED, "red"), Map.of());

        FactionStore.write(world.resolve(FactionStore.FILE), written);

        assertTrue(Files.isRegularFile(world.resolve(FactionStore.FILE)));
        assertEquals(written, FactionStore.read(world.resolve(FactionStore.FILE)));
    }

    @Test
    void aWorldThatNeverHadFactionsStartsWithNone(@TempDir Path world)
    {
        assertEquals(FactionStore.EMPTY, FactionStore.read(world.resolve(FactionStore.FILE)));
    }

    @Test
    void aFileThatCannotBeReadIsAnEmptyRegistryRatherThanAFailure(@TempDir Path world) throws IOException
    {
        Path file = world.resolve(FactionStore.FILE);
        Files.writeString(file, "this is not json");

        assertEquals(FactionStore.EMPTY, FactionStore.read(file));
    }

    @Test
    void aRegistryFileWithoutTheNewerKeysStillReads()
    {
        Snapshot read = FactionStore.fromJson("{\"factions\": [\"red\"]}");

        assertEquals(List.of("red"), read.factions());
        assertEquals(Map.of(), read.members());
        assertEquals(Map.of(), read.allies());
    }

    @Test
    void somethingThatIsNotARegistryFileIsRefused()
    {
        assertThrows(IllegalArgumentException.class, () -> FactionStore.fromJson("[\"red\"]"));
    }
}