package carpet.logic.bot;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How a program's variables are written into a Scarpet snippet. That Scarpet reads them back the same is the
 * self-test's to show, on a running server.
 */
class ScriptBridgeTest
{
    @Test
    void theSnippetIsPutBetweenTheLinesThatPassTheVariablesInAndOut()
    {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("kills", 5.0);
        variables.put("who", "Steve");

        assertEquals("""
                kills = 5.0;
                who = 'Steve';
                __carpetlogic_result = (
                kills = kills + 1; kills * 2
                );
                l(__carpetlogic_result, kills, who)""", ScriptBridge.wrap("  kills = kills + 1; kills * 2;  ", variables));
    }

    @Test
    void aSnippetWithoutVariablesStillHandsBackAList()
    {
        assertEquals("__carpetlogic_result = (\n1 + 1\n);\nl(__carpetlogic_result)", ScriptBridge.wrap("1 + 1", Map.of()));
        assertEquals("__carpetlogic_result = (\nnull\n);\nl(__carpetlogic_result)", ScriptBridge.wrap(" ;; ", Map.of()), "nothing to run is nothing");
    }

    @Test
    void valuesAreWrittenAsScarpetReadsThem()
    {
        assertEquals("5.0", ScriptBridge.literal(5.0));
        assertEquals("-0.25", ScriptBridge.literal(-0.25));
        assertEquals("10000000000", ScriptBridge.literal(1.0e10), "not in the notation with an exponent");
        assertEquals("1.7976931348623157E308", ScriptBridge.literal(Double.POSITIVE_INFINITY), "the largest number there is stands for infinity");
        assertEquals("-1.7976931348623157E308", ScriptBridge.literal(Double.NEGATIVE_INFINITY));
        assertEquals("true", ScriptBridge.literal(true));
        assertEquals("'Steve'", ScriptBridge.literal("Steve"));
        assertEquals("l(1.0, 'a', l(false))", ScriptBridge.literal(List.of(1.0, "a", List.of(false))));
        assertEquals("l()", ScriptBridge.literal(List.of()));
    }

    @Test
    void textCannotBreakOutOfItsQuotes()
    {
        // What ends the text early would run as Scarpet with the owner's permissions.
        assertEquals("'it\\'s'", ScriptBridge.literal("it's"));
        assertEquals("'a\\\\'", ScriptBridge.literal("a\\"), "a backslash before the closing quote is a backslash, not an escape of the quote");
        assertEquals("'\\\\\\'; run(\\'op me\\'); \\''", ScriptBridge.literal("\\'; run('op me'); '"));
        assertEquals("'two\\nlines'", ScriptBridge.literal("two\nlines"));
    }

    @Test
    void aSnippetRunsOnlyForAnOnlinePlayerWhoMayUseScriptRun()
    {
        assertNull(ScriptBridge.refusal(true, "Steve", true, true));
        assertEquals("SCARPET only runs in programs a player started from the web editor", ScriptBridge.refusal(false, null, true, true));
        assertEquals("SCARPET needs the player who started this program to be online", ScriptBridge.refusal(true, null, true, true));
        String notAllowed = "SCARPET needs Steve to be allowed /script run, which the rules commandScript and commandScriptACE decide";
        assertEquals(notAllowed, ScriptBridge.refusal(true, "Steve", true, false), "commandScriptACE alone refuses");
        assertEquals(notAllowed, ScriptBridge.refusal(true, "Steve", false, true), "commandScript alone refuses");
        assertEquals(notAllowed, ScriptBridge.refusal(true, "Steve", false, false));
    }
}
