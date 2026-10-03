package carpet.pvp.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.BotPvpConfig;
import carpet.pvp.gui.BotOptionLayout.Entry;
import carpet.pvp.gui.BotOptionLayout.Kind;
import carpet.pvp.gui.BotOptionLayout.Option;
import carpet.pvp.gui.BotOptionLayout.Role;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BotOptionLayoutTest
{
    /** The settings the header of the bot page holds itself. */
    private static final Set<String> HEADER = Set.of("combatstyle", "difficulty", "faction");

    @Test
    void everySettingOfTheConfigIsListedOnceAndTheHeaderIsNot()
    {
        List<String> keys = new ArrayList<>();
        for (Option option : BotOptionLayout.options())
        {
            assertTrue(keys.add(option.key()), option.key() + " is listed twice");
            assertTrue(BotPvpConfig.keys().length > 0);
        }
        for (String key : BotPvpConfig.KEYS)
        {
            if (HEADER.contains(key)) assertTrue(!keys.contains(key), key + " belongs in the header");
            else assertTrue(keys.contains(key), key + " is not listed");
        }
    }

    @Test
    void theKindOfASettingComesFromTheFieldItBelongsTo()
    {
        assertEquals(Kind.TOGGLE, BotOptionLayout.kindOf("combat"));
        assertEquals(Kind.TOGGLE, BotOptionLayout.kindOf("AutoTotem"), "the name is read in lower case");
        assertEquals(Kind.NUMBER, BotOptionLayout.kindOf("skill"));
        assertEquals(Kind.NUMBER, BotOptionLayout.kindOf("attackcooldown"));
        assertEquals(Kind.STYLE, BotOptionLayout.kindOf("combatstyle"));
        assertEquals(Kind.DIFFICULTY, BotOptionLayout.kindOf("difficulty"));
        assertEquals(Kind.TEXT, BotOptionLayout.kindOf("faction"));
        // A setting the config does not know is text, so it still shows up rather than being dropped.
        assertEquals(Kind.TEXT, BotOptionLayout.kindOf("nosuch.setting"));
    }

    @Test
    void aNumberTakesThreeSlotsAndEverythingElseTakesOne()
    {
        for (Option option : BotOptionLayout.options())
        {
            assertEquals(option.kind() == Kind.NUMBER ? 3 : 1, option.width(), option.key());
        }
    }

    @Test
    void theStepsAreTheOnesAHumanWouldWant()
    {
        assertEquals(1.0D, BotOptionLayout.stepOf("attackcooldown"));
        assertEquals(0.05D, BotOptionLayout.stepOf("skill"));
        assertEquals(0.5D, BotOptionLayout.stepOf("clickspersecond"));
        assertEquals(1.0D, BotOptionLayout.stepOf("nosuch.setting"));
    }

    @Test
    void thePagesHoldEverySettingOnceWithoutTwoOfThemOnOneSlot()
    {
        assertTrue(BotOptionLayout.pageCount() > 1, "the settings of a bot do not fit on one page");
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < BotOptionLayout.pageCount(); page++)
        {
            // The slots are the same on every page, so the collision check is per page.
            Set<Integer> slots = new HashSet<>();
            for (Entry entry : BotOptionLayout.page(page))
            {
                assertTrue(entry.slot() >= BotOptionLayout.FIRST_SLOT && entry.slot() <= BotOptionLayout.LAST_SLOT,
                        "slot " + entry.slot() + " is outside the option area");
                assertTrue(slots.add(entry.slot()), "slot " + entry.slot() + " holds two settings");
                if (entry.role() == Role.VALUE) seen.add(entry.option().key());
                if (!entry.option().numeric())
                {
                    assertEquals(Role.VALUE, entry.role(), entry.option().key() + " is not a number but has three roles");
                }
            }
        }
        for (Option option : BotOptionLayout.options())
        {
            assertTrue(seen.contains(option.key()), option.key() + " is on no page at all");
        }
    }

    @Test
    void aNumberIsAMinusTheValueAndAPlus()
    {
        Entry[] found = null;
        for (Entry entry : BotOptionLayout.page(0))
        {
            if (entry.option().key().equals("skill")) found = find(found, entry);
        }
        assertNotNull(found, "skill is on the first page of the settings");
        assertEquals(3, found.length);
        assertEquals(Role.MINUS, found[0].role());
        assertEquals(Role.VALUE, found[1].role());
        assertEquals(Role.PLUS, found[2].role());
        assertEquals(found[0].slot() + 1, found[1].slot(), "the value sits between the two buttons");
        assertEquals(found[1].slot() + 1, found[2].slot());
    }

    @Test
    void aPageThatDoesNotExistIsEmptyRatherThanBroken()
    {
        assertEquals(List.of(), BotOptionLayout.page(-1));
        assertEquals(List.of(), BotOptionLayout.page(BotOptionLayout.pageCount()));
    }

    @Test
    void everySettingHasANameAndSomethingToSayAboutIt()
    {
        for (Option option : BotOptionLayout.options())
        {
            assertTrue(!BotOptionLayout.title(option.key()).isBlank(), option.key() + " has no name");
            assertTrue(!BotOptionLayout.text(option.key()).isBlank(), option.key() + " has no lore line");
        }
        assertEquals("Auto shield", BotOptionLayout.title("autoshield"));
        assertTrue(BotOptionLayout.title("autoshield").startsWith("Auto"), "words are split at the vowel boundary");
    }

    @Test
    void numbersAreWrittenWithoutNoise()
    {
        assertEquals("3", BotOptionLayout.format(3.0D));
        assertEquals("0", BotOptionLayout.format(-0.0D));
        assertEquals("0.05", BotOptionLayout.format(0.05D));
        assertEquals("16.50", BotOptionLayout.format(16.5D));
        assertEquals("0.33", BotOptionLayout.format(1.0D / 3.0D));
    }

    private static Entry[] find(Entry[] found, Entry entry)
    {
        if (found == null) return new Entry[] {entry};
        List<Entry> all = new ArrayList<>(List.of(found));
        all.add(entry);
        return all.toArray(new Entry[0]);
    }
}