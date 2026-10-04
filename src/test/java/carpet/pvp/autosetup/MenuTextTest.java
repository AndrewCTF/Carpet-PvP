package carpet.pvp.autosetup;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What the {@code /auto-setup} menu puts in chat. A piece is a colour letter, a space and then the
 * text, and the reader drops the first two characters, so a piece with two letters in it loses a
 * word rather than a prefix.
 */
class MenuTextTest
{
    private static final String HINT = "  the arena is built next to you, /auto-setup stop takes it away again";

    @Test
    void theHintReadsAsTwoPlainHalvesAroundAYellowCommand()
    {
        MutableComponent hint = Menus.hint();
        assertEquals(HINT, hint.getString());
        List<Component> pieces = hint.getSiblings();
        assertEquals(5, pieces.size(), "the hint is two spaces of indent and one piece per colour");
        for (int i : new int[] {0, 1, 2, 4})
        {
            assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), colour(pieces.get(i)),
                    "piece " + i + " of the hint is grey");
        }
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW), colour(pieces.get(3)),
                "the command of the hint is yellow");
    }

    @Test
    void everyColourLetterDropsItsPrefixAndColoursTheRest()
    {
        for (ChatFormatting colour : new ChatFormatting[] {ChatFormatting.GRAY, ChatFormatting.DARK_GRAY,
                ChatFormatting.YELLOW, ChatFormatting.GREEN, ChatFormatting.AQUA, ChatFormatting.RED,
                ChatFormatting.LIGHT_PURPLE})
        {
            char letter = switch (colour)
            {
                case GRAY -> 'g';
                case DARK_GRAY -> 'n';
                case YELLOW -> 'y';
                case GREEN -> 'l';
                case AQUA -> 'c';
                case RED -> 'r';
                default -> 'm';
            };
            MutableComponent line = Menus.line(letter + " the text");
            assertEquals("the text", line.getString(), "the prefix of " + letter + " is dropped");
            assertEquals(TextColor.fromLegacyFormat(colour), colour(line.getSiblings().get(0)),
                    letter + " is its own colour");
        }
    }

    @Test
    void aPieceWithNoColourLetterIsLeftAlone()
    {
        // Two spaces of indent, which is what the button lines of the menu start with.
        assertEquals("  ", Menus.line("  ").getString());
        assertEquals("a button", Menus.line("a button").getString());
    }

    private static TextColor colour(Component piece)
    {
        TextColor colour = piece.getStyle().getColor();
        assertNotNull(colour, piece.getString() + " has no colour");
        return colour;
    }
}
