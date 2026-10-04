package carpet.commands;

import carpet.pvp.bot.MenuCommands;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * {@code /bot gui} on Fabric. The tree is Carpet's, the bodies are {@link MenuCommands}, which the
 * Paper plugin calls as well.
 */
public class BotGuiCommand
{
    private BotGuiCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("bot")
                .then(literal("gui")
                        .executes(c -> MenuCommands.open(c.getSource()))
                        .then(literal("saveas")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> suggest(MenuCommands.suggestions(c.getSource()), b))
                                        .executes(c -> MenuCommands.saveAs(c.getSource(),
                                                StringArgumentType.getString(c, "name")))))
                        .then(literal("discard").executes(c -> MenuCommands.discard(c.getSource()))));
        dispatcher.register(command);
    }
}