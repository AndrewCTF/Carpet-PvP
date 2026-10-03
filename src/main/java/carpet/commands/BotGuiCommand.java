package carpet.commands;

import carpet.CarpetSettings;
import carpet.pvp.gui.BotGui;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * {@code /bot gui}, which opens the menus of {@link BotGui}: the bots of the server, and one page each
 * of their settings, their kits and how to spawn them. The two subcommands under it are what the chat
 * prompt of the kit editor hands out.
 */
public class BotGuiCommand
{
    private BotGuiCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("bot")
                .then(literal("gui")
                        .executes(BotGuiCommand::open)
                        .then(literal("saveas")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> suggest(suggestions(c), b))
                                        .executes(BotGuiCommand::saveAs)))
                        .then(literal("discard").executes(BotGuiCommand::discard)));
        dispatcher.register(command);
    }

    private static int open(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (cantUse(source, player)) return 0;
        BotGui.openMain(player);
        return 1;
    }

    private static int saveAs(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (cantUse(source, player)) return 0;
        return BotGui.saveAs(player, StringArgumentType.getString(context, "name")) ? 1 : 0;
    }

    private static int discard(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (cantUse(source, player)) return 0;
        if (BotGui.discard(player))
        {
            Messenger.m(source, "g Threw the kit you were editing away");
            return 1;
        }
        Messenger.m(source, "y You have no kit open in the editor");
        return 0;
    }

    private static List<String> suggestions(CommandContext<CommandSourceStack> context)
    {
        ServerPlayer player = context.getSource().getPlayer();
        return player == null ? List.of() : BotGui.suggestions(player);
    }

    private static boolean cantUse(CommandSourceStack source, Player player)
    {
        if (player == null)
        {
            Messenger.m(source, "r Only a player can open the bot menu");
            return true;
        }
        if (CommandHelper.canUseCommand(source, CarpetSettings.commandBot)) return false;
        Messenger.m(source, "r You don't have permission to use /bot commands");
        return true;
    }
}