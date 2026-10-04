package carpet.commands;

import carpet.pvp.BotPvpConfig;
import carpet.pvp.bot.CombatCommands;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * {@code /bot} on Fabric: spawning PvP combat bots, setting them up, duelling and stats. The tree is
 * Carpet's, the bodies are {@link CombatCommands}, which the Paper plugin calls as well.
 */
public class BotCombatCommand
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("bot")
                .then(makeSpawn())
                .then(makeOption())
                .then(makeDuel())
                .then(literal("stop")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((c, b) -> suggest(bots(c), b))
                                .executes(c -> CombatCommands.stop(c.getSource(), StringArgumentType.getString(c, "name")))))
                .then(literal("stats")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((c, b) -> suggest(bots(c), b))
                                .executes(c -> CombatCommands.stats(c.getSource(), StringArgumentType.getString(c, "name")))));
        dispatcher.register(command);
    }

    /** Applies the settings of {@code /bot spawn} to the bots that have finished logging in. */
    public static void tick(MinecraftServer server)
    {
        CombatCommands.tick(server);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeSpawn()
    {
        return literal("spawn")
                .then(argument("name", StringArgumentType.word())
                        .then(argument("mode", StringArgumentType.word())
                                .suggests((c, b) -> suggest(CombatCommands.styles(), b))
                                .then(argument("difficulty", StringArgumentType.word())
                                        .suggests((c, b) -> suggest(BotPvpConfig.difficulties(), b))
                                        .executes(BotCombatCommand::spawn)
                                        .then(literal("at")
                                                .then(argument("position", Vec3Argument.vec3())
                                                        .executes(BotCombatCommand::spawn))))
                                .executes(BotCombatCommand::spawn)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeOption()
    {
        return literal("option")
                .then(argument("name", StringArgumentType.word())
                        .suggests((c, b) -> suggest(bots(c), b))
                        .executes(c -> CombatCommands.showOption(c.getSource(), StringArgumentType.getString(c, "name")))
                        .then(argument("key", StringArgumentType.word())
                                .suggests((c, b) -> suggest(List.of(BotPvpConfig.keys()), b))
                                .then(argument("value", StringArgumentType.greedyString())
                                        .executes(c -> CombatCommands.setOption(c.getSource(),
                                                StringArgumentType.getString(c, "name"),
                                                StringArgumentType.getString(c, "key"),
                                                StringArgumentType.getString(c, "value"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeDuel()
    {
        return literal("duel")
                .then(argument("a", StringArgumentType.word())
                        .suggests((c, b) -> suggest(bots(c), b))
                        .then(argument("b", StringArgumentType.word())
                                .suggests((c, b) -> suggest(bots(c), b))
                                .executes(c -> CombatCommands.duel(c.getSource(),
                                        StringArgumentType.getString(c, "a"), StringArgumentType.getString(c, "b")))));
    }

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return CombatCommands.spawn(context.getSource(), StringArgumentType.getString(context, "name"),
                StringArgumentType.getString(context, "mode"),
                getArgOrDefault(() -> StringArgumentType.getString(context, "difficulty"), CombatCommands.defaultDifficulty()),
                getArgOrDefault(() -> Vec3Argument.getVec3(context, "position"), context.getSource().getPosition()));
    }

    private static List<String> bots(CommandContext<CommandSourceStack> context)
    {
        return CombatCommands.bots(context.getSource().getServer());
    }

    private static <T> T getArgOrDefault(CommandSupplier<T> getter, T fallback)
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
    private interface CommandSupplier<T>
    {
        T get();
    }
}
