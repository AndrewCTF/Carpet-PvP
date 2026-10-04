package carpet.commands;

import carpet.pvp.bot.CombatCommands;
import carpet.pvp.bot.PracticeCommands;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.CombatTraces;
import carpet.pvp.MatchManager;
import carpet.pvp.drill.Drills;
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

import java.util.concurrent.CompletableFuture;

import static carpet.pvp.bot.BotCommands.styles;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * {@code /bot drill …}, {@code /bot match …}, {@code /bot spectate …}, {@code /bot skin …} and
 * {@code /bot trace}. The tree is Carpet's, the bodies are {@link PracticeCommands}, which the Paper
 * plugin calls as well.
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
                                .executes(c -> PracticeCommands.trace(c.getSource(), name(c))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeDrill()
    {
        return literal("drill")
                .then(literal("list").executes(c -> PracticeCommands.drillList(c.getSource())))
                .then(literal("stop").executes(c -> PracticeCommands.drillStop(c.getSource())))
                .then(argument("name", StringArgumentType.word())
                        .suggests((c, b) -> suggest(Drills.names(), b))
                        .executes(c -> PracticeCommands.drill(c.getSource(), name(c))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeMatch()
    {
        return literal("match")
                .then(literal("stop").executes(c -> PracticeCommands.matchStop(c.getSource())))
                .then(literal("join")
                        .then(argument("team", StringArgumentType.word())
                                .executes(c -> PracticeCommands.matchJoin(c.getSource(), string(c, "team")))))
                .then(literal("ffa")
                        .then(argument("count", IntegerArgumentType.integer(1, 16))
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests((c, b) -> suggest(styles(), b))
                                        .executes(c -> match(c, MatchManager.Mode.FFA, "count"))
                                        .then(argument("difficulty", StringArgumentType.word())
                                                .suggests((c, b) -> suggest(BotPvpConfig.difficulties(), b))
                                                .executes(c -> match(c, MatchManager.Mode.FFA, "count"))))))
                .then(literal("teams")
                        .then(argument("size", IntegerArgumentType.integer(1, 16))
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests((c, b) -> suggest(styles(), b))
                                        .executes(c -> match(c, MatchManager.Mode.TEAMS, "size"))
                                        .then(argument("difficulty", StringArgumentType.word())
                                                .suggests((c, b) -> suggest(BotPvpConfig.difficulties(), b))
                                                .executes(c -> match(c, MatchManager.Mode.TEAMS, "size"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeSpectate()
    {
        return literal("spectate")
                .then(literal("stop").executes(c -> PracticeCommands.spectateStop(c.getSource())))
                .then(argument("name", StringArgumentType.word())
                        .suggests((c, b) -> suggest(CombatCommands.bots(c.getSource().getServer()), b))
                        .executes(c -> PracticeCommands.spectate(c.getSource(), name(c))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeSkin()
    {
        return literal("skin")
                .then(argument("bot", StringArgumentType.word())
                        .suggests((c, b) -> suggest(CombatCommands.bots(c.getSource().getServer()), b))
                        .then(argument("player", StringArgumentType.word())
                                .suggests(BotPracticeCommand::onlineNames)
                                .executes(c -> PracticeCommands.skin(c.getSource(), string(c, "bot"),
                                        string(c, "player")))));
    }

    /** The accounts that are on the server now, which is what a skin can be taken from offline too. */
    private static CompletableFuture<Suggestions> onlineNames(CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder)
    {
        return suggest(context.getSource().getServer().getPlayerList().getPlayerNamesArray(), builder);
    }

    private static int match(CommandContext<CommandSourceStack> context, MatchManager.Mode mode, String size)
            throws CommandSyntaxException
    {
        return PracticeCommands.match(context.getSource(), mode, IntegerArgumentType.getInteger(context, size),
                string(context, "mode"), optional(() -> string(context, "difficulty"),
                        PracticeCommands.defaultDifficulty()));
    }

    private static String name(CommandContext<CommandSourceStack> context)
    {
        return StringArgumentType.getString(context, "name");
    }

    private static String string(CommandContext<CommandSourceStack> context, String argument)
    {
        return StringArgumentType.getString(context, argument);
    }

    private static <T> T optional(java.util.function.Supplier<T> getter, T fallback)
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
}