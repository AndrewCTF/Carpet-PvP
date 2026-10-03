package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTestReport.Result;
import carpet.CarpetSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Headless self-test. Started with {@code -Dcarpet.selftest=<names|all>}, the server runs scripted fake-player
 * scenarios from its tick loop, writes {@code selftest-report.json} and exits with 0 (all passed) or 1.
 * Expects a flat world. Uses only Minecraft and JDK types, so it is not tied to one mod loader.
 */
public final class SelfTest
{
    private static final List<String> SCENARIOS = List.of("spawn", "nav_goto", "nav_follow", "chase_attack", "chase_crit", "script_run", "fill_updates", "sword_block");

    private static final String REQUESTED = System.getProperty("carpet.selftest");
    private static final double SURFACE_Y = -60.0D;
    private static final double SPACING = 256.0D;
    private static final float SWORD_BLOCK_HIT = 4.0F;

    private record Bot(String name, Vec3 pos) {}

    private record Probe(boolean ok, String detail) {}

    /** The commands are issued once every bot has joined; the check is then polled every tick. */
    private record Scenario(int timeout, List<Bot> bots, List<String> commands, Function<MinecraftServer, Probe> check) {}

    private static final List<Result> results = new ArrayList<>();
    private static List<String> names;
    private static Scenario current;
    private static boolean acting;
    private static boolean finished;
    private static int ticks;

    private SelfTest() {}

    public static void tick(MinecraftServer server)
    {
        if (REQUESTED == null || finished) return;
        if (names == null)
        {
            names = SelfTestReport.parseNames(REQUESTED, SCENARIOS);
            run(server, "carpet fakePlayerNavigation true");
            // Sprinting only removes the wait between ticks; the scenarios take the same ticks either way.
            run(server, "tick sprint 1d");
        }
        if (current == null)
        {
            if (results.size() == names.size())
            {
                finish(server);
                return;
            }
            acting = false;
            ticks = 0;
            current = scenario(names.get(results.size()), results.size());
            if (current == null)
            {
                conclude(server, false, "unknown scenario");
                return;
            }
            for (Bot bot : current.bots())
            {
                run(server, "player " + bot.name() + " spawn at " + coords(bot.pos()) + " facing 0 0 in minecraft:overworld in survival");
            }
            return;
        }
        ticks++;
        Probe probe = probe(server);
        if (probe.ok() || ticks >= current.timeout())
        {
            for (Bot bot : current.bots())
            {
                if (player(server, bot.name()) != null) run(server, "player " + bot.name() + " disconnect");
            }
            current = null;
            conclude(server, probe.ok(), probe.detail());
        }
    }

    private static Probe probe(MinecraftServer server)
    {
        for (Bot bot : current.bots())
        {
            if (player(server, bot.name()) == null) return new Probe(false, bot.name() + " is not in the player list");
        }
        if (!acting)
        {
            acting = true;
            current.commands().forEach(command -> run(server, command));
        }
        return current.check().apply(server);
    }

