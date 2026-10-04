package carpet.commands;

import carpet.pvp.bot.AutoSetupCommands;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

import static carpet.pvp.bot.BotCommands.styles;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * {@code /auto-setup} on Fabric: {@code [<mode> [difficulty]]} or {@code stop}. The tree is Carpet's,
 * the bodies are {@link AutoSetupCommands}, which the Paper plugin calls as well.
 */
public class AutoSetupCommand
{
    private AutoSetupCommand()
    {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("auto-setup")
                .executes(c -> AutoSetupCommands.menu(c.getSource()))
                .then(literal("stop")
                        .executes(c -> AutoSetupCommands.stop(c.getSource())))
                .then(argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> suggest(carpet.pvp.autosetup.AutoMode.names(), b))
                        .executes(c -> AutoSetupCommands.start(c.getSource(), mode(c), null))
                        .then(argument("difficulty", StringArgumentType.word())
                                .suggests((c, b) -> suggest(carpet.pvp.BotPvpConfig.difficulties(), b))
                                .executes(c -> AutoSetupCommands.start(c.getSource(), mode(c), difficulty(c)))));
        dispatcher.register(command);
    }

    private static String mode(CommandContext<CommandSourceStack> context)
    {
        return StringArgumentType.getString(context, "mode");
    }

    private static String difficulty(CommandContext<CommandSourceStack> context)
    {
        return StringArgumentType.getString(context, "difficulty");
    }
}