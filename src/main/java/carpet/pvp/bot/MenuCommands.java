package carpet.pvp.bot;

import carpet.pvp.gui.BotGui;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import static carpet.pvp.bot.BotCommands.grey;
import static carpet.pvp.bot.BotCommands.red;
import static carpet.pvp.bot.BotCommands.say;
import static carpet.pvp.bot.BotCommands.yellow;

/**
 * {@code /bot gui}, which opens the menus of {@link BotGui}: the bots of the server, and one page each
 * of their settings, their kits and how to spawn them. The two subcommands under it are what the chat
 * prompt of the kit editor hands out. See {@link BotCommands} for how this is shared with the Paper
 * build.
 */
public final class MenuCommands
{
    private MenuCommands() {}

    public static int open(CommandSourceStack source)
    {
        ServerPlayer player = playerOnly(source);
        if (player == null) return 0;
        BotGui.openMain(player);
        return 1;
    }

    public static int saveAs(CommandSourceStack source, String name)
    {
        ServerPlayer player = playerOnly(source);
        if (player == null) return 0;
        return BotGui.saveAs(player, name) ? 1 : 0;
    }

    public static int discard(CommandSourceStack source)
    {
        ServerPlayer player = playerOnly(source);
        if (player == null) return 0;
        if (BotGui.discard(player))
        {
            say(source, grey("Threw the kit you were editing away"));
            return 1;
        }
        say(source, yellow("You have no kit open in the editor"));
        return 0;
    }

    /** The kit names a save could take, which is what the argument suggests. */
    public static java.util.List<String> suggestions(CommandSourceStack source)
    {
        ServerPlayer player = source.getPlayer();
        return player == null ? java.util.List.of() : BotGui.suggestions(player);
    }

    private static ServerPlayer playerOnly(CommandSourceStack source)
    {
        if (!BotCommands.canUse(source)) return null;
        ServerPlayer player = source.getPlayer();
        if (player == null)
        {
            say(source, red("Only a player can open the bot menu"));
        }
        return player;
    }
}