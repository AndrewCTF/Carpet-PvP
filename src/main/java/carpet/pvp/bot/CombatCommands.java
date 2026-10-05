package carpet.pvp.bot;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotSettings;
import carpet.pvp.FactionManager;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import carpet.pvp.style.StyleIndex;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static carpet.pvp.bot.BotCommands.grey;
import static carpet.pvp.bot.BotCommands.red;
import static carpet.pvp.bot.BotCommands.say;
import static carpet.pvp.bot.BotCommands.yellow;

/**
 * {@code /bot}: spawning PvP combat bots, setting them up, starting and stopping duels and reading
 * what their bodies did. See {@link BotCommands} for how this is shared with the Paper build.
 */
public final class CombatCommands
{
    /** Settings of a {@code /bot spawn}, applied once the fake player has finished logging in. */
    private record PendingSpawn(BotPvpConfig.CombatStyle style, String difficulty)
    {
    }

    private static final Map<String, PendingSpawn> pending = new HashMap<>();

    private CombatCommands()
    {
    }

    /** The bots of this server, for the argument suggestions. */
    public static List<String> bots(MinecraftServer server)
    {
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (player instanceof EntityPlayerMPFake)
            {
                names.add(player.getName().getString());
            }
        }
        return names;
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

    /** The style of a bot that was not given one. */
    public static final String DEFAULT_MODE = "sword";

    /** {@code /bot spawn} with nothing after it: a sword bot under the first free name, in front of the sender. */
    public static int spawn(CommandSourceStack source)
    {
        MinecraftServer server = source.getServer();
        int number = 1;
        while (server.getPlayerList().getPlayerByName("Bot" + number) != null || EntityPlayerMPFake.isSpawningPlayer("Bot" + number))
        {
            number++;
        }
        // Three blocks in front of the sender, where it can be seen, unless something is in the way there.
        Vec3 here = source.getPosition();
        float yaw = source.getRotation().y * Mth.DEG_TO_RAD;
        Vec3 ahead = here.add(-Mth.sin(yaw) * 3.0D, 0.0D, Mth.cos(yaw) * 3.0D);
        // A player's box, written out: where the player entity type lives differs between the versions built here.
        boolean free = source.getLevel().noCollision(new AABB(ahead.x - 0.3D, ahead.y, ahead.z - 0.3D, ahead.x + 0.3D, ahead.y + 1.8D, ahead.z + 0.3D));
        return spawn(source, "Bot" + number, DEFAULT_MODE, defaultDifficulty(), free ? ahead : here);
    }

