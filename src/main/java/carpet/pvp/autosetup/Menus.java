package carpet.pvp.autosetup;

import carpet.pvp.BotPvpConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * What a player is told while a session runs: the menu that starts one, and the menu of a round
 * that is over. Every choice is a click that runs the argument form of {@code /auto-setup}, so that
 * a player who has never read the documentation goes from nothing to a fight in one click.
 */
public final class Menus
{
    private Menus() {}

    /** Once the arena is built and the bot is on its way. */
    public static void started(ServerPlayer player, AutoSetupSession session)
    {
        tell(player, "g Your ", "y " + session.mode().label(), "g  arena is ready, and ", "y "
                + session.botName(), "g  is coming to fight you. Starting in three seconds.");
    }

    /** The countdown, once a second. */
    public static void countdown(ServerPlayer player, int seconds)
    {
        if (seconds <= 0) return;
        tell(player, "y " + seconds + (seconds == 1 ? "..." : ""));
    }

    /** The fight itself, with what it is against. */
    public static void fight(ServerPlayer player, AutoSetupSession session)
    {
        tell(player, "l Fight!", "g  ", "y " + session.mode().label(), "g  at ", "y "
                + name(session.difficulty()));
    }

    /** A round that is over: how it ended, the score, and what to do next. */
    public static void roundOver(ServerPlayer player, AutoSetupSession session, boolean playerWon, String why)
    {
        tell(player, playerWon ? "l You won" : "n The bot won", "g  (", "y " + why, "g )   ", "g Score ",
                "y " + session.playerWins(), "g -", "y " + session.botWins());
        next(player, session);
    }

    /** The menu of a session between rounds; the score stays as it is across it. */
    public static void next(ServerPlayer player, AutoSetupSession session)
    {
        String mode = name(session.mode());
        MutableComponent line = plain("  ");
        line.append(button("Rematch", "/auto-setup " + mode, ChatFormatting.GREEN));
        line.append(plain("  "));
        line.append(button("Easier", "/auto-setup " + mode + " " + step(session.difficulty(), -1),
                ChatFormatting.AQUA));
        line.append(plain("  "));
        line.append(button("Harder", "/auto-setup " + mode + " " + step(session.difficulty(), 1),
                ChatFormatting.AQUA));
        line.append(plain("  "));
        line.append(button("Change mode", "/auto-setup", ChatFormatting.YELLOW));
        line.append(plain("  "));
        line.append(button("Stop", "/auto-setup stop", ChatFormatting.RED));
        player.sendSystemMessage(line);
    }

    public static void rematch(ServerPlayer player, AutoSetupSession session)
    {
        tell(player, "g Back to it in three seconds, against a ", "y " + name(session.difficulty()));
    }

    /**
     * The menu of {@code /auto-setup} on its own: the modes, then the difficulties of the mode the
     * player is in, or of the sword when they are not in a session yet.
     */
    public static void modeMenu(ServerPlayer player, AutoMode current, BotPvpConfig.Difficulty currentDifficulty)
    {
        AutoMode mode = current != null ? current : AutoMode.SWORD;
        String where = " /auto-setup " + name(mode);
        MutableComponent line = plain("  ");
        boolean first = true;
        for (AutoMode offered : AutoMode.offered())
        {
            if (!first) line.append(plain("  "));
            first = false;
            line.append(button(offered.label(), "/auto-setup " + name(offered),
                    offered == mode ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        }
        player.sendSystemMessage(line);

        MutableComponent levels = plain("  ");
        BotPvpConfig.Difficulty[] all = BotPvpConfig.Difficulty.values();
        for (int i = 0; i < all.length; i++)
        {
            if (i > 0) levels.append(plain("  "));
            levels.append(button(name(all[i]), where + " " + name(all[i]),
                    all[i] == currentDifficulty ? ChatFormatting.GREEN : ChatFormatting.AQUA));
        }
        player.sendSystemMessage(levels);
        player.sendSystemMessage(hint());
    }

    /**
     * The line under the buttons of the mode menu. Every piece is two spaces of indent or a colour
     * letter and a space and then the text, the way {@link #tell} reads: one letter per piece, so a
     * second letter would be swallowed rather than shown.
     */
    static MutableComponent hint()
    {
        return line("  ", "g the arena is built next to you", "g , ", "y /auto-setup stop",
                "g  takes it away again");
    }

    /** A session that has just been cleaned up. */
    public static void stopped(ServerPlayer player)
    {
        tell(player, "g The session is over: your things, your place and your game mode are back, and the "
                + "arena is gone.");
    }

    /** Things handed back after a session the server did not survive. */
    public static void recovered(ServerPlayer player)
    {
        tell(player, "g The server did not survive your last ", "y /auto-setup", "g  session, so your things, "
                + "your place and your game mode are back, and the arena is gone.");
    }

    /** A clickable choice: it runs the command as it stands, and shows it on hover. */
    public static MutableComponent button(String label, String command, ChatFormatting colour)
    {
        return Component.literal("[" + label + "]").withStyle(style -> style
                .withColor(colour).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    /** Sends one line of styled pieces: "g " is grey, "y " yellow, "l " green, "c " aqua. */
    public static void tell(ServerPlayer player, Object... parts)
    {
        if (player == null) return;
        player.sendSystemMessage(line(parts));
    }

    /** The line a set of pieces makes, which {@link #tell} sends and the tests read. */
    static MutableComponent line(Object... parts)
    {
        MutableComponent line = Component.literal("");
        for (Object part : parts)
        {
            line.append(part instanceof Component component ? component : styled(String.valueOf(part)));
        }
        return line;
    }

    private static MutableComponent styled(String text)
    {
        if (text.length() < 2) return plain(text);
        String rest = text.substring(2);
        return switch (text.charAt(0))
        {
            case 'g' -> plain(rest);
            case 'l' -> Component.literal(rest).withStyle(ChatFormatting.GREEN);
            case 'n' -> Component.literal(rest).withStyle(ChatFormatting.DARK_GRAY);
            case 'y' -> Component.literal(rest).withStyle(ChatFormatting.YELLOW);
            case 'c' -> Component.literal(rest).withStyle(ChatFormatting.AQUA);
            case 'r' -> Component.literal(rest).withStyle(ChatFormatting.RED);
            case 'm' -> Component.literal(rest).withStyle(ChatFormatting.LIGHT_PURPLE);
            default -> plain(text);
        };
    }

    private static MutableComponent plain(String text)
    {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static String name(AutoMode mode)
    {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private static String name(BotPvpConfig.Difficulty difficulty)
    {
        return difficulty.name().toLowerCase(Locale.ROOT);
    }

    /** The name of the difficulty one step up or down, which is what the harder and easier buttons run. */
    private static String step(BotPvpConfig.Difficulty difficulty, int by)
    {
        BotPvpConfig.Difficulty[] all = BotPvpConfig.Difficulty.values();
        return name(all[Math.max(0, Math.min(all.length - 1, difficulty.ordinal() + by))]);
    }
}
