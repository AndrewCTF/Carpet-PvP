package carpet.commands;

import carpet.CarpetSettings;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class SpawnPlayerCommand
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("spawnplayer")
                .executes(c -> {
                    Messenger.m(c.getSource(), "w Usage: /spawnplayer <name> [at <x y z>] [facing <yaw> <pitch>] [in <dimension>] [in <gamemode>]");
                    return 1;
                })
                .then(argument("player", StringArgumentType.word())
                        .executes(c -> forwardToPlayerSpawn(c, ""))
                        .then(argument("args", StringArgumentType.greedyString())
                                .executes(c -> forwardToPlayerSpawn(c, StringArgumentType.getString(c, "args")))));

        dispatcher.register(command);
    }

    private static int forwardToPlayerSpawn(CommandContext<CommandSourceStack> context, String trailingArgs)
    {
        if (!CommandHelper.canUseCommand(context.getSource(), CarpetSettings.commandPlayer))
        {
            Messenger.m(context.getSource(), "r You don't have permission to use /spawnplayer");
            return 0;
        }

        String player = StringArgumentType.getString(context, "player");
        String command = "player " + player + " spawn";
        if (!trailingArgs.isEmpty())
        {
            command += " " + trailingArgs;
        }
        context.getSource().getServer().getCommands().performPrefixedCommand(context.getSource(), command);
        return 1;
    }
}
