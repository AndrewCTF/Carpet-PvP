package carpet.commands;

import carpet.CarpetSettings;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotSkins;
import carpet.pvp.CombatTrace;
import carpet.pvp.CombatTraces;
import carpet.pvp.MatchManager;
import carpet.pvp.Spectators;
import carpet.pvp.drill.Drills;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * The practice half of {@code /bot}: the drills a player runs against a bot, matches between bots,
 * looking through a bot's eyes, another account's skin on a bot, and the trace of what a bot's last
 * fight was made of.
 */
public class BotPracticeCommand
{
    private BotPracticeCommand()
    {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext)
    {
        dispatcher.register(literal("bot")
                .then(makeDrill())
                .then(makeMatch())
                .then(makeSpectate())
                .then(makeSkin())
                .then(literal("trace")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((c, b) -> suggest(CombatTraces.names(), b))
                                .executes(BotPracticeCommand::trace))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeDrill()
    {
        return literal("drill")
                .then(literal("list").executes(BotPracticeCommand::drillList))
                .then(literal("stop").executes(BotPracticeCommand::drillStop))
                .then(argument("name", StringArgumentType.word())
                        .suggests((c, b) -> suggest(Drills.names(), b))
                        .executes(BotPracticeCommand::drill));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeMatch()
    {
        return literal("match")
                .then(literal("stop").executes(BotPracticeCommand::matchStop))
                .then(literal("join")
                        .then(argument("team", StringArgumentType.word())
                                .executes(BotPracticeCommand::matchJoin)))
                .then(literal("ffa")
                        .then(argument("count", IntegerArgumentType.integer(1, 16))
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests((c, b) -> suggest(styles(), b))
                                        .executes(BotPracticeCommand::matchFfa)
                                        .then(argument("difficulty", StringArgumentType.word())
                                                .suggests((c, b) -> suggest(BotPvpConfig.difficulties(), b))
                                                .executes(BotPracticeCommand::matchFfa)))))
                .then(literal("teams")
                        .then(argument("size", IntegerArgumentType.integer(1, 16))
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests((c, b) -> suggest(styles(), b))
                                        .executes(BotPracticeCommand::matchTeams)
                                        .then(argument("difficulty", StringArgumentType.word())
                                                .suggests((c, b) -> suggest(BotPvpConfig.difficulties(), b))
                                                .executes(BotPracticeCommand::matchTeams)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeSpectate()
    {
        return literal("spectate")
                .then(literal("stop").executes(BotPracticeCommand::spectateStop))
                .then(argument("name", StringArgumentType.word())
                        .suggests((c, b) -> suggest(bots(c), b))
                        .executes(BotPracticeCommand::spectate));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeSkin()
    {
        return literal("skin")
                .then(argument("bot", StringArgumentType.word())
                        .suggests((c, b) -> suggest(bots(c), b))
                        .then(argument("player", StringArgumentType.word())
                                .suggests(BotPracticeCommand::onlineNames)
                                .executes(BotPracticeCommand::skin)));
    }

    private static String[] styles()
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

    private static List<String> bots(CommandContext<CommandSourceStack> context)
    {
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers())
        {
            if (player instanceof EntityPlayerMPFake)
            {
                names.add(player.getName().getString());
            }
        }
        return names;
    }

    /** The accounts that are on the server now, which is what a skin can be taken from offline too. */
    private static CompletableFuture<Suggestions> onlineNames(CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder)
    {
        return suggest(context.getSource().getServer().getPlayerList().getPlayerNamesArray(), builder);
    }

    // ===== drills =====

    private static int drillList(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        if (!canUse(source)) return 0;
        Messenger.m(source, "w Drills: ", "y ", Drills.list());
        return 1;
    }

    private static int drill(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (!canUse(source)) return 0;
        if (player == null)
        {
            Messenger.m(source, "r Only a player can run a drill");
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        String problem = Drills.start(source.getServer(), player, name);
        if (problem != null)
        {
            Messenger.m(source, "r " + problem);
            return 0;
        }
        Messenger.m(source, "w Started the ", "y " + name, "w  drill; /bot drill stop ends it");
        return 1;
    }

    private static int drillStop(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (!canUse(source)) return 0;
        if (player == null)
        {
            Messenger.m(source, "r Only a player can stop their own drill");
            return 0;
        }
        String problem = Drills.stop(source.getServer(), player);
        if (problem != null)
        {
            Messenger.m(source, "r " + problem);
            return 0;
        }
        return 1;
    }

    // ===== matches =====

    private static int matchFfa(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return match(context, MatchManager.Mode.FFA, "count");
    }

    private static int matchTeams(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return match(context, MatchManager.Mode.TEAMS, "size");
    }

    private static int match(CommandContext<CommandSourceStack> context, MatchManager.Mode mode, String size)
            throws CommandSyntaxException
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (!canUse(source)) return 0;
        if (player == null)
        {
            Messenger.m(source, "r Only a player can start a match where they stand");
            return 0;
        }
        int count = IntegerArgumentType.getInteger(context, size);
        BotPvpConfig.CombatStyle style;
        try
        {
            style = BotPvpConfig.styleOf(StringArgumentType.getString(context, "mode"));
        }
        catch (IllegalArgumentException e)
        {
            Messenger.m(source, "r Unknown combat style; the styles are ", "y ", String.join(", ", styles()));
            return 0;
        }
        String difficulty = optional(() -> StringArgumentType.getString(context, "difficulty"),
                CarpetSettings.botDifficulty);
        String problem = MatchManager.start(source.getServer(), player, mode, count, style, difficulty);
        if (problem != null)
        {
            Messenger.m(source, "r " + problem);
            return 0;
        }
        Messenger.m(source, "w ", "y ", MatchManager.status());
        return count;
    }

    private static int matchJoin(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (!canUse(source)) return 0;
        if (player == null)
        {
            Messenger.m(source, "r Only a player can join a team");
            return 0;
        }
        String problem = MatchManager.join(player, StringArgumentType.getString(context, "team"));
        if (problem != null)
        {
            Messenger.m(source, "r " + problem);
            return 0;
        }
        Messenger.m(source, "w You are on ", "y ", StringArgumentType.getString(context, "team").toLowerCase(Locale.ROOT),
                "w  until the match is over");
        return 1;
    }

    private static int matchStop(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        if (!canUse(source)) return 0;
        String problem = MatchManager.stop(source.getServer());
        if (problem != null)
        {
            Messenger.m(source, "r " + problem);
            return 0;
        }
        return 1;
    }

    // ===== spectating =====

    private static int spectate(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (!canUse(source)) return 0;
        if (player == null)
        {
            Messenger.m(source, "r Only a player can be put in someone else's eyes");
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        ServerPlayer bot = source.getServer().getPlayerList().getPlayerByName(name);
        if (bot == null)
        {
            Messenger.m(source, "r ", "rb " + name, "r  is not on this server");
            return 0;
        }
        String problem = Spectators.start(player, bot);
        if (problem != null)
        {
            Messenger.m(source, "r " + problem);
            return 0;
        }
        Messenger.m(source, "w Looking through ", "y ", bot.getName(), "w ; /bot spectate stop gives you your eyes back");
        return 1;
    }

    private static int spectateStop(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (!canUse(source)) return 0;
        if (player == null)
        {
            Messenger.m(source, "r Only a player can stop spectating");
            return 0;
        }
        String problem = Spectators.stop(player);
        if (problem != null)
        {
            Messenger.m(source, "r " + problem);
            return 0;
        }
        Messenger.m(source, "w You have your own eyes back");
        return 1;
    }

    // ===== skins and traces =====

    private static int skin(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        if (!canUse(source)) return 0;
        ServerPlayer bot = source.getServer().getPlayerList()
                .getPlayerByName(StringArgumentType.getString(context, "bot"));
        if (!(bot instanceof EntityPlayerMPFake))
        {
            Messenger.m(source, "r ", "rb " + StringArgumentType.getString(context, "bot"),
                    "r  is not a bot of this server");
            return 0;
        }
        String problem = BotSkins.wear(source.getServer(), bot, StringArgumentType.getString(context, "player"));
        if (problem != null)
        {
            Messenger.m(source, "r " + problem);
            return 0;
        }
        Messenger.m(source, "w Looking up ", "y ", StringArgumentType.getString(context, "player"));
        return 1;
    }

    private static int trace(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        if (!canUse(source)) return 0;
        String name = StringArgumentType.getString(context, "name");
        CombatTrace fight = CombatTraces.fight(name);
        String where = "its fight right now";
        if (fight == null)
        {
            fight = CombatTraces.last(name);
            where = "its last fight";
        }
        if (fight == null)
        {
            Messenger.m(source, "r ", "rb " + name, "r  has not fought");
            return 0;
        }
        String problem = CombatTraces.problem(name);
        Messenger.m(source, "w ", "y ", name, "w ", where, ": ", "y ", fight.describe());
        if (fight == CombatTraces.fight(name))
        {
            Messenger.m(source, "w It will be written to ", "y ",
                    CombatTraces.folder(source.getServer()).getFileName() + "/" + name + ".json",
                    "w  when the fight is over");
        }
        if (problem != null)
        {
            Messenger.m(source, "r Its trace could not be written: ", "y ", problem);
        }
        return 1;
    }

    private static boolean canUse(CommandSourceStack source)
    {
        if (CommandHelper.canUseCommand(source, CarpetSettings.commandBot))
        {
            return true;
        }
        Messenger.m(source, "r You don't have permission to use /bot commands");
        return false;
    }

    private static <T> T optional(Supplier<T> getter, T fallback)
    {
        try
        {
            return getter.get();
        }
        catch (IllegalArgumentException notPresent)
        {
            return fallback;
        }
    }

    @FunctionalInterface
    private interface Supplier<T>
    {
        T get();
    }
}