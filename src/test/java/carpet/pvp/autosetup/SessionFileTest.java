package carpet.pvp.autosetup;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file a session leaves behind: what a player owned has to come back out of it exactly as it
 * went in, and a file that is not one of ours has to be refused rather than read as an empty one.
 */
class SessionFileTest
{
    /** Forty-one slots: the thirty-six of the inventory, the four of the armour and the offhand. */
    private static List<String> slots()
    {
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < 41; i++)
        {
            slots.add(i == 3 ? "{\"id\":\"minecraft:diamond\",\"count\":12}" : "");
        }
        return slots;
    }

    private static SessionFile file()
    {
        ArenaBlocks blocks = new ArenaBlocks();
        blocks.add(10, -60, 20, 1);
        blocks.add(11, -60, 20, 2);
        return new SessionFile(SessionFile.VERSION, "Steve", "sword", "beginner",
                new SavedState(new SavedState.Spot("minecraft:overworld", 1.5D, -60.0D, 2.5D, 90.0F, -12.5F),
                        "creative", 3, slots(), List.of("fakePlayerNavigation=false"), blocks));
    }

    @Test
    void aSessionSurvivesTheTripThroughTheFile()
    {
        SessionFile written = file();
        SessionFile read = SessionFile.parse(written.toJson());

        assertEquals(SessionFile.VERSION, read.version());
        assertEquals("Steve", read.player());
        assertEquals("sword", read.mode());
        assertEquals("beginner", read.difficulty());

        SavedState saved = read.saved();
        assertEquals("minecraft:overworld", saved.spot().dimension());
        assertEquals(1.5D, saved.spot().x());
        assertEquals(-60.0D, saved.spot().y());
        assertEquals(2.5D, saved.spot().z());
        assertEquals(90.0F, saved.spot().yaw());
        assertEquals(-12.5F, saved.spot().pitch());
        assertEquals("creative", saved.gamemode());
        assertEquals(3, saved.selected());
        assertEquals(slots(), saved.slots());
        assertEquals(List.of("fakePlayerNavigation=false"), saved.rules());
        assertEquals(List.of(new ArenaBlocks.Entry(10, -60, 20, 1), new ArenaBlocks.Entry(11, -60, 20, 2)),
                saved.blocks().entries());
    }

    @Test
    void theTwoListsAreCopiesSoNothingCanChangeTheFileUnderneathIt()
    {
        List<String> slots = new ArrayList<>(slots());
        List<String> rules = new ArrayList<>(List.of("fakePlayerNavigation=false"));
        SavedState saved = new SavedState(new SavedState.Spot("minecraft:overworld", 0.0D, 0.0D, 0.0D, 0.0F, 0.0F),
                "survival", 0, slots, rules, new ArenaBlocks());
        slots.clear();
        rules.clear();
        assertEquals(41, saved.slots().size());
        assertEquals(1, saved.rules().size());
    }

    @Test
    void aFileOfAnotherVersionIsNotRead()
    {
        String json = file().toJson().replace("\"version\": 1", "\"version\": 2");
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class, () -> SessionFile.parse(json));
        assertTrue(problem.getMessage().contains("version 2"), problem.getMessage());
    }

    @Test
    void aFileThatIsNotOneOfOursIsRefusedWithAReason()
    {
        assertThrows(IllegalArgumentException.class, () -> SessionFile.parse("not json at all"));
        assertThrows(IllegalArgumentException.class, () -> SessionFile.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> SessionFile.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> SessionFile.parse("{\"version\": 1}"));
        assertThrows(IllegalArgumentException.class,
                () -> SessionFile.parse("{\"version\": 1, \"player\": \"  \"}"));
        assertThrows(IllegalArgumentException.class,
                () -> SessionFile.parse("{\"version\": 1, \"player\": \"Steve\", \"saved\": 3}"));
        assertThrows(IllegalArgumentException.class, () -> SessionFile.parse("""
                {"version": 1, "player": "Steve", "saved": {"spot": {"dimension": "minecraft:overworld"}}}"""));
        assertThrows(IllegalArgumentException.class, () -> SessionFile.parse("""
                {"version": 1, "player": "Steve", "saved": {"spot": {"dimension": 7}}}"""));
        assertThrows(IllegalArgumentException.class, () -> SessionFile.parse("""
                {"version": 1, "player": "Steve", "saved": {"spot": {"dimension": "minecraft:overworld",
                 "x": 0, "y": 0, "z": 0, "yaw": 0, "pitch": 0}, "gamemode": "survival", "selected": 0,
                 "slots": "everything"}}"""));
    }

    @Test
    void theModeAndTheDifficultyAreOptionalSoAnOlderFileStillNamesItsPlayer()
    {
        SessionFile read = SessionFile.parse("""
                {"version": 1, "player": "Steve", "saved": {"spot": {"dimension": "minecraft:overworld",
                 "x": 0, "y": 0, "z": 0, "yaw": 0, "pitch": 0}, "gamemode": "survival", "selected": 0}}""");
        assertEquals("Steve", read.player());
        assertEquals("", read.mode());
        assertEquals("", read.difficulty());
        assertTrue(read.saved().slots().isEmpty());
        assertTrue(read.saved().rules().isEmpty());
        assertTrue(read.saved().blocks().isEmpty());
    }

    @Test
    void aSavedStateCanBeCopiedWithTheBlocksAndTheRulesThatCameAfterIt()
    {
        SavedState saved = file().saved();
        ArenaBlocks blocks = new ArenaBlocks();
        blocks.add(1, 2, 3, 4);
        SavedState withBlocks = saved.withBlocks(blocks);
        assertEquals(saved.slots(), withBlocks.slots());
        assertEquals(saved.spot(), withBlocks.spot());
        assertEquals(List.of(new ArenaBlocks.Entry(1, 2, 3, 4)), withBlocks.blocks().entries());
        assertEquals(List.of("botCombat=true"), withBlocks.withRules(List.of("botCombat=true")).rules());
        assertEquals(List.of("fakePlayerNavigation=false"), withBlocks.rules());
        assertEquals(2, saved.blocks().size());
    }

    /** The bookkeeping of an arena: what it wrote over, in the order it wrote it. */
    @Test
    void anArenaRemembersEveryBlockItWroteOver()
    {
        ArenaBlocks blocks = new ArenaBlocks();
        assertTrue(blocks.isEmpty());
        assertNull(blocks.bounds());
        blocks.add(0, 0, 0, 5);
        blocks.add(-3, 4, 7, 6);
        blocks.add(3, 1, -2, 7);

        assertEquals(3, blocks.size());
        assertFalse(blocks.isEmpty());
        ArenaBlocks.Box bounds = blocks.bounds();
        assertNotNull(bounds);
        assertEquals(-3, bounds.minX());
        assertEquals(3, bounds.maxX());
        assertEquals(0, bounds.minY());
        assertEquals(4, bounds.maxY());
        assertEquals(-2, bounds.minZ());
        assertEquals(7, bounds.maxZ());
        assertTrue(bounds.holds(new ArenaBlocks.Box(-3, 0, -2, 3, 4, 7)));
        assertFalse(bounds.holds(new ArenaBlocks.Box(-4, 0, -2, 3, 4, 7)));
        assertFalse(bounds.holds(new ArenaBlocks.Box(-3, 0, -2, 3, 5, 7)));

        assertEquals(blocks.entries(), ArenaBlocks.fromJson(blocks.toJson()).entries());
        blocks.clear();
        assertTrue(blocks.isEmpty());
        assertNull(blocks.bounds());
    }

    @Test
    void aBlockOfTheBookkeepingNeedsAllFourNumbers()
    {
        assertThrows(IllegalArgumentException.class, () -> ArenaBlocks.fromJson(JsonParser.parseString("{}")));
        assertThrows(IllegalArgumentException.class, () -> ArenaBlocks.fromJson(JsonParser.parseString("[[1, 2, 3, 4]]")));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaBlocks.fromJson(JsonParser.parseString("[{\"x\": 1, \"y\": 2, \"z\": 3}]")));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaBlocks.fromJson(JsonParser.parseString("[{\"x\": \"a\", \"y\": 2, \"z\": 3, \"state\": 4}]")));
        assertTrue(ArenaBlocks.fromJson(null).isEmpty());
        assertTrue(ArenaBlocks.fromJson(JsonParser.parseString("[]")).isEmpty());
    }
}