    public static int spawn(CommandSourceStack source, String name, String mode, String difficulty, Vec3 pos)
    {
        if (!BotCommands.canUse(source))
        {
            return 0;
        }
        BotPvpConfig.CombatStyle style;
        try
        {
            style = BotPvpConfig.styleOf(mode);
        }
        catch (IllegalArgumentException e)
        {
            say(source, red("Unknown combat style: " + mode + ". Known styles: " + String.join(", ", BotCommands.styles())));
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server.getPlayerList().getPlayerByName(name) != null)
        {
            say(source, red(name + " is already online"));
            return 0;
        }
        if (EntityPlayerMPFake.isSpawningPlayer(name))
        {
            say(source, red(name + " is already logging in"));
            return 0;
        }
        if (!EntityPlayerMPFake.createFake(name, server, pos, source.getRotation().y, source.getRotation().x,
                source.getLevel().dimension(), GameType.SURVIVAL, false))
        {
            say(source, red("Could not spawn " + name));
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
        // With a profile to look up first, the bot joins a moment later: only one that is here is called spawned.
        say(source, grey(pending.containsKey(name) ? "Spawning " : "Spawned ").append(grey(name)).append(grey(" fighting with "))
                .append(yellow(style.toString())).append(grey(" at difficulty ")).append(yellow(difficulty)));
        say(source, grey("It attacks players in survival mode. ").append(yellow("/bot stop " + name))
                .append(grey(" makes it stand still, ")).append(yellow("/bot")).append(grey(" has everything else")));
        return 1;
    }

    /** Turns the bot into a fighter: the kit of its style, the style itself and the difficulty preset. */
    private static void applySpawn(EntityPlayerMPFake bot, PendingSpawn spawn)
    {
        MinecraftServer server = bot.level().getServer();
        String kitName = StyleIndex.kit(spawn.style());
        if (kitName != null)
        {
            KitStore.of(server).get(kitName).ifPresent(kit -> KitInventory.apply(bot, kit, server.registryAccess()));
        }
        BotPvpConfig cfg = bot.getPvpConfig();
        cfg.combatStyle = spawn.style();
        cfg.combat = true;
        cfg.autoTarget = true;
        cfg.targetBots = true;
        String error = cfg.applyDifficulty(spawn.difficulty());
        if (error != null)
        {
            say(server.createCommandSourceStack(), red(error));
        }
    }

    public static int showOption(CommandSourceStack source, String name)
    {
        if (!BotCommands.canUse(source))
        {
            return 0;
        }
        EntityPlayerMPFake bot = bot(source, name);
        if (bot == null)
        {
            return 0;
        }
        say(source, grey(bot.getName().getString() + ": ").append(yellow(bot.getPvpConfig().describe())));
        return 1;
    }

    public static int setOption(CommandSourceStack source, String name, String key, String value)
    {
        if (!BotCommands.canUse(source))
        {
            return 0;
        }
        EntityPlayerMPFake bot = bot(source, name);
        if (bot == null)
        {
            return 0;
        }
        String error = bot.getPvpConfig().apply(key, value);
        if (error != null)
        {
            say(source, red(error));
            return 0;
        }
        say(source, grey(bot.getName().getString() + ": ").append(yellow(key)).append(grey(" = ")).append(yellow(value)));
        return 1;
    }

    /** Makes two bots target each other by putting them in different factions and turning them on. */
    public static int duel(CommandSourceStack source, String first, String second)
    {
        if (!BotCommands.canUse(source))
        {
            return 0;
        }
        EntityPlayerMPFake a = bot(source, first);
        EntityPlayerMPFake b = bot(source, second);
        if (a == null || b == null)
        {
            return 0;
        }
        if (a.getUUID().equals(b.getUUID()))
        {
            say(source, red("A bot cannot duel itself"));
            return 0;
        }
        String mine = "bot_" + a.getName().getString();
        String theirs = "bot_" + b.getName().getString();
        for (EntityPlayerMPFake duellist : new EntityPlayerMPFake[] {a, b})
        {
            BotPvpConfig cfg = duellist.getPvpConfig();
            cfg.combat = true;
            cfg.autoTarget = true;
            cfg.targetBots = true;
            cfg.targetRange = Math.max(cfg.targetRange, 32.0);
            cfg.faction = duellist == a ? mine : theirs;
            FactionManager.create(cfg.faction);
            FactionManager.join(cfg.faction, duellist.getUUID());
        }
        say(source, grey(a.getName().getString()).append(grey(" and ")).append(grey(b.getName().getString())).append(grey(" are duelling")));
        return 2;
    }

    public static int stop(CommandSourceStack source, String name)
    {
        if (!BotCommands.canUse(source))
        {
            return 0;
        }
        EntityPlayerMPFake bot = bot(source, name);
        if (bot == null)
        {
            return 0;
        }
        bot.getPvpConfig().combat = false;
        bot.getActionPack().stopNavigation();
        say(source, grey(bot.getName().getString()).append(grey(" stopped fighting")));
        return 1;
    }

    public static int stats(CommandSourceStack source, String name)
    {
        if (!BotCommands.canUse(source))
        {
            return 0;
        }
        EntityPlayerMPFake bot = bot(source, name);
        if (bot == null)
        {
            return 0;
        }
        BotBody body = bot.getBotBrain().body();
        if (body == null)
        {
            say(source, grey(bot.getName().getString()).append(grey(" has not fought yet")));
            return 1;
        }
        say(source, grey(bot.getName().getString() + ": ").append(yellow(body.stats().describe())));
        return 1;
    }

    private static EntityPlayerMPFake bot(CommandSourceStack source, String name)
    {
        ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
        if (player instanceof EntityPlayerMPFake bot)
        {
            return bot;
        }
        say(source, red(name + " is not a bot of this server"));
        return null;
    }

    /** The difficulty a {@code /bot spawn} leaves out takes from the rules. */
    public static String defaultDifficulty()
    {
        return BotSettings.botDifficulty;
    }
}
