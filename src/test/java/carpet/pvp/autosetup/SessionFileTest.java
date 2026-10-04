package carpet.pvp.autosetup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session file is all a crash leaves behind, so what goes into it and what comes out of it has
 * to be the same. Only what a player owned is in it: there is no session to hand back to, so nothing
 * about the mode or the difficulty is kept.
 */
class SessionFileTest
{
    private static SavedState savedState()
    {
        ArenaBlocks blocks = new ArenaBlocks();
        blocks.add(20, -60, 0, 5);
        blocks.add(21, -60, 0, 17);
        List<String> slots = new ArrayList<>();
        slots.add("{\"id\":\"minecraft:golden_apple\",\"count\":3}");
        for (int i = 1; i < 41; i++) slots.add("");
        return new SavedState(new SavedState.Spot("minecraft:overworld", 12.5D, -60.0D, 0.5D, 90.0F, -12.0F),
                "creative", 4, slots, List.of("fakePlayerNavigation=false"), blocks);
    }

    @Test
    void aFileComesBackAsItWentIn()
    {
        SessionFile written = new SessionFile(SessionFile.VERSION, "Ann", savedState());
        SessionFile read = SessionFile.parse(written.toJson());
        assertEquals(SessionFile.VERSION, read.version());
        assertEquals("Ann", read.player());
        assertEquals(written.saved().spot(), read.saved().spot());
        assertEquals(written.saved().gamemode(), read.saved().gamemode());
        assertEquals(written.saved().selected(), read.saved().selected());
        assertEquals(written.saved().slots(), read.saved().slots());
        assertEquals(written.saved().rules(), read.saved().rules());
        assertEquals(written.saved().blocks().entries(), read.saved().blocks().entries());
    }

    @Test
    void theFileKeepsNothingAboutTheSessionItself()
    {
        String json = new SessionFile(SessionFile.VERSION, "Ann", savedState()).toJson();
        assertFalse(json.contains("\"mode\""), "nothing reads a mode back, so the file does not keep one");
        assertFalse(json.contains("\"difficulty\""), "nothing reads a difficulty back either");
        assertTrue(json.contains("\"version\"") && json.contains("\"player\"") && json.contains("\"saved\""));
    }

    @Test
    void aFileThatIsNotOneOfOursIsRefusedWithAReason()
    {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SessionFile.parse("not json at all"))
                .getMessage().contains("JSON"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SessionFile.parse("[]"))
                .getMessage().contains("JSON object"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SessionFile.parse("{\"version\": 99, \"player\": \"Ann\"}")).getMessage().contains("version"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SessionFile.parse("{\"version\": 1}")).getMessage().contains("player"));
    }
}
