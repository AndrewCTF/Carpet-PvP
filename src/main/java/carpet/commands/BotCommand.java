package carpet.commands;

import carpet.pvp.bot.KitCommands;
import carpet.pvp.kit.KitStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;

import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * {@code /bot kit} on Fabric. The tree is Carpet's, the bodies are {@link KitCommands}, which the
 * Paper plugin calls as well.
 */
public class BotCommand
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("bot")
                .then(literal("kit")
                        .then(literal("list").executes(c -> KitCommands.list(c.getSource())))
                        .then(literal("reload").executes(c -> KitCommands.reload(c.getSource())))
                        .then(literal("give")
                                .then(argument("players", EntityArgument.players())
                                        .then(argument("kit", StringArgumentType.word())
                                                .suggests((c, b) -> suggest(kitNames(c), b))
                                                .executes(c -> KitCommands.give(c.getSource(),
                                                        EntityArgument.getPlayers(c, "players"),
                                                        StringArgumentType.getString(c, "kit"))))))
                        .then(literal("save")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(c -> KitCommands.save(c.getSource(), StringArgumentType.getString(c, "name")))))
                        .then(literal("delete")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> suggest(customKitNames(c), b))
                                        .executes(c -> KitCommands.delete(c.getSource(), StringArgumentType.getString(c, "name")))))
                        .then(literal("restore")
                                .executes(c -> KitCommands.restoreSelf(c.getSource()))
                                .then(argument("players", EntityArgument.players())
                                        .executes(c -> KitCommands.restore(c.getSource(),
                                                                EntityArgument.getPlayers(c, "players")))))
                );
        dispatcher.register(command);
    }

    private static List<String> kitNames(CommandContext<CommandSourceStack> context)
    {
        return KitStore.of(context.getSource().getServer()).names();
    }

    private static List<String> customKitNames(CommandContext<CommandSourceStack> context)
    {
        return KitStore.of(context.getSource().getServer()).customNames();
    }
}
