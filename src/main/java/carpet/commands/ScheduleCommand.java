package carpet.commands;

import carpet.CarpetSettings;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /schedule command &lt;ticks&gt; &lt;command_string&gt;
 * Schedules an arbitrary command to be executed after the specified number of game ticks.
 * The command runs with the same source/permissions as the caller.
 */
public class ScheduleCommand
{
    private static final List<ScheduledEntry> SCHEDULED = new ArrayList<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("schedule")
                .requires(source -> CommandHelper.canUseCommand(source, CarpetSettings.commandPlayer))
                .then(literal("command")
                        .then(argument("ticks", IntegerArgumentType.integer(1))
                                .then(argument("command", StringArgumentType.greedyString())
                                        .executes(ScheduleCommand::scheduleCommand))))
                .then(literal("list").executes(ScheduleCommand::listScheduled))
                .then(literal("clear").executes(ScheduleCommand::clearScheduled));
        dispatcher.register(command);
    }

    private static int scheduleCommand(CommandContext<CommandSourceStack> context)
    {
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        String cmd = StringArgumentType.getString(context, "command");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        long executeAt = server.getTickCount() + ticks;
        SCHEDULED.add(new ScheduledEntry(executeAt, cmd, source));

        Messenger.m(source, "g Scheduled '", "w " + cmd, "g ' to run in ", "y " + ticks, "g  ticks");
        return 1;
    }

    private static int listScheduled(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        long currentTick = server.getTickCount();

        if (SCHEDULED.isEmpty())
        {
            Messenger.m(source, "y No scheduled commands");
            return 0;
        }

        Messenger.m(source, "g Scheduled commands:");
        for (int i = 0; i < SCHEDULED.size(); i++)
        {
            ScheduledEntry e = SCHEDULED.get(i);
            long remaining = e.executeAtTick - currentTick;
            Messenger.m(source, "w  [" + i + "] '", "y " + e.command, "w ' in ",
                    "y " + remaining, "w  ticks (by ", "w " + e.source.getTextName(), "w )");
        }
        return SCHEDULED.size();
    }

    private static int clearScheduled(CommandContext<CommandSourceStack> context)
    {
        int count = SCHEDULED.size();
        SCHEDULED.clear();
        Messenger.m(context.getSource(), "g Cleared ", "y " + count, "g  scheduled commands");
        return count;
    }

    /**
     * Called every server tick from CarpetServer.tick() to execute any due scheduled commands.
     */
    public static void tick(MinecraftServer server)
    {
        if (SCHEDULED.isEmpty()) return;

        long currentTick = server.getTickCount();
        Iterator<ScheduledEntry> it = SCHEDULED.iterator();
        while (it.hasNext())
        {
            ScheduledEntry entry = it.next();
            if (currentTick >= entry.executeAtTick)
            {
                it.remove();
                try
                {
                    server.getCommands().performPrefixedCommand(entry.source, entry.command);
                }
                catch (Exception e)
                {
                    // Log but don't crash the server
                    CarpetSettings.LOG.warn("Scheduled command '{}' failed: {}", entry.command, e.getMessage());
                }
            }
        }
    }

    /**
     * Called on server close to clear all scheduled commands.
     */
    public static void onServerClosed()
    {
        SCHEDULED.clear();
    }

    private record ScheduledEntry(long executeAtTick, String command, CommandSourceStack source) {}
}
