package carpet.pvp.gui;

import carpet.pvp.BotPvpConfig;
import carpet.pvp.style.StyleIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the buttons of the bot page are called. Every label the menu shows is built from a setting
 * name, so a label has to read as words rather than as the name spelled out letter by letter.
 */
class BotOptionLayoutTest
{
    @Test
    void everyStyleOptionIsCalledByItsStyleAndItsOwnWords()
    {
        Map<String, String> options = StyleIndex.options();
        assertTrue(options.size() > 20, "the styles declare " + options.size() + " options");
        for (String key : options.keySet())
        {
            int dot = key.indexOf('.');
            assertTrue(dot > 0, key + " is not named <style>.<option>");
            String style = BotPvpConfig.CombatStyle.valueOf(key.substring(0, dot).toUpperCase(Locale.ROOT)).name();
            String option = key.substring(dot + 1).replace('_', ' ');
            assertEquals(style + ": " + option, BotOptionLayout.title(key), key);
        }
    }

    @Test
    void noLabelIsBrokenUpIntoSingleLetters()
    {
        for (String key : BotPvpConfig.KEYS)
        {
            assertReadsAsWords(key);
        }
        for (String key : StyleIndex.options().keySet())
        {
            assertReadsAsWords(key);
        }
    }

    @Test
    void theCommonSettingsAreCalledByTheirHandWrittenNames()
    {
        assertEquals("Auto totem", BotOptionLayout.title("autototem"));
        assertEquals("Clicks per second", BotOptionLayout.title("clickspersecond"));
        assertEquals("Shield break", BotOptionLayout.title("shieldbreak"));
    }

    private static void assertReadsAsWords(String key)
    {
        String title = BotOptionLayout.title(key);
        assertTrue(!title.isBlank(), key + " has no label");
        for (String word : title.split(" "))
        {
            assertTrue(word.length() > 1, key + " reads as " + title + ", which has a word of one letter");
        }
    }
}
