package carpet.logic.bot;

import carpet.pvp.BotPvpConfig.CombatStyle;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The part of the panel's snapshot that is plain Java. What it says about a player needs a running server, and is
 * pinned by the logic_bot_snapshot self-test scenario instead.
 */
class BotSnapshotTest
{
    @Test
    void theCombatSettingsAreTheOnesThePanelShows()
    {
        JsonObject combat = BotSnapshot.combat(true, CombatStyle.CRYSTAL);

        assertEquals(Set.of("combat", "style"), combat.keySet());
        // A panel reads this as a flag, not as the text "true".
        assertTrue(combat.get("combat").getAsJsonPrimitive().isBoolean());
        assertTrue(combat.get("combat").getAsBoolean());
        assertEquals("CRYSTAL", combat.get("style").getAsString());
    }

    @Test
    void everyCombatStyleHasANameThePanelCanShow()
    {
        for (CombatStyle style : CombatStyle.values())
        {
            assertEquals(style.name(), BotSnapshot.combat(false, style).get("style").getAsString());
        }
    }
}