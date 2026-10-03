package carpet.commands;

import carpet.pvp.BotPvpConfig;
import carpet.pvp.autosetup.AutoMode;
import carpet.pvp.autosetup.AutoSetupManager;
import carpet.pvp.autosetup.AutoSetupSession;
import carpet.pvp.autosetup.AutoSetupSettings;
import carpet.pvp.autosetup.Menus;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * {@code /auto-setup}: everything a player needs to be fighting a bot with one command.
 *
 * <p>On its own it prints the menu of modes and difficulties, every entry a click that runs the
 * same command as if it had been typed. With a mode and a difficulty it sets the session up, and
 * while one runs it prints the menu of the next round after every round. {@code /auto-setup stop}
 * ends it and hands everything back.</p>
 */
public class AutoSetupCommand
{
    private AutoSetupCommand()
    {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("auto-setup")
                .executes(AutoSetupCommand::menu)
                .then(literal("stop")
                        .executes(AutoSetupCommand::stop))
                .then(argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> suggest(AutoMode.names(), b))
                        .executes(AutoSetupCommand::start)
                        .then(argument("difficulty", StringArgumentType.word())
                                .suggests((c, b) -> suggest(BotPvpConfig.difficulties(), b))
                                .executes(AutoSetupCommand::startAt)));
        dispatcher.register(command);
    }

    private static int menu(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = player(source);
        if (player == null) return 0;
        AutoSetupSession session = AutoSetupManager.session(player);
        Menus.modeMenu(player, session == null ? null : session.mode(),
                session == null ? null : session.difficulty());
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = player(source);
        if (player == null) return 0;
        AutoMode mode;
        try
        {
            mode = AutoMode.of(StringArgumentType.getString(context, "mode"));
        }
        catch (IllegalArgumentException e)
        {
            Messenger.m(source, "r ", e.getMessage(), "r . Known modes: ", "y ",
                    String.join(", ", AutoMode.names()));
            return 0;
        }
        return start(source, player, mode, null);
    }

    /** The form with a difficulty as well: {@code /auto-setup <mode> <difficulty>}. */
    private static int startAt(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = player(source);
        if (player == null) return 0;
        AutoMode mode;
        try
        {
            mode = AutoMode.of(StringArgumentType.getString(context, "mode"));
        }
        catch (IllegalArgumentException e)
        {
            Messenger.m(source, "r ", e.getMessage(), "r . Known modes: ", "y ",
                    String.join(", ", AutoMode.names()));
            return 0;
        }
        BotPvpConfig.Difficulty difficulty;
        try
        {
            difficulty = BotPvpConfig.Difficulty.valueOf(
                    StringArgumentType.getString(context, "difficulty").toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException unknown)
        {
            Messenger.m(source, "r Unknown difficulty. Known difficulties: ", "y ",
                    String.join(", ", BotPvpConfig.difficulties()));
            return 0;
        }
        return start(source, player, mode, difficulty);
    }

    /**
     * Sets a session up. A difficulty of null keeps the one the session already has, or the one the
     * {@code botDifficulty} rule names when it starts a new one.
     */
    private static int start(CommandSourceStack source, ServerPlayer player, AutoMode mode,
            BotPvpConfig.Difficulty difficulty)
    {
        try
        {
            AutoSetupSession session = AutoSetupManager.start(source.getServer(), player, mode, difficulty);
            Messenger.m(source, "g Setting up a ", "y " + mode.label(), "g  fight at ", "y "
                    + session.difficulty().name().toLowerCase(Locale.ROOT), "g  difficulty");
            return 1;
        }
        catch (AutoSetupSession.Failed e)
        {
            Messenger.m(source, "r ", e.getMessage());
            return 0;
        }
    }

    private static int stop(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = player(source);
        if (player == null) return 0;
        if (AutoSetupManager.session(player) == null)
        {
            Messenger.m(source, "y You are not in an ", "y /auto-setup", "y  session");
            return 0;
        }
        if (!AutoSetupManager.stop(source.getServer(), player.getName().getString()))
        {
            Messenger.m(source, "r Your things could not be given back yet, so they are being kept: they will "
                    + "be waiting for you the next time you log in");
            return 0;
        }
        return 1;
    }

    /** Checks the rule and that a session belongs to a player and not to the console. */
    private static ServerPlayer player(CommandSourceStack source)
    {
        if (!CommandHelper.canUseCommand(source, AutoSetupSettings.permission()))
        {
            Messenger.m(source, "r You don't have permission to use /auto-setup");
            return null;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null)
        {
            Messenger.m(source, "r /auto-setup is something a player does, not a command block");
        }
        return player;
    }
}
