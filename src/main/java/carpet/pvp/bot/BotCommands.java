package carpet.pvp.bot;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The bodies of {@code /bot}, written once for both platforms. Carpet's {@code /bot} builds a
 * Brigadier tree over the vanilla source stack and Paper's builds one over its own, which unwraps to
 * this one; both then call straight into these methods.
 *
 * <p>Messages are plain {@link Component}s rather than Carpet's {@code Messenger}, which the Paper
 * build does not have.</p>
 */
public final class BotCommands
{
    /** Who may run the bot commands. Carpet sets this from its {@code commandBot} rule. */
    public static Predicate<CommandSourceStack> mayCommandBots = source -> true;

    private BotCommands() {}

    /** Says what the command did, the way Carpet's Messenger does: to the sender, and to whoever is watching. */
    static void say(CommandSourceStack source, Component message)
    {
        source.sendSuccess(() -> message, source.getServer() != null && source.getServer().overworld() != null);
    }

    static MutableComponent grey(String text)
    {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    static MutableComponent yellow(String text)
    {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    static MutableComponent red(String text)
    {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    /** Whether the source may run the bot commands at all; says so when it may not. */
    public static boolean canUse(CommandSourceStack source)
    {
        if (mayCommandBots.test(source)) return true;
        say(source, red("You don't have permission to use /bot commands"));
        return false;
    }

    /** The combat styles a bot or a match takes, MELEE under the name players know it by. */
    public static String[] styles()
    {
        BotPvpConfig.CombatStyle[] values = BotPvpConfig.CombatStyle.values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++)
        {
            names[i] = values[i] == BotPvpConfig.CombatStyle.MELEE ? "sword"
                    : values[i].name().toLowerCase(Locale.ROOT);
        }
        return names;
    }

    /**
     * Whether the source may run a command that acts on the given players. As well as the rule, an
     * un-opped player must not be reaching into someone else's inventory; a fake player is nobody's
     * real player, so anyone who may run the commands may drive one.
     */
    public static boolean mayTouch(CommandSourceStack source, Collection<? extends ServerPlayer> targets)
    {
        if (!canUse(source)) return false;
        ServerPlayer sender = source.getPlayer();
        if (sender == null || Commands.LEVEL_GAMEMASTERS.check(source.permissions())) return true;
        for (ServerPlayer player : targets)
        {
            if (player != sender && !(player instanceof EntityPlayerMPFake))
            {
                say(source, red("Non OP players can't control other real players"));
                return false;
            }
        }
        return true;
    }
}
