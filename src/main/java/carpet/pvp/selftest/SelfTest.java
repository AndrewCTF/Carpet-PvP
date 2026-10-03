package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTestReport.Result;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Headless self-test. Started with {@code -Dcarpet.selftest=<names|all>}, the server runs scripted fake-player
 * scenarios from its tick loop, writes {@code selftest-report.json} and exits with 0 (all passed) or 1.
 * Expects a flat world. Uses only Minecraft and JDK types, so it is not tied to one mod loader.
 */
public final class SelfTest
{
    private static final List<String> SCENARIOS = List.of("spawn", "spawn_exact_name", "spawn_gamemode", "nav_goto",
            "nav_follow", "chase_attack", "chase_crit", "shield_disable");

    private static final String REQUESTED = System.getProperty("carpet.selftest");
    private static final double SURFACE_Y = -60.0D;
    private static final double SPACING = 256.0D;
    /** How long the server gets to wind down before the watchdog looks at the threads that are left. */
    private static final long EXIT_GRACE_MILLIS = 5000L;

    private record Bot(String name, Vec3 pos, String gamemode)
    {
        Bot(String name, Vec3 pos) { this(name, pos, "survival"); }
    }

    private record Probe(boolean ok, String detail) {}

    /** The commands are issued once every bot has joined; the check is then polled every tick. */
    private record Scenario(int timeout, List<Bot> bots, List<String> commands, Function<MinecraftServer, Probe> check) {}

    private static final List<Result> results = new ArrayList<>();
    private static final ItemStack SHIELD = new ItemStack(Items.SHIELD);
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
                run(server, "player " + bot.name() + " spawn at " + coords(bot.pos()) + " facing 0 0 in minecraft:overworld in " + bot.gamemode());
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
            case "spawn_exact_name":
                // Mixed case: the name has to survive the spawn untouched.
                String mixed = "sElF" + index + "a";
                return new Scenario(200, List.of(new Bot(mixed, origin)), List.of(), server ->
                {
                    String actual = player(server, mixed).getGameProfile().name();
                    return new Probe(mixed.equals(actual), fmt("%s was spawned as %s", mixed, actual));
                });
            case "spawn_gamemode":
                return new Scenario(200, List.of(new Bot(a, origin, "creative")), List.of(), server ->
                {
                    GameType mode = player(server, a).gameMode.getGameModeForPlayer();
                    return new Probe(mode == GameType.CREATIVE, fmt("%s is in %s mode", a, mode));
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
            case "shield_disable":
                // The attacker stands in front of the blocker, as everyone spawns looking along +z.
                Vec3 front = origin.add(0.0D, 0.0D, 2.0D);
                return new Scenario(600, List.of(new Bot(a, origin), new Bot(b, front)),
                        List.of("player " + a + " equip shield minecraft:shield", "player " + a + " use continuous",
                                "player " + b + " equip mainhand minecraft:diamond_axe",
                                "player " + b + " turn back", "player " + b + " attack continuous"), server ->
                {
                    ServerPlayer blocker = player(server, a);
                    boolean cooling = blocker.getCooldowns().isOnCooldown(SHIELD);
                    return new Probe(cooling, fmt("%s is %s and its shield is %s", a,
                            blocker.isBlocking() ? "still blocking" : "not blocking",
                            cooling ? "on cooldown" : "not on cooldown"));
                });
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
        // Stop the server the way an operator would, then let the watchdog wait it out: a server that
        // leaves a thread behind would keep this JVM alive forever, so the run has to prove it can end.
        Thread serverThread = Thread.currentThread();
        int exitCode = passed ? 0 : 1;
        server.halt(false);
        Thread watchdog = new Thread(() -> watchExit(serverThread, exitCode), "selftest-watchdog");
        // Not a daemon thread: the JVM would end on its own the moment the server thread stops, taking
        // the result and the thread check with it. The watchdog has to outlive the server to end the run.
        watchdog.setDaemon(false);
        watchdog.start();
    }

    /**
     * Waits for the server thread to end, then reports the non-daemon threads that are still around.
     * The watchdog has to hold the JVM open to be able to do that, so it is the one thread it ignores.
     */
    private static void watchExit(Thread serverThread, int exitCode)
    {
        try
        {
            serverThread.join();
            Thread.sleep(EXIT_GRACE_MILLIS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            System.out.println("[selftest] interrupted while waiting for the server to stop: " + e);
            Runtime.getRuntime().halt(3);
        }
        List<Thread> lingering = lingeringThreads();
        for (Thread thread : lingering)
        {
            System.out.println("[selftest] the server left the non-daemon thread '" + thread.getName() + "' running");
            for (StackTraceElement element : thread.getStackTrace())
            {
                System.out.println("[selftest]    at " + element);
            }
        }
        Runtime.getRuntime().halt(lingering.isEmpty() ? exitCode : 3);
    }

    /** The threads that would keep the JVM alive once the server is gone, the watchdog itself aside. */
    private static List<Thread> lingeringThreads()
    {
        // main is the JVM's own wait for the last non-daemon thread, and it calls itself DestroyJavaVM there.
        Set<String> jvmThreads = Set.of("main", "DestroyJavaVM");
        List<Thread> lingering = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet())
        {
            if (!thread.isAlive() || thread.isDaemon() || thread == Thread.currentThread() || jvmThreads.contains(thread.getName())) continue;
            lingering.add(thread);
        }
        return lingering;
    }

    // The player list matches names ignoring case, so the profile is what tells the exact name apart.
    private static ServerPlayer player(MinecraftServer server, String name)
    {
        return server.getPlayerList().getPlayerByName(name);
    }

    private static void run(MinecraftServer server, String command)
    {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
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
