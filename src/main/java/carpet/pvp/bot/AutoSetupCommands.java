package carpet.pvp.bot;

import carpet.pvp.BotPvpConfig;
import carpet.pvp.autosetup.AutoMode;
import carpet.pvp.autosetup.AutoSetupManager;
import carpet.pvp.autosetup.AutoSetupSession;
import carpet.pvp.autosetup.AutoSetupSettings;
import carpet.pvp.autosetup.Menus;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

import static carpet.pvp.bot.BotCommands.grey;
import static carpet.pvp.bot.BotCommands.red;
import static carpet.pvp.bot.BotCommands.say;
import static carpet.pvp.bot.BotCommands.yellow;

/**
 * {@code /auto-setup}: everything a player needs to be fighting a bot with one command.
 *
 * <p>On its own it prints the menu of modes and difficulties, every entry a click that runs the same
 * command as if it had been typed. With a mode and a difficulty it sets the session up, and while one
 * runs it prints the menu of the next round after every round. {@code /auto-setup stop} ends it and
 * hands everything back. See {@link BotCommands} for how this is shared with the Paper build.</p>
 */
public final class AutoSetupCommands
{
    private AutoSetupCommands() {}

    public static int menu(CommandSourceStack source)
    {
        ServerPlayer player = playerOnly(source);
        if (player == null) return 0;
        AutoSetupSession session = AutoSetupManager.session(player);
        Menus.modeMenu(player, session == null ? null : session.mode(),
                session == null ? null : session.difficulty());
        return 1;
    }

    /** {@code /auto-setup <mode> [difficulty]}: a difficulty of null keeps the one the session has. */
    public static int start(CommandSourceStack source, String mode, String difficulty)
    {
        ServerPlayer player = playerOnly(source);
        if (player == null) return 0;
        AutoMode wanted;
        try
        {
            wanted = AutoMode.of(mode);
        }
        catch (IllegalArgumentException e)
        {
            say(source, red(e.getMessage()).append(red(". Known modes: "))
                    .append(yellow(String.join(", ", AutoMode.names()))));
            return 0;
        }
        BotPvpConfig.Difficulty level = null;
        if (difficulty != null)
        {
            try
            {
                level = BotPvpConfig.Difficulty.valueOf(difficulty.toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException unknown)
            {
                say(source, red("Unknown difficulty. Known difficulties: ")
                        .append(yellow(String.join(", ", BotPvpConfig.difficulties()))));
                return 0;
            }
        }
        return start(source, player, wanted, level);
    }

    private static int start(CommandSourceStack source, ServerPlayer player, AutoMode mode,
            BotPvpConfig.Difficulty difficulty)
    {
        try
        {
            AutoSetupSession session = AutoSetupManager.start(source.getServer(), player, mode, difficulty);
            say(source, grey("Setting up a ").append(yellow(mode.label())).append(grey(" fight at "))
                    .append(yellow(session.difficulty().name().toLowerCase(Locale.ROOT)))
                    .append(grey(" difficulty")));
            return 1;
        }
        catch (AutoSetupSession.Failed e)
        {
            say(source, red(e.getMessage()));
            return 0;
        }
    }

    public static int stop(CommandSourceStack source)
    {
        ServerPlayer player = playerOnly(source);
        if (player == null) return 0;
        if (AutoSetupManager.session(player) == null)
        {
            say(source, yellow("You are not in an ").append(yellow("/auto-setup")).append(yellow(" session")));
            return 0;
        }
        if (!AutoSetupManager.stop(source.getServer(), player.getName().getString()))
        {
            say(source, red("Your things could not be given back yet, so they are being kept: they will be "
                    + "waiting for you the next time you log in"));
            return 0;
        }
        return 1;
    }

    /** Checks the rule and that a session belongs to a player and not to the console. */
    private static ServerPlayer playerOnly(CommandSourceStack source)
    {
        if (!AutoSetupSettings.permission.test(source))
        {
            say(source, red("You don't have permission to use /auto-setup"));
            return null;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null)
        {
            say(source, red("/auto-setup is something a player does, not a command block"));
        }
        return player;
    }
}