    private static Scenario scenario(String name, int index)
    {
        // a is the bot under test, b the second player it follows or fights. Everyone spawns looking along +z.
        String a = "SelfA" + index;
        String b = "SelfB" + index;
        Vec3 origin = new Vec3(SPACING * (index + 1) + 0.5D, SURFACE_Y, 0.5D);
        switch (name)
        {
            case "spawn":
                return new Scenario(200, List.of(new Bot(a, origin)), List.of(), server ->
                {
                    double off = player(server, a).position().distanceTo(origin);
                    return new Probe(off < 0.5D, fmt("%s is %.2f blocks from where it was spawned", a, off));
                });
            case "nav_goto":
                Vec3 goal = origin.add(12.0D, 0.0D, 0.0D);
                return new Scenario(600, List.of(new Bot(a, origin)), List.of("player " + a + " nav goto " + coords(goal)), server ->
                {
                    double left = player(server, a).position().distanceTo(goal);
                    // 1 block is the default arrival radius of nav goto
                    return new Probe(left <= 1.0D, fmt("%s is %.2f blocks from the goal", a, left));
                });
            case "nav_follow":
                Vec3 behind = origin.add(0.0D, 0.0D, -2.0D);
                return new Scenario(600, List.of(new Bot(a, behind), new Bot(b, origin)),
                        List.of("player " + a + " nav follow " + b, "player " + b + " move forward"), server ->
                {
                    ServerPlayer leader = player(server, b);
                    double walked = leader.position().distanceTo(origin);
                    double gap = player(server, a).distanceTo(leader);
                    return new Probe(walked >= 20.0D && gap <= 4.0D, fmt("%s walked %.1f blocks, %s is %.1f blocks behind", b, walked, a, gap));
                });
            case "chase_attack":
            case "chase_crit":
                String mode = name.substring("chase_".length());
                Vec3 ahead = origin.add(0.0D, 0.0D, 6.0D);
                return new Scenario(600, List.of(new Bot(a, origin), new Bot(b, ahead)),
                        List.of("player " + a + " nav chase " + mode + " 2.5 0 " + b), server ->
                {
                    float health = player(server, b).getHealth();
                    return new Probe(health < 20.0F, fmt("%s has %.1f health", b, health));
                });
            case "script_run":
                int[] computed = {-1};
                return new Scenario(100, List.of(), List.of(), server ->
                {
                    if (computed[0] < 0) computed[0] = result(server, "script run 1+1");
                    return new Probe(computed[0] > 0, fmt("script run 1+1 returned %d", computed[0]));
                });
            case "fill_updates":
                // A lamp turns on as soon as a neighbour update reaches it, so a redstone block placed next to it
                // lights it while the rule is on and leaves it dark while the rule is off.
                BlockPos quietLamp = BlockPos.containing(origin);
                BlockPos quietPower = quietLamp.east();
                BlockPos liveLamp = quietLamp.east(4);
                BlockPos livePower = liveLamp.east();
                return new Scenario(100, List.of(), List.of(
                        "forceload add " + quietLamp.getX() + " " + quietLamp.getZ(),
                        "carpet fillUpdates false",
                        setBlock(quietLamp, "minecraft:redstone_lamp"),
                        setBlock(quietPower, "minecraft:redstone_block"),
                        setBlock(liveLamp, "minecraft:redstone_lamp"),
                        "carpet fillUpdates true",
                        setBlock(livePower, "minecraft:redstone_block")), server ->
                {
                    boolean quietLit = lit(server, quietLamp);
                    boolean liveLit = lit(server, liveLamp);
                    // a few ticks of slack, in case an update ever arrives late
                    boolean settled = ticks > 5;
                    return new Probe(settled && !quietLit && liveLit, fmt(
                            "after %d ticks the lamp placed with the rule off is %s and the one placed with it on is %s",
                            ticks, quietLit ? "lit" : "dark", liveLit ? "lit" : "dark"));
                });
            case "sword_block":
            {
                // One player holds its sword up, the other one stands idle, both take the same fixed hit. With the rule
                // on the blocking one must lose swordBlockDamageMultiplier of it, with the rule off it must lose all of it.
                int[] phase = {0};
                float[] guarding = new float[2];
                float[] open = new float[2];
                return new Scenario(300, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, -2.0D))),
                        List.of("carpet swordBlockHitting true",
                                "player " + a + " equip mainhand minecraft:diamond_sword",
                                "player " + a + " use continuous"), server ->
                {
                    ServerPlayer blocker = player(server, a);
                    ServerPlayer idle = player(server, b);
                    if (!blocker.isUsingItem()) return pending(a + " is not holding the sword up yet");
                    if (!hittable(blocker, idle)) return pending(hitWaitReason(blocker, idle));
                    if (phase[0] == 0)
                    {
                        guarding[0] = damage(server, blocker);
                        open[0] = damage(server, idle);
                        run(server, "carpet swordBlockHitting false");
                        phase[0] = 1;
                        return pending(fmt("with the rule on %s lost %.1f health while blocking and %s lost %.1f",
                                a, guarding[0], b, open[0]));
                    }
                    guarding[1] = damage(server, blocker);
                    open[1] = damage(server, idle);
                    // both idle players have to take the whole hit, otherwise the numbers below mean nothing
                    boolean hitsLanded = same(open[0], SWORD_BLOCK_HIT) && same(open[1], SWORD_BLOCK_HIT);
                    float factor = (float) CarpetSettings.swordBlockDamageMultiplier;
                    boolean halved = same(guarding[0], SWORD_BLOCK_HIT * factor);
                    boolean wholeAgain = same(guarding[1], SWORD_BLOCK_HIT);
                    return new Probe(hitsLanded && halved && wholeAgain, fmt(
                            "with the rule on %s lost %.1f of the %.1f health a hit takes, with the rule off %.1f",
                            a, guarding[0], SWORD_BLOCK_HIT, guarding[1]));
                });
            }
            default:
                return null;
        }
    }

    private static void conclude(MinecraftServer server, boolean passed, String detail)
    {
        Result result = new Result(names.get(results.size()), passed, ticks, detail);
        results.add(result);
        log(server, fmt("%s %s after %d ticks: %s", passed ? "PASS" : "FAIL", result.name(), result.ticks(), detail));
    }

    private static void finish(MinecraftServer server)
    {
        finished = true;
        boolean passed = SelfTestReport.allPassed(results);
        try
        {
            Files.writeString(Path.of("selftest-report.json"), SelfTestReport.toJson(server.getServerVersion(), results));
        }
        catch (IOException e)
        {
            log(server, "could not write selftest-report.json: " + e);
            passed = false;
        }
        log(server, fmt("%s, %d of %d scenarios passed", passed ? "PASSED" : "FAILED", results.stream().filter(Result::passed).count(), results.size()));
        int exitCode = passed ? 0 : 1;
        // Not from this thread: the server's shutdown hook waits for the server thread to stop ticking.
        new Thread(() -> System.exit(exitCode), "selftest-exit").start();
    }

    // The player list matches names ignoring case; a profile lookup may have changed the capitalisation.
    private static ServerPlayer player(MinecraftServer server, String name)
    {
        return server.getPlayerList().getPlayerByName(name);
    }

    private static void run(MinecraftServer server, String command)
    {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** Issues a command and reads back its result, the way the console reports success. */
    private static int result(MinecraftServer server, String command)
    {
        try
        {
            return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
        }
        catch (Exception e)
        {
            log(server, command + " threw " + e);
            return 0;
        }
    }

    private static String setBlock(BlockPos pos, String block)
    {
        return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block;
    }

    private static boolean lit(MinecraftServer server, BlockPos pos)
    {
        return server.overworld().getBlockState(pos).getValue(RedstoneLampBlock.LIT);
    }

    private static Probe pending(String detail)
    {
        return new Probe(false, detail);
    }

    /** A hit counts in full only when the player is loaded, off the damage cooldown of the last one and healthy. */
    private static boolean hittable(ServerPlayer... players)
    {
        for (ServerPlayer player : players)
        {
            // a fake player cannot be hurt while its connection is still counted as loading
            if (!player.connection.hasClientLoaded()) return false;
            if (player.hurtTime > 0 || player.getHealth() <= SWORD_BLOCK_HIT) return false;
        }
        return true;
    }

    private static String hitWaitReason(ServerPlayer... players)
    {
        for (ServerPlayer player : players)
        {
            if (!player.connection.hasClientLoaded()) return player.getName().getString() + " is still loading";
            if (player.getHealth() <= SWORD_BLOCK_HIT) return player.getName().getString() + " has too little health left";
        }
        return "the last hit is still on cooldown";
    }

    /** Hits a player for a fixed amount and returns what it cost them in health. */
    private static float damage(MinecraftServer server, ServerPlayer victim)
    {
        float before = victim.getHealth();
        result(server, "damage " + victim.getName().getString() + " " + SWORD_BLOCK_HIT);
        return before - victim.getHealth();
    }

    private static boolean same(float one, float other)
    {
        return Math.abs(one - other) < 0.05F;
    }

    private static void log(MinecraftServer server, String message)
    {
        server.sendSystemMessage(Component.literal("[selftest] " + message));
    }

    private static String coords(Vec3 pos)
    {
        return pos.x + " " + pos.y + " " + pos.z;
    }

    private static String fmt(String format, Object... args)
    {
        return String.format(Locale.ROOT, format, args);
    }
}
