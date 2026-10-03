package carpet.commands;

import carpet.CarpetSettings;
import carpet.fakes.ServerPlayerInterface;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.FactionManager;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * The {@code /bot} command: spawning PvP combat bots, setting them up, starting and stopping duels
 * and reading what their bodies did.
 */
public class BotCombatCommand
{
    /** Settings of a {@code /bot spawn}, applied once the fake player has finished logging in. */
    private record PendingSpawn(BotPvpConfig.CombatStyle style, String difficulty)
    {
    }

    private static final Map<String, PendingSpawn> pending = new HashMap<>();

    private BotCombatCommand()
    {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("bot")
                .then(makeSpawn())
                .then(makeOption())
                .then(makeDuel())
                .then(literal("stop")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((c, b) -> suggest(bots(c), b))
                                .executes(BotCombatCommand::stop)))
                .then(literal("stats")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((c, b) -> suggest(bots(c), b))
                                .executes(BotCombatCommand::stats)));
        dispatcher.register(command);
    }

    /** Applies the settings of {@code /bot spawn} to the bots that have finished logging in. */
    public static void tick(MinecraftServer server)
    {
        if (pending.isEmpty())
        {
            return;
        }
        for (String name : new ArrayList<>(pending.keySet()))
        {
            ServerPlayer player = server.getPlayerList().getPlayerByName(name);
            if (player instanceof EntityPlayerMPFake bot)
            {
                applySpawn(bot, pending.remove(name));
            }
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeSpawn()
    {
        return literal("spawn")
                .then(argument("name", StringArgumentType.word())
                        .then(argument("mode", StringArgumentType.word())
                                .suggests((c, b) -> suggest(styles(), b))
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
                        .executes(BotCombatCommand::showOption)
                        .then(argument("key", StringArgumentType.word())
                                .suggests((c, b) -> suggest(List.of(BotPvpConfig.KEYS), b))
                                .then(argument("value", StringArgumentType.greedyString())
                                        .executes(BotCombatCommand::setOption))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeDuel()
    {
        return literal("duel")
                .then(argument("a", StringArgumentType.word())
                        .suggests((c, b) -> suggest(bots(c), b))
                        .then(argument("b", StringArgumentType.word())
                                .suggests((c, b) -> suggest(bots(c), b))
                                .executes(BotCombatCommand::duel)));
    }

    private static String[] styles()
    {
        BotPvpConfig.CombatStyle[] values = BotPvpConfig.CombatStyle.values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++)
        {
            names[i] = values[i].name().toLowerCase(Locale.ROOT);
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

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        CommandSourceStack source = context.getSource();
        if (!canUse(source))
        {
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        String mode = StringArgumentType.getString(context, "mode");
        BotPvpConfig.CombatStyle style;
        try
        {
            style = BotPvpConfig.CombatStyle.valueOf(mode.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            Messenger.m(source, "r Unknown combat style: " + mode + ". Known styles: "
                    + String.join(", ", styles()));
            return 0;
        }
        String difficulty = getArgOrDefault(() -> StringArgumentType.getString(context, "difficulty"),
                CarpetSettings.botDifficulty);
        MinecraftServer server = source.getServer();
        if (server.getPlayerList().getPlayerByName(name) != null)
        {
            Messenger.m(source, "r " + name + " is already online");
            return 0;
        }
        if (EntityPlayerMPFake.isSpawningPlayer(name))
        {
            Messenger.m(source, "r " + name + " is already logging in");
            return 0;
        }
        Vec3 pos = getArgOrDefault(() -> Vec3Argument.getVec3(context, "position"), source.getPosition());
        ResourceKey<Level> dimension = source.getLevel().dimension();
        if (!EntityPlayerMPFake.createFake(name, server, pos, source.getRotation().y, source.getRotation().x,
                dimension, GameType.SURVIVAL, false))
        {
            Messenger.m(source, "r Could not spawn " + name);
            return 0;
        }
        PendingSpawn spawn = new PendingSpawn(style, difficulty);
        pending.put(name, spawn);
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player instanceof EntityPlayerMPFake bot)
        {
            applySpawn(bot, spawn);
            pending.remove(name);
        }
        Messenger.m(source, "w Spawned " + name + " fighting with " + style + " at difficulty " + difficulty);
        return 1;
    }

    /**
     * Turns the bot into a fighter. A fake player spawns without a kit in this version, so the gear is
     * left to {@code /player <name> equip} or {@code /give}.
     */
    private static void applySpawn(EntityPlayerMPFake bot, PendingSpawn spawn)
    {
        BotPvpConfig cfg = bot.getPvpConfig();
        cfg.combatStyle = spawn.style();
        cfg.combat = true;
        cfg.autoTarget = true;
        cfg.targetBots = true;
        String error = cfg.applyDifficulty(spawn.difficulty());
        if (error != null)
        {
            Messenger.m(bot.level().getServer().createCommandSourceStack(), "r " + error);
        }
    }

    private static int showOption(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        if (!canUse(source))
        {
            return 0;
        }
        EntityPlayerMPFake bot = bot(source, StringArgumentType.getString(context, "name"));
        if (bot == null)
        {
            return 0;
        }
        Messenger.m(source, "w " + bot.getName().getString() + ": " + bot.getPvpConfig().describe());
        return 1;
    }

    private static int setOption(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        if (!canUse(source))
        {
            return 0;
        }
        EntityPlayerMPFake bot = bot(source, StringArgumentType.getString(context, "name"));
        if (bot == null)
        {
            return 0;
        }
        String key = StringArgumentType.getString(context, "key");
        String value = StringArgumentType.getString(context, "value");
        String error = bot.getPvpConfig().apply(key, value);
        if (error != null)
        {
            Messenger.m(source, "r " + error);
            return 0;
        }
        Messenger.m(source, "w " + bot.getName().getString() + ": " + key + " = " + value);
        return 1;
    }

    /** Makes two bots target each other by putting them in different factions and turning them on. */
    private static int duel(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        if (!canUse(source))
        {
            return 0;
        }
        EntityPlayerMPFake a = bot(source, StringArgumentType.getString(context, "a"));
        EntityPlayerMPFake b = bot(source, StringArgumentType.getString(context, "b"));
        if (a == null || b == null)
        {
            return 0;
        }
        if (a.getUUID().equals(b.getUUID()))
        {
            Messenger.m(source, "r A bot cannot duel itself");
            return 0;
        }
        String mine = "bot_" + a.getName().getString();
        String theirs = "bot_" + b.getName().getString();
        for (EntityPlayerMPFake bot : new EntityPlayerMPFake[] {a, b})
        {
            BotPvpConfig cfg = bot.getPvpConfig();
            cfg.combat = true;
            cfg.autoTarget = true;
            cfg.targetBots = true;
            cfg.targetRange = Math.max(cfg.targetRange, 32.0);
            cfg.faction = bot == a ? mine : theirs;
            FactionManager.create(cfg.faction);
            FactionManager.join(cfg.faction, bot.getUUID());
        }
        Messenger.m(source, "w " + a.getName().getString() + " and " + b.getName().getString() + " are duelling");
        return 2;
    }

    private static int stop(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        if (!canUse(source))
        {
            return 0;
        }
        EntityPlayerMPFake bot = bot(source, StringArgumentType.getString(context, "name"));
        if (bot == null)
        {
            return 0;
        }
        bot.getPvpConfig().combat = false;
        ((ServerPlayerInterface) bot).getActionPack().stopNavigation();
        Messenger.m(source, "w " + bot.getName().getString() + " stopped fighting");
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        if (!canUse(source))
        {
            return 0;
        }
        EntityPlayerMPFake bot = bot(source, StringArgumentType.getString(context, "name"));
        if (bot == null)
        {
            return 0;
        }
        BotBody body = bot.getBotBrain().body();
        if (body == null)
        {
            Messenger.m(source, "w " + bot.getName().getString() + " has not fought yet");
            return 1;
        }
        Messenger.m(source, "w " + bot.getName().getString() + ": " + body.stats().describe());
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

    private static EntityPlayerMPFake bot(CommandSourceStack source, String name)
    {
        ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
        if (player instanceof EntityPlayerMPFake bot)
        {
            return bot;
        }
        Messenger.m(source, "r " + name + " is not a bot of this server");
        return null;
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