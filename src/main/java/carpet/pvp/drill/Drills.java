package carpet.pvp.drill;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import carpet.pvp.style.StyleIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The drills of the server: which ones there are, who is running one and how they are taken away.
 *
 * <p>A drill starts by asking the drill whether the player has what it needs, so one that cannot run
 * says why and stops there. Otherwise the player's own inventory is remembered first, the drill spawns
 * its bot, and the score goes to the action bar while the drill runs; when it is over the bot is taken
 * off, the blocks the drill laid are taken up and the inventory is handed back exactly as it was.</p>
 */
public final class Drills
{
    /** Ticks between the score lines sent to the action bar. */
    private static final int SCORE_EVERY = 4;
    private static final Map<String, Drill> DRILLS = new LinkedHashMap<>();
    private static final Map<UUID, DrillRun> runs = new LinkedHashMap<>();
    private static final Map<UUID, String> summaries = new LinkedHashMap<>();

    static
    {
        DRILLS.put("aim", new AimDrill());
        DRILLS.put("stunslam", new StunslamDrill());
        DRILLS.put("pearlcatch", new PearlCatchDrill());
        DRILLS.put("retotem", new ReTotemDrill());
        DRILLS.put("crystaltiming", new CrystalTimingDrill());
    }

    private Drills()
    {
    }

    /** The name of every drill {@code /bot drill} takes. */
    public static String[] names()
    {
        return DRILLS.keySet().toArray(new String[0]);
    }

    public static Drill of(String name)
    {
        return DRILLS.get(name.toLowerCase(Locale.ROOT));
    }

    /** What every drill is for, one line each. */
    public static String list()
    {
        StringBuilder out = new StringBuilder();
        for (Drill drill : DRILLS.values())
        {
            if (out.length() > 0) out.append(" | ");
            out.append(drill.name()).append(": ").append(drill.describe());
        }
        return out.toString();
    }

    public static boolean running(ServerPlayer player)
    {
        return runs.containsKey(player.getUUID());
    }

    /** How many drills are running, so a server with none skips the whole tick. */
    public static int runningCount()
    {
        return runs.size();
    }

    /** The name of the drill a player is running, or null. */
    public static String runningName(ServerPlayer player)
    {
        DrillRun run = runs.get(player.getUUID());
        return run == null ? null : run.drill().name();
    }

    /** The name of the bot the drill of this player is using, or null while there is no drill. */
    public static String runningBotName(ServerPlayer player)
    {
        DrillRun run = runs.get(player.getUUID());
        return run == null ? null : run.botName();
    }

    /** The score line of the drill this player is running, or null when there is none. */
    public static String score(ServerPlayer player)
    {
        DrillRun run = runs.get(player.getUUID());
        return run == null ? null : run.drill().score(run);
    }

    /** What the last drill of this player ended with, or null when they have not run one. */
    public static String lastSummary(ServerPlayer player)
    {
        return summaries.get(player.getUUID());
    }

    /**
     * Starts a drill for a player.
     *
     * @return null on success, otherwise the reason the drill could not run
     */
    public static String start(MinecraftServer server, ServerPlayer player, String name)
    {
        if (runs.containsKey(player.getUUID()))
        {
            stop(server, player);
        }
        Drill drill = of(name);
        if (drill == null)
        {
            return "there is no drill called " + name + "; the drills are " + String.join(", ", names());
        }
        String missing = drill.unavailable(server, player);
        if (missing != null)
        {
            return missing;
        }
        if (!KitInventory.save(player))
        {
            // Somebody handed this player a kit that was never taken back; taking one away now would
            // take their kit away rather than the drill's.
            return "you still have a kit on: /bot kit restore yourself first";
        }
        DrillRun run = new DrillRun(server, player, drill, botName(server, player, drill.name()));
        runs.put(player.getUUID(), run);
        try
        {
            drill.begin(run);
        }
        catch (RuntimeException e)
        {
            runs.remove(player.getUUID());
            clean(server, run);
            KitInventory.restore(player);
            return "the drill could not be set up: " + e;
        }
        return null;
    }

    /**
     * Ends the drill a player is running.
     *
     * @return null on success, otherwise the reason there was nothing to end
     */
    public static String stop(MinecraftServer server, ServerPlayer player)
    {
        DrillRun run = runs.get(player.getUUID());
        if (run == null)
        {
            return "you are not running a drill";
        }
        finish(server, run, run.drill().summary(run));
        return null;
    }

    /** One tick of every drill that is running. */
    public static void tick(MinecraftServer server)
    {
        if (runs.isEmpty()) return;
        for (UUID id : new ArrayList<>(runs.keySet()))
        {
            DrillRun run = runs.get(id);
            if (run == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null)
            {
                finish(server, run, run.drill().summary(run));
                continue;
            }
            run.count();
            run.drill().tick(run);
            if (run.ticks() % SCORE_EVERY == 0)
            {
                player.sendOverlayMessage(Component.literal(run.drill().name() + ": " + run.drill().score(run)));
            }
            if (run.over())
            {
                finish(server, run, run.drill().summary(run));
            }
        }
    }

