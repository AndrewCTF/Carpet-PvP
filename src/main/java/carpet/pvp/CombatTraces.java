package carpet.pvp;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The fight trace of every bot on the server: the fight a bot is in, and the one before it.
 *
 * <p>Events are recorded where {@link BotStats} counts them, so a trace and the bot's own counters
 * always cover the same clicks and the same health. A fight ends when a bot has not recorded
 * anything for {@link #IDLE_TICKS} ticks, or when the bot leaves; the finished fight becomes the
 * last one and is written as JSON under the world folder.</p>
 */
public final class CombatTraces
{
    /** Folder inside the world the finished traces are written to. */
    public static final String FOLDER = "carpet-traces";
    /** Ticks without an event after which a bot counts as out of the fight. */
    public static final int IDLE_TICKS = 100;

    private static final Map<String, CombatTrace> fights = new LinkedHashMap<>();
    private static final Map<String, CombatTrace> finished = new LinkedHashMap<>();
    /** Bots whose finished trace could not be written, with the reason, so the file system is not asked again. */
    private static final Map<String, String> problems = new LinkedHashMap<>();

    private CombatTraces()
    {
    }

    /** The fight a bot is in, or null while it is not in one. */
    public static CombatTrace fight(String bot)
    {
        CombatTrace trace = fights.get(bot);
        return trace == null || trace.size() == 0 ? null : trace;
    }

    /** The fight a bot finished last, or null when it has not finished one. */
    public static CombatTrace last(String bot)
    {
        return finished.get(bot);
    }

    /** Every bot that has a last fight, for the command to list. */
    public static List<String> names()
    {
        return List.copyOf(finished.keySet());
    }

    /** The short form of the fight a bot is in, falling back to the one it finished last. */
    public static String describe(String bot)
    {
        CombatTrace trace = fight(bot);
        if (trace == null)
        {
            trace = finished.get(bot);
        }
        return trace == null ? "no fight" : trace.describe();
    }

    /** The folder the finished traces live in, inside the world. */
    public static Path folder(MinecraftServer server)
    {
        return server.getWorldPath(LevelResource.ROOT).resolve(FOLDER);
    }

    /** A landed hit. Called from the body next to the counter of hits and dealt damage. */
    public static void hit(EntityPlayerMPFake bot, LivingEntity target, double damage, boolean crit)
    {
        add(bot, new CombatTrace.Event(tick(bot), CombatTrace.Kind.HIT, name(target),
                item(bot.getWeaponItem()), damage, crit));
    }

    /** A click that hit nothing. Called from the body next to the counter of misses. */
    public static void miss(EntityPlayerMPFake bot, LivingEntity target)
    {
        add(bot, new CombatTrace.Event(tick(bot), CombatTrace.Kind.MISS, name(target),
                item(bot.getWeaponItem()), 0.0, false));
    }

    /** Health the bot lost. Called from the body next to the counter of damage taken. */
    public static void took(EntityPlayerMPFake bot, double damage)
    {
        if (damage <= 0.0) return;
        add(bot, new CombatTrace.Event(tick(bot), CombatTrace.Kind.TAKEN,
                attackerName(bot), item(bot.getWeaponItem()), damage, false));
    }

    /** An item the bot put to use, which is what raised its shield. */
    public static void used(EntityPlayerMPFake bot, ItemStack item)
    {
        add(bot, new CombatTrace.Event(tick(bot), CombatTrace.Kind.ITEM, "", item(item), 0.0, false));
    }

    private static void add(EntityPlayerMPFake bot, CombatTrace.Event event)
    {
        String name = bot.getName().getString();
        CombatTrace trace = fights.get(name);
        if (trace == null)
        {
            trace = new CombatTrace(name, bot.getPvpConfig().combatStyle.name(), event.tick());
            fights.put(name, trace);
        }
        trace.add(event);
    }

    /**
     * Ends whatever fights are open and writes them all out, for the moment the server goes down and
     * nothing would tick to end them.
     *
     * @return how many fight traces were written
     */
    public static int flush(MinecraftServer server)
    {
        int written = 0;
        for (String name : List.copyOf(fights.keySet()))
        {
            if (write(server, end(name)))
            {
                written++;
            }
        }
        return written;
    }

    /**
     * Ends the fights that have gone quiet and writes them out. Called once per server tick; a bot
     * that has left simply stops recording and its fight ends like any other.
     *
     * @return how many fight traces were written
     */
    public static int endIdle(MinecraftServer server, long gameTime)
    {
        List<String> over = new ArrayList<>();
        for (Map.Entry<String, CombatTrace> entry : fights.entrySet())
        {
            if (gameTime - entry.getValue().lastTick() >= IDLE_TICKS)
            {
                over.add(entry.getKey());
            }
        }
        int written = 0;
        for (String name : over)
        {
            if (write(server, end(name)))
            {
                written++;
            }
        }
        return written;
    }

    /**
     * Ends whatever fight a bot is in and keeps it as its last one. Returns null when it was not in
     * a fight, so that nothing is written for a bot that only warmed up.
     */
    public static CombatTrace end(String bot)
    {
        CombatTrace trace = fight(bot);
        fights.remove(bot);
        if (trace != null)
        {
            finished.put(bot, trace);
        }
        return trace;
    }

    /** Writes a finished trace under the world folder, keeping the problems of a folder that cannot be written. */
    private static boolean write(MinecraftServer server, CombatTrace trace)
    {
        if (trace == null)
        {
            return false;
        }
        String known = problems.get(trace.bot());
        if (known != null)
        {
            return false;
        }
        Path file = folder(server).resolve(trace.bot() + ".json");
        try
        {
            trace.writeTo(file);
            return true;
        }
        catch (IOException | RuntimeException e)
        {
            problems.put(trace.bot(), e.getMessage() == null ? e.toString() : e.getMessage());
            return false;
        }
    }

    /** Forgets everything, for the tests and between self-test scenarios. */
    public static void clear()
    {
        fights.clear();
        finished.clear();
        problems.clear();
    }

    /** Why the last trace of this bot could not be written, or null when it could. */
    public static String problem(String bot)
    {
        return problems.get(bot);
    }

    private static long tick(EntityPlayerMPFake bot)
    {
        return bot.level() instanceof ServerLevel level ? level.getGameTime() : 0L;
    }

    private static String name(LivingEntity entity)
    {
        if (entity == null)
        {
            return "";
        }
        if (entity instanceof Player player)
        {
            return player.getName().getString();
        }
        return entity.getName().getString();
    }

    private static String attackerName(EntityPlayerMPFake bot)
    {
        UUID attacker = bot.lastAttackerUUID;
        if (attacker == null || !(bot.level() instanceof ServerLevel level))
        {
            return "";
        }
        Entity other = level.getEntity(attacker);
        return other instanceof LivingEntity living ? name(living) : "";
    }

    private static String item(ItemStack stack)
    {
        return stack == null || stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}