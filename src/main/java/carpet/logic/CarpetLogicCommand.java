package carpet.logic;

import carpet.CarpetSettings;
import carpet.logic.program.BotProgram;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class CarpetLogicCommand
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(literal("carpetlogic")
                .requires(source -> CommandHelper.canUseCommand(source, CarpetSettings.commandCarpetLogic))
                .executes(CarpetLogicCommand::showStatus)
                .then(literal("status").executes(CarpetLogicCommand::showStatus))
                .then(literal("programs")
                        .executes(CarpetLogicCommand::listPrograms)
                        .then(literal("run")
                                .then(argument("program", StringArgumentType.string())
                                        .then(argument("bot", StringArgumentType.word())
                                                .executes(CarpetLogicCommand::runProgram))))
                        .then(literal("stop")
                                .then(argument("bot", StringArgumentType.word())
                                        .executes(CarpetLogicCommand::stopProgram))))
                .then(literal("bots").executes(CarpetLogicCommand::listBots)));
    }

    private static boolean notReady(CommandContext<CommandSourceStack> ctx)
    {
        if (CarpetLogic.INSTANCE.getProgramExecutor() == null)
        {
            Messenger.m(ctx.getSource(), "r CarpetLogic is not initialized");
            return true;
        }
        return false;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx)
    {
        if (notReady(ctx)) return 0;
        CarpetLogic logic = CarpetLogic.INSTANCE;
        Messenger.m(ctx.getSource(), "w CarpetLogic: ",
                "y " + logic.getBotManager().getBots().size(), "w  bots, ",
                "y " + logic.getProgramExecutor().getRunningCount(), "w  running programs, ",
                "y " + logic.getProgramStorage().getCount(), "w  saved programs");
        return 1;
    }

    private static int listPrograms(CommandContext<CommandSourceStack> ctx)
    {
        if (notReady(ctx)) return 0;
        List<BotProgram> programs = CarpetLogic.INSTANCE.getProgramStorage().getAllPrograms();
        if (programs.isEmpty())
        {
            Messenger.m(ctx.getSource(), "y No saved programs");
            return 1;
        }
        for (BotProgram program : programs)
        {
            Messenger.m(ctx.getSource(), "w  - " + program.getName(), "g  (" + program.getActionCount() + " actions)");
        }
        return programs.size();
    }

    private static int runProgram(CommandContext<CommandSourceStack> ctx)
    {
        if (notReady(ctx)) return 0;
        String programName = StringArgumentType.getString(ctx, "program");
        String botName = StringArgumentType.getString(ctx, "bot");
        BotProgram program = CarpetLogic.INSTANCE.getProgramStorage().getByName(programName);
        if (program == null)
        {
            Messenger.m(ctx.getSource(), "r Program '" + programName + "' not found");
            return 0;
        }
        if (!CarpetLogic.INSTANCE.getProgramExecutor().startProgram(botName, program))
        {
            Messenger.m(ctx.getSource(), "r Failed to start the program. Is the bot spawned?");
            return 0;
        }
        Messenger.m(ctx.getSource(), "g Started '" + programName + "' on bot '" + botName + "'");
        return 1;
    }

    private static int stopProgram(CommandContext<CommandSourceStack> ctx)
    {
        if (notReady(ctx)) return 0;
        String botName = StringArgumentType.getString(ctx, "bot");
        if (!CarpetLogic.INSTANCE.getProgramExecutor().stopProgram(botName))
        {
            Messenger.m(ctx.getSource(), "r No program running on bot '" + botName + "'");
            return 0;
        }
        Messenger.m(ctx.getSource(), "y Stopped program on bot '" + botName + "'");
        return 1;
    }

    private static int listBots(CommandContext<CommandSourceStack> ctx)
    {
        if (notReady(ctx)) return 0;
        List<ServerPlayer> bots = CarpetLogic.INSTANCE.getBotManager().getBots();
        if (bots.isEmpty())
        {
            Messenger.m(ctx.getSource(), "y No active bots");
            return 1;
        }
        for (ServerPlayer bot : bots)
        {
            Messenger.m(ctx.getSource(), "w  - " + bot.getGameProfile().name(),
                    "g  " + String.format("[HP:%.0f Pos:%.0f,%.0f,%.0f]", bot.getHealth(), bot.getX(), bot.getY(), bot.getZ()));
        }
        return bots.size();
    }
}