    public static void clear()
    {
        runs.clear();
        summaries.clear();
    }

    private static void finish(MinecraftServer server, DrillRun run, String summary)
    {
        runs.remove(run.player().getUUID());
        clean(server, run);
        summaries.put(run.player().getUUID(), summary);
        run.player().sendSystemMessage(Component.literal("Drill " + run.drill().name() + " over: " + summary));
    }

    /** Takes the bot off, puts the blocks back and hands back what the drill handed out. */
    private static void clean(MinecraftServer server, DrillRun run)
    {
        DrillBot bot = run.bot();
        if (bot != null)
        {
            bot.stop();
        }
        if (server.getPlayerList().getPlayerByName(run.botName()) instanceof EntityPlayerMPFake spawned)
        {
            spawned.fakePlayerDisconnect(Component.literal("The drill is over"));
        }
        KitInventory.restore(run.player());
    }

    /** The drill bot of that name, wrapped so it can act, or null while it has not joined. */
    public static DrillBot botOf(MinecraftServer server, String name)
    {
        return server.getPlayerList().getPlayerByName(name) instanceof EntityPlayerMPFake bot
                ? new DrillBot(bot) : null;
    }

    /**
     * Spawns the bot of a drill in front of the player: the kit of the drill's style, combat off so it
     * fights nobody and its own name so the drill can find it again. On an online-mode server the bot
     * only exists once the profile of the name has come back, so a false here is not a failure.
     */
    public static boolean spawnBot(DrillRun run, Vec3 pos)
    {
        MinecraftServer server = run.server();
        ServerPlayer player = run.player();
        float yaw = (float) (Math.toDegrees(Math.atan2(player.getZ() - pos.z, player.getX() - pos.x)) - 90.0D);
        if (!EntityPlayerMPFake.createFake(run.botName(), server, server.createCommandSourceStack(), pos, yaw,
                0.0F, player.level().dimension(), GameType.SURVIVAL, false))
        {
            return false;
        }
        if (server.getPlayerList().getPlayerByName(run.botName()) instanceof EntityPlayerMPFake bot)
        {
            BotPvpConfig cfg = bot.getPvpConfig();
            cfg.combat = false;
            cfg.autoTarget = false;
            cfg.autoTotem = false;
            cfg.autoShield = false;
            cfg.combatStyle = run.style();
            Kit kit = kit(server, run.style());
            if (kit != null)
            {
                KitInventory.apply(bot, kit, server.registryAccess());
            }
            return true;
        }
        return false;
    }

    /** The built-in kit a style's bot is given, or null when the style has none. */
    public static Kit kit(MinecraftServer server, BotPvpConfig.CombatStyle style)
    {
        String name = StyleIndex.kit(style);
        return name == null ? null : KitStore.of(server).get(name).orElse(null);
    }

    /** A spot {@code distance} in front of a player, at the player's own height. */
    public static Vec3 inFront(ServerPlayer player, double distance)
    {
        double yaw = Math.toRadians(player.getYRot());
        return new Vec3(player.getX() - Math.sin(yaw) * distance, player.getY(),
                player.getZ() + Math.cos(yaw) * distance);
    }

    /**
     * Lays a square of obsidian on the ground under a drill bot, remembering where it put it.
     *
     * @return every block it laid, for {@link #putBack}
     */
    public static List<BlockPos> lay(ServerLevel level, Vec3i corner, int size, int surfaceY)
    {
        List<BlockPos> laid = new ArrayList<>();
        for (int x = corner.getX(); x < corner.getX() + size; x++)
        {
            for (int z = corner.getZ(); z < corner.getZ() + size; z++)
            {
                BlockPos pos = new BlockPos(x, surfaceY, z);
                if (level.getBlockState(pos).isAir()
                        && level.setBlockAndUpdate(pos, Blocks.OBSIDIAN.defaultBlockState()))
                {
                    laid.add(pos);
                }
            }
        }
        return laid;
    }

    /** Takes the blocks a drill laid away again. */
    public static void putBack(ServerLevel level, List<BlockPos> laid)
    {
        for (BlockPos pos : laid)
        {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }

    /** The name a drill bot is spawned under: the player and the drill, kept clear of the players. */
    private static String botName(MinecraftServer server, ServerPlayer player, String drill)
    {
        String wanted = "Drill_" + tag(player.getName().getString()) + "_" + tag(drill);
        for (int suffix = 0; suffix < 100; suffix++)
        {
            String name = suffix == 0 ? wanted : wanted + suffix;
            if (server.getPlayerList().getPlayerByName(name) == null
                    && !EntityPlayerMPFake.isSpawningPlayer(name))
            {
                return name;
            }
        }
        return wanted;
    }

    /** Only the characters a player name may hold. */
    private static String tag(String name)
    {
        String clean = name.replaceAll("[^A-Za-z0-9_]", "");
        return clean.isEmpty() ? "x" : clean;
    }
}