package carpet.pvp.selftest;

import carpet.logic.CarpetLogic;
import carpet.logic.program.BotAction;
import carpet.logic.program.BotProgram;
import carpet.logic.program.ProgramExecutor.ProgramInfo;
import carpet.pvp.selftest.SelfTestReport.Result;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Headless self-test. Started with {@code -Dcarpet.selftest=<names|all>}, the server runs scripted fake-player
 * scenarios from its tick loop, writes {@code selftest-report.json} and exits with 0 (all passed) or 1.
 * Expects a flat world. Uses only Minecraft and JDK types, so it is not tied to one mod loader.
 */
public final class SelfTest
{
    private static final List<String> SCENARIOS = List.of(
            "spawn", "nav_goto", "nav_come", "nav_patrol", "nav_stop", "nav_follow",
            "chase_attack", "chase_crit", "script_run", "fill_updates", "logic_program", "logic_forever_budget",
            "spawn_exact_name", "spawn_gamemode", "shield_disable");

    private static final String REQUESTED = System.getProperty("carpet.selftest");
    private static final double SURFACE_Y = -60.0D;
    private static final double SPACING = 256.0D;
    private static final Gson GSON = new Gson();
    /** How long the server thread gets to stop, and then how long its worker threads get to finish. */
    private static final long EXIT_WAIT_MILLIS = 120_000L;
    private static volatile MinecraftServer stoppingServer;

    private record Bot(String name, Vec3 pos, String gamemode)
    {
        Bot(String name, Vec3 pos) { this(name, pos, "survival"); }
    }

    private record Probe(boolean ok, String detail) {}

    /** Scenarios that drive the bot with commands only. */
    private static final Consumer<MinecraftServer> NOTHING = server -> {};

    /** The commands are issued once every bot has joined; the check is then polled every tick. */
    private record Scenario(int timeout, List<Bot> bots, List<String> commands, Consumer<MinecraftServer> start, Function<MinecraftServer, Probe> check)
    {
        Scenario(int timeout, List<Bot> bots, List<String> commands, Function<MinecraftServer, Probe> check)
        {
            this(timeout, bots, commands, server -> {}, check);
        }
    }

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
            current.start().accept(server);
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
                return new Scenario(200, List.of(new Bot(a, origin)), List.of(), NOTHING, server ->
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
                return new Scenario(600, List.of(new Bot(a, origin)), List.of("player " + a + " nav goto " + coords(goal)), NOTHING, server ->
                {
                    double left = player(server, a).position().distanceTo(goal);
                    // 1 block is the default arrival radius of nav goto
                    return new Probe(left <= 1.0D, fmt("%s is %.2f blocks from the goal", a, left));
                });
            case "nav_follow":
                Vec3 behind = origin.add(0.0D, 0.0D, -2.0D);
                return new Scenario(600, List.of(new Bot(a, behind), new Bot(b, origin)),
                        List.of("player " + a + " nav follow " + b, "player " + b + " move forward"), NOTHING, server ->
                {
                    ServerPlayer leader = player(server, b);
                    double walked = leader.position().distanceTo(origin);
                    double gap = player(server, a).distanceTo(leader);
                    return new Probe(walked >= 20.0D && gap <= 4.0D, fmt("%s walked %.1f blocks, %s is %.1f blocks behind", b, walked, a, gap));
                });
            case "nav_patrol":
                // The bot starts away from the first waypoint, so visiting both means it walked there.
                Vec3 first = origin;
                Vec3 second = origin.add(10.0D, 0.0D, 0.0D);
                boolean[] visited = {false, false};
                return new Scenario(600, List.of(new Bot(a, origin.add(0.0D, 0.0D, -6.0D))),
                        List.of("player " + a + " nav patrol " + coords(first) + " " + coords(second)), server ->
                {
                    ServerPlayer bot = player(server, a);
                    // Patrol loops, so the two visits happen at different ticks; remember them.
                    // The patrol arrival radius is 1.5 blocks.
                    if (bot.position().distanceTo(first) <= 2.0D) visited[0] = true;
                    if (bot.position().distanceTo(second) <= 2.0D) visited[1] = true;
                    return new Probe(visited[0] && visited[1],
                            fmt("%s visited the first waypoint: %s, the second: %s", a, visited[0], visited[1]));
                });
            case "nav_stop":
                // nav stop clears the navigation state but never the movement inputs, so the bot keeps
                // coasting at its last speed instead of halting. This pins that; it should be tightened
                // to "the bot stops moving" once stopNavigation() also stops movement.
                Vec3 far = origin.add(40.0D, 0.0D, 0.0D);
                boolean[] stopping = {false};
                double[] lastX = {origin.x};
                return new Scenario(600, List.of(new Bot(a, origin)),
                        List.of("player " + a + " nav goto " + coords(far)), server ->
                {
                    ServerPlayer bot = player(server, a);
                    double x = bot.getX();
                    if (!stopping[0])
                    {
                        if (Math.abs(x - origin.x) < 3.0D)
                        {
                            return new Probe(false, fmt("%s has not started walking yet", a));
                        }
                        run(server, "player " + a + " nav stop");
                        stopping[0] = true;
                        lastX[0] = x;
                        return new Probe(false, fmt("issued nav stop with %s 3 blocks along", a));
                    }
                    double since = Math.abs(x - lastX[0]);
                    return new Probe(since >= 0.15D, fmt("%s coasted %.2f blocks past nav stop", a, since));
                });
            case "nav_come":
                // nav come navigates to the command source's position, so the console is moved there.
                Vec3 here = origin.add(8.0D, 0.0D, 0.0D);
                boolean[] sent = {false};
                return new Scenario(600, List.of(new Bot(a, origin)), List.of(), server ->
                {
                    ServerPlayer bot = player(server, a);
                    if (!sent[0])
                    {
                        run(server, "player " + a + " nav come", here);
                        sent[0] = true;
                        return new Probe(false, fmt("%s was sent to the command source's position", a));
                    }
                    double left = bot.position().distanceTo(here);
                    return new Probe(left <= 1.5D, fmt("%s is %.2f blocks from the command source", a, left));
                });
            case "chase_attack":
            case "chase_crit":
                String mode = name.substring("chase_".length());
                Vec3 ahead = origin.add(0.0D, 0.0D, 6.0D);
                return new Scenario(600, List.of(new Bot(a, origin), new Bot(b, ahead)),
                        List.of("player " + a + " nav chase " + mode + " 2.5 0 " + b), NOTHING, server ->
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
            case "logic_program":
                return new Scenario(600, List.of(new Bot(a, origin)), List.of(), server ->
                {
                    startProgram(server, a, "[{type: MOVE, params: {direction: forward, ticks: 40}}, {type: STOP_MOVEMENT}]");
                }, server ->
                {
                    double walked = player(server, a).position().distanceTo(origin);
                    String status = status(a);
                    return new Probe(walked >= 3.0D && status.equals("COMPLETED"),
                            fmt("%s walked %.1f blocks, its program is %s", a, walked, status));
                });
            case "logic_forever_budget":
                return new Scenario(200, List.of(new Bot(a, origin)), List.of(), server ->
                {
                    startProgram(server, a, "[{type: FOREVER, children: [{type: SPRINT}]}]");
                }, server ->
                {
                    // Reaching this many ticks at all says the loop left the server ticking; the program itself
                    // has no end, so it must still be running.
                    String status = status(a);
                    return new Probe(ticks >= 40 && status.equals("RUNNING"),
                            fmt("after %d ticks the program is %s", ticks, status));
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

    // A bot program started through the Java API, the way the web editor's execute endpoint starts one, and
    // never as the console: a program started from a command has no player to run its commands as.
    private static void startProgram(MinecraftServer server, String botName, String actions)
    {
        CarpetLogic logic = CarpetLogic.INSTANCE;
        BotProgram program = new BotProgram("_selftest", "selftest", "");
        program.setActions(GSON.fromJson(actions, new TypeToken<List<BotAction>>() {}.getType()));
        logic.getSchema().validate(program.getActions());
        String refused = logic.getProgramExecutor().startProgram(botName, program, null);
        if (refused != null)
        {
            log(server, "could not start the program on " + botName + ": " + refused);
        }
    }

    private static String status(String botName)
    {
        ProgramInfo info = CarpetLogic.INSTANCE.getProgramExecutor().getPrograms().get(botName);
        return info == null ? "gone" : info.status();
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
        stoppingServer = server;
        // Servers are usually stopped with bots online, so the run ends that way too.
        run(server, "player SelfStop spawn at 0.5 -60 0.5 facing 0 0 in minecraft:overworld in survival");
        log(server, "stopping with " + server.getPlayerList().getPlayers().size() + " fake player(s) online");
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
            serverThread.join(EXIT_WAIT_MILLIS);
            // The game's own I/O workers may still be saving chunks; only a thread that never ends is a leak.
            long deadline = System.currentTimeMillis() + EXIT_WAIT_MILLIS;
            while (!serverThread.isAlive() && !lingeringThreads().isEmpty() && System.currentTimeMillis() < deadline)
            {
                Thread.sleep(250L);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            System.out.println("[selftest] interrupted while waiting for the server to stop: " + e);
            Runtime.getRuntime().halt(3);
        }
        List<Thread> lingering = lingeringThreads();
        if (serverThread.isAlive())
        {
            System.out.println("[selftest] the server thread did not stop");
            lingering.add(serverThread);
            // What a stuck shutdown is usually waiting on: players that were never removed, and chunk work.
            MinecraftServer stuck = stoppingServer;
            if (stuck != null)
            {
                System.out.println("[selftest]   players still listed: " + stuck.getPlayerList().getPlayers().stream().map(p -> p.getGameProfile().name()).toList());
                for (ServerLevel level : stuck.getAllLevels())
                {
                    System.out.println("[selftest]   " + level.dimension().identifier() + ": players=" + level.players().size()
                            + " chunkMapHasWork=" + level.getChunkSource().chunkMap.hasWork()
                            + " loadedChunks=" + level.getChunkSource().getLoadedChunksCount());
                    System.out.println("[selftest]   " + level.dimension().identifier() + ": players the chunk tracker still holds: " + trackedPlayers(level));
                }
            }
        }
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

    /** Players the distance manager still counts, read by reflection: a diagnostic for a stuck shutdown only. */
    private static String trackedPlayers(ServerLevel level)
    {
        try
        {
            Object chunkMap = level.getChunkSource().chunkMap;
            java.lang.reflect.Method getter = chunkMap.getClass().getDeclaredMethod("getDistanceManager");
            getter.setAccessible(true);
            Object distanceManager = getter.invoke(chunkMap);
            java.lang.reflect.Field field = net.minecraft.server.level.DistanceManager.class.getDeclaredField("playersPerChunk");
            field.setAccessible(true);
            java.util.Map<?, ?> perChunk = (java.util.Map<?, ?>) field.get(distanceManager);
            java.util.Set<String> found = new java.util.TreeSet<>();
            for (Object players : perChunk.values())
            {
                for (Object o : (java.util.Collection<?>) players)
                {
                    ServerPlayer p = (ServerPlayer) o;
                    found.add(p.getGameProfile().name() + (p.isRemoved() ? "(removed:" + p.getRemovalReason() + ")" : "(live)") + "@" + p.blockPosition().toShortString());
                }
            }
            return found.toString();
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return "unavailable (" + e + ")";
        }
    }

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

    /** Runs a command as if it came from the given position, which nav come navigates to. */
    private static void run(MinecraftServer server, String command, Vec3 sourcePos)
    {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPosition(sourcePos), command);
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
