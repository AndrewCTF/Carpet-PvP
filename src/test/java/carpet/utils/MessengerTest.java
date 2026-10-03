package carpet.utils;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the message parser. A field of {@code Messenger.c} is a description: an optional style prefix
 * followed by the text. A field without a space and without a click prefix is a bare value, and the parser used to
 * treat all of it as a style prefix, so the value went missing from lines like the navigation status.
 */
class MessengerTest
{
    @Test
    void aDescriptionStillCarriesItsText()
    {
        assertEquals("Navigation: enabled ", Messenger.c("g Navigation: enabled ").getString());
        assertEquals("Navigation: SelfA1", Messenger.c("g Navigation: ", Messenger.s("SelfA1")).getString());
        assertEquals(TextColor.GRAY, style(Messenger.c("g Navigation: enabled ")).getColor());
        assertEquals(TextColor.WHITE, style(Messenger.c("w Navigation: enabled ")).getColor());
    }

    @Test
    void aBareValueIsNoLongerTakenForAStylePrefix()
    {
        assertEquals("goto", Messenger.c("goto").getString());
        // every one of these is made of nothing but formatting codes, which is what used to eat them
        for (String value : List.of("goto", "loop", "once", "1/2", "crit", "continuous", "minecraft:stone", "SelfA1"))
        {
            assertEquals(value, Messenger.c(value).getString(), value);
        }
    }

    @Test
    void theNavigationStatusLineKeepsEveryValue()
    {
        // the shape PlayerCommand sends: a description, then a style, then the value on its own
        assertEquals("Navigation: enabled mode=goto target=12.0 -60.0 0.5 radius=1.50", Messenger.c(
                "g Navigation: enabled ", "w mode=", "y ", "goto",
                "w  target=", "y 12.0 -60.0 0.5",
                "w  radius=", "y 1.50").getString());
    }

    @Test
    void aBareValueTakesTheStyleOfTheFieldBeforeIt()
    {
        Component line = Messenger.c("w mode=", "y ", "goto", "w  radius=", "y 1.50");
        List<Component> parts = line.getSiblings();
        // "mode=", the empty yellow style marker, "goto", " radius=", "1.50"
        assertEquals(5, parts.size());
        assertEquals(TextColor.YELLOW, parts.get(2).getStyle().getColor());
    }

    @Test
    void aLeadingSpaceStillAsksForTheDefaultStyle()
    {
        assertEquals("Navigation: 42", Messenger.c("g Navigation: ", " 42").getString());
    }

    @Test
    void anEmptyFieldAddsNothing()
    {
        assertEquals("Navigation: ", Messenger.c("g Navigation: ", "").getString());
    }

    @Test
    void clickAndHoverPrefixesStillActOnThePreviousText()
    {
        Component line = Messenger.c("g Navigation: enabled ", "!/tp 12 -60 0");
        // ClickEvent.Action is left out on purpose: reading it pulls in the game event registries,
        // which a plain unit test has not bootstrapped.
        ClickEvent click = line.getSiblings().get(0).getStyle().getClickEvent();
        assertEquals("/tp 12 -60 0", ((ClickEvent.RunCommand) click).command());

        ClickEvent copy = Messenger.c("g copied", "&SelfA1").getSiblings().get(0).getStyle().getClickEvent();
        assertEquals("SelfA1", ((ClickEvent.CopyToClipboard) copy).value());
    }

    @Test
    void aPlainValueHasNoClickEvent()
    {
        assertNull(style(Messenger.c("SelfA1")).getClickEvent());
    }

    @Test
    void formattingCodesStillApply()
    {
        Component line = Messenger.c("bi ", "caret");
        List<Component> parts = line.getSiblings();
        assertEquals("caret", parts.get(1).getString());
        assertTrue(parts.get(1).getStyle().isBold());
        assertTrue(parts.get(1).getStyle().isItalic());
    }

    /** The style of the first piece of text a composed line is made of. */
    private static Style style(Component line)
    {
        return line.getSiblings().isEmpty() ? line.getStyle() : line.getSiblings().get(0).getStyle();
    }
}