package carpet.pvp.bot;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotSettings;
import carpet.pvp.BotSkins;
import carpet.pvp.CombatTrace;
import carpet.pvp.CombatTraces;
import carpet.pvp.MatchManager;
import carpet.pvp.Spectators;
import carpet.pvp.drill.Drills;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

import static carpet.pvp.bot.BotCommands.grey;
import static carpet.pvp.bot.BotCommands.red;
import static carpet.pvp.bot.BotCommands.say;
import static carpet.pvp.bot.BotCommands.yellow;

/**
 * The practice half of {@code /bot}: the drills a player runs against a bot, matches between bots,
 * looking through a bot's eyes, another account's skin on a bot, and the trace of what a bot's last
 * fight was made of. See {@link BotCommands} for how this is shared with the Paper build.
 */
public final class PracticeCommands
{
    private PracticeCommands() {}

    // ===== drills =====

    public static int drillList(CommandSourceStack source)
    {
        if (!BotCommands.canUse(source)) return 0;
        say(source, grey("Drills: ").append(yellow(Drills.list())));
        return 1;
    }

    public static int drill(CommandSourceStack source, String name)
    {
        ServerPlayer player = playerOnly(source, "Only a player can run a drill");
        if (player == null) return 0;
        String problem = Drills.start(source.getServer(), player, name);
        if (problem != null)
        {
            say(source, red(problem));
            return 0;
        }
        say(source, grey("Started the ").append(yellow(name)).append(grey(" drill; /bot drill stop ends it")));
        return 1;
    }

    public static int drillStop(CommandSourceStack source)
    {
        ServerPlayer player = playerOnly(source, "Only a player can stop their own drill");
        if (player == null) return 0;
        String problem = Drills.stop(source.getServer(), player);
        if (problem != null)
        {
            say(source, red(problem));
            return 0;
        }
        return 1;
    }

    // ===== matches =====

    /** {@code /bot match ffa <count> <style> [difficulty]} and its teams form, which differ in mode. */
    public static int match(CommandSourceStack source, MatchManager.Mode mode, int count, String style,
            String difficulty)
    {
        ServerPlayer player = playerOnly(source, "Only a player can start a match where they stand");
        if (player == null) return 0;
        BotPvpConfig.CombatStyle combatStyle;
        try
        {
            combatStyle = BotPvpConfig.styleOf(style);
        }
        catch (IllegalArgumentException e)
        {
            say(source, red("Unknown combat style; the styles are ").append(yellow(String.join(", ", BotCommands.styles()))));
            return 0;
        }
        String problem = MatchManager.start(source.getServer(), player, mode, count, combatStyle, difficulty);
        if (problem != null)
        {
            say(source, red(problem));
            return 0;
        }
        say(source, yellow(MatchManager.status()));
        return count;
    }

    /** The difficulty a match leaves out takes from the rules. */
    public static String defaultDifficulty()
    {
        return BotSettings.botDifficulty;
    }

    public static int matchJoin(CommandSourceStack source, String team)
    {
        ServerPlayer player = playerOnly(source, "Only a player can join a team");
        if (player == null) return 0;
        String problem = MatchManager.join(player, team);
        if (problem != null)
        {
            say(source, red(problem));
            return 0;
        }
        say(source, grey("You are on ").append(yellow(team.toLowerCase(Locale.ROOT)))
                .append(grey(" until the match is over")));
        return 1;
    }

    public static int matchStop(CommandSourceStack source)
    {
        if (!BotCommands.canUse(source)) return 0;
        String problem = MatchManager.stop(source.getServer());
        if (problem != null)
        {
            say(source, red(problem));
            return 0;
        }
        return 1;
    }

    // ===== spectating =====

    public static int spectate(CommandSourceStack source, String name)
    {
        ServerPlayer player = playerOnly(source, "Only a player can be put in someone else's eyes");
        if (player == null) return 0;
        ServerPlayer bot = source.getServer().getPlayerList().getPlayerByName(name);
        if (bot == null)
        {
            say(source, red(name).append(red(" is not on this server")));
            return 0;
        }
        String problem = Spectators.start(player, bot);
        if (problem != null)
        {
            say(source, red(problem));
            return 0;
        }
        say(source, grey("Looking through ").append(yellow(bot.getName().getString()))
                .append(grey("; /bot spectate stop gives you your eyes back")));
        return 1;
    }

    public static int spectateStop(CommandSourceStack source)
    {
        ServerPlayer player = playerOnly(source, "Only a player can stop spectating");
        if (player == null) return 0;
        String problem = Spectators.stop(player);
        if (problem != null)
        {
            say(source, red(problem));
            return 0;
        }
        say(source, grey("You have your own eyes back"));
        return 1;
    }

    // ===== skins and traces =====

    public static int skin(CommandSourceStack source, String bot, String playername)
    {
        if (!BotCommands.canUse(source)) return 0;
        ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(bot);
        if (!(player instanceof EntityPlayerMPFake))
        {
            say(source, red(bot).append(red(" is not a bot of this server")));
            return 0;
        }
        String problem = BotSkins.wear(source.getServer(), player, playername);
        if (problem != null)
        {
            say(source, red(problem));
            return 0;
        }
        say(source, grey("Looking up ").append(yellow(playername)));
        return 1;
    }

    public static int trace(CommandSourceStack source, String name)
    {
        if (!BotCommands.canUse(source)) return 0;
        CombatTrace fight = CombatTraces.fight(name);
        String where = "its fight right now";
        if (fight == null)
        {
            fight = CombatTraces.last(name);
            where = "its last fight";
        }
        if (fight == null)
        {
            say(source, red(name).append(red(" has not fought")));
            return 0;
        }
        String problem = CombatTraces.problem(name);
        say(source, grey(name).append(grey(" ")).append(grey(where)).append(grey(": ")).append(yellow(fight.describe())));
        if (fight == CombatTraces.fight(name))
        {
            say(source, grey("It will be written to ").append(yellow(
                    CombatTraces.folder(source.getServer()).getFileName() + "/" + name + ".json"))
                    .append(grey(" when the fight is over")));
        }
        if (problem != null)
        {
            say(source, red("Its trace could not be written: ").append(yellow(problem)));
        }
        return 1;
    }

    /**
     * The player a command acts on, after the rule has had its say. A command block and a drill of
     * somebody else's are both refused, with the reason to give.
     */
    private static ServerPlayer playerOnly(CommandSourceStack source, String refused)
    {
        if (!BotCommands.canUse(source)) return null;
        ServerPlayer player = source.getPlayer();
        if (player == null)
        {
            say(source, red(refused));
        }
        return player;
    }
}