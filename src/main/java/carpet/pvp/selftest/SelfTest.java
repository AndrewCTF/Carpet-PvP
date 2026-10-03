package carpet.pvp.selftest;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.logic.CarpetLogic;
import carpet.logic.program.BotAction;
import carpet.logic.program.BotProgram;
import carpet.logic.program.ProgramExecutor.ProgramInfo;
import carpet.pvp.nav.NavSearchBudget;
import carpet.pvp.selftest.SelfTestReport.Result;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
            "spawn", "nav_goto", "nav_come", "nav_patrol", "nav_stop", "nav_follow", "chase_attack", "chase_crit",
            "nav_maze", "nav_parkour", "nav_ladder", "nav_partial_blocks", "nav_moving_target", "nav_crowd",
            "nav_tick_budget", "nav_smooth",
            "script_run", "fill_updates", "logic_program", "logic_forever_budget");

    private static final String REQUESTED = System.getProperty("carpet.selftest");
    private static final double SURFACE_Y = -60.0D;
    private static final double SPACING = 256.0D;
    private static final Gson GSON = new Gson();

    private record Bot(String name, Vec3 pos) {}

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
            case "nav_maze":
            {
                // Six cross walls whose gaps alternate between the two ends of the maze, so the way through is a
                // single serpentine with no shortcuts.
                int x0 = (int) origin.x + 2;
                int z0 = (int) origin.z + 3;
                List<String> course = List.of(
                        fill(x0 - 1, -60, z0 - 1, x0 + 13, -59, z0 - 1, "minecraft:stone"),
                        fill(x0 - 1, -60, z0 + 14, x0 + 13, -59, z0 + 14, "minecraft:stone"),
                        fill(x0 - 1, -60, z0 - 1, x0 - 1, -59, z0 + 14, "minecraft:stone"),
                        fill(x0 + 13, -60, z0 - 1, x0 + 13, -59, z0 + 14, "minecraft:stone"));
                course = new ArrayList<>(course);
                course.add(fill(x0, -60, z0 - 1, x0 + 1, -59, z0 - 1, "minecraft:air"));
                for (int row = 0; row < 6; row++)
                {
                    int z = z0 + 1 + row * 2;
                    course.add(row % 2 == 0
                            ? fill(x0 + 2, -60, z, x0 + 12, -59, z, "minecraft:stone")
                            : fill(x0, -60, z, x0 + 10, -59, z, "minecraft:stone"));
                }
                Vec3 start = new Vec3(x0 + 0.5D, SURFACE_Y, z0 - 3.5D);
                Vec3 exit = new Vec3(x0 + 11.5D, SURFACE_Y, z0 + 12.5D);
                course.add("player " + a + " nav goto " + coords(exit));
                return new Scenario(900, List.of(new Bot(a, start)), course, server ->
                {
                    double left = player(server, a).position().distanceTo(exit);
                    return new Probe(left <= 1.5D, fmt("%s is %.2f blocks from the exit of the maze", a, left));
                });
            }
            case "nav_parkour":
                {
                // A walkway with a two block gap in it and then a three block one, both dug two deep, so the only
                // way on is a sprint jump and the second one needs the run up.
                int x0 = (int) origin.x;
                int z0 = (int) origin.z;
                List<String> course = List.of(
                        forceload(x0, z0, x0 + 46, z0),
                        fill(x0 + 20, -62, z0 - 118, x0 + 21, -61, z0 + 118, "minecraft:air"),
                        fill(x0 + 34, -62, z0 - 118, x0 + 36, -61, z0 + 118, "minecraft:air"));
                Vec3 start = new Vec3(x0 + 0.5D, SURFACE_Y, z0 + 0.5D);
                Vec3 parkourGoal = new Vec3(x0 + 46.0D, SURFACE_Y, z0 + 0.5D);
                List<String> commands = new ArrayList<>(course);
                commands.add("player " + a + " nav goto " + coords(parkourGoal));
                return new Scenario(900, List.of(new Bot(a, start)), commands, server ->
                {
                    double left = player(server, a).position().distanceTo(parkourGoal);
                    return new Probe(left <= 1.5D,
                            fmt("%s is %.2f blocks past the three block gap", a, left));
                });
            }
            case "nav_ladder":
            {
                // A tower with a ladder fixed to its west face and a vine down its east face: the ladder is the
                // only way up and the vine the only way down.
                int tx = (int) origin.x;
                int tz = (int) origin.z;
                List<String> course = List.of(
                        fill(tx, -62, tz, tx + 4, -51, tz + 4, "minecraft:stone"),
                        fill(tx - 1, -60, tz + 2, tx - 1, -51, tz + 2, "minecraft:ladder[facing=west]"),
                        fill(tx + 6, -61, tz + 2, tx + 6, -49, tz + 2, "minecraft:stone"),
                        fill(tx + 5, -60, tz + 2, tx + 5, -50, tz + 2, "minecraft:vine[east=true]"));
                Vec3 start = new Vec3(tx - 6.0D, SURFACE_Y, tz + 2.5D);
                Vec3 top = new Vec3(tx + 2.0D, -50.0D, tz + 2.5D);
                Vec3 down = new Vec3(tx + 9.0D, SURFACE_Y, tz + 2.5D);
                int[] phase = {0};
                double[] highest = {SURFACE_Y};
                return new Scenario(1200, List.of(new Bot(a, start)), course,
                        server -> run(server, "player " + a + " nav goto " + coords(top)), server ->
                {
                    ServerPlayer bot = player(server, a);
                    highest[0] = Math.max(highest[0], bot.getY());
                    if (phase[0] == 0)
                    {
                        if (bot.position().distanceTo(top) <= 1.5D)
                        {
                            phase[0] = 1;
                            run(server, "player " + a + " nav goto " + coords(down));
                            return new Probe(false, fmt("%s climbed the ladder to y=%.2f and was sent back down", a, bot.getY()));
                        }
                        return new Probe(false, fmt("%s is still on the ladder at y=%.2f", a, bot.getY()));
                    }
                    boolean left = bot.position().distanceTo(down) <= 1.5D;
                    return new Probe(left && highest[0] >= -50.5D, fmt(
                            "%s is %.2f blocks from the ground again, highest point on the tower y=%.2f",
                            a, bot.position().distanceTo(down), highest[0]));
                });
            }
            case "nav_partial_blocks":
                {
                // A corridor whose floor is a slab, a slab with a carpet on it, a slab under a snow layer, a stone
                // block with a stair on top and a bare stair, then a grass path and blocksGoalmland back at ground level.
                int x0 = (int) origin.x;
                int z0 = (int) origin.z;
                // The walls are three high so the only way along the corridor is along it: a bot that could get over
                // one would walk out of the course instead of over the blocks it is meant to be testing.
                List<String> course = List.of(
                        fill(x0 - 4, -60, z0 - 1, x0 + 10, -58, z0 - 1, "minecraft:stone"),
                        fill(x0 - 4, -60, z0 + 1, x0 + 10, -58, z0 + 1, "minecraft:stone"),
                        setBlock(x0, -60, z0, "minecraft:oak_slab[type=bottom]"),
                        setBlock(x0 + 1, -60, z0, "minecraft:oak_slab[type=bottom]"),
                        setBlock(x0 + 1, -59, z0, "minecraft:white_carpet"),
                        setBlock(x0 + 2, -60, z0, "minecraft:oak_slab[type=bottom]"),
                        setBlock(x0 + 2, -59, z0, "minecraft:snow[layers=1]"),
                        setBlock(x0 + 3, -60, z0, "minecraft:cobblestone"),
                        setBlock(x0 + 3, -59, z0, "minecraft:oak_stairs[facing=east,half=bottom]"),
                        setBlock(x0 + 4, -60, z0, "minecraft:oak_stairs[facing=east,half=bottom]"),
                        setBlock(x0 + 6, -61, z0, "minecraft:grass_path"),
                        setBlock(x0 + 7, -61, z0, "minecraft:blocksGoalmland"));
                Vec3 start = new Vec3(x0 - 3.0D, SURFACE_Y, z0 + 0.5D);
                Vec3 blocksGoal = new Vec3(x0 + 9.0D, SURFACE_Y, z0 + 0.5D);
                List<String> commands = new ArrayList<>(course);
                commands.add("player " + a + " nav goto " + coords(blocksGoal));
                double[] highest = {SURFACE_Y};
                return new Scenario(900, List.of(new Bot(a, start)), commands, server ->
                {
                    ServerPlayer bot = player(server, a);
                    highest[0] = Math.max(highest[0], bot.getY());
                    double left = bot.position().distanceTo(blocksGoal);
                    return new Probe(left <= 1.5D && highest[0] >= -58.5D, fmt(
                            "%s is %.2f blocks from the far end of the corridor, highest step was y=%.2f",
                            a, left, highest[0]));
                });
            }
            case "nav_moving_target":
            {
                // The target walks north and then west, which takes it round the north end of a wall the chaser
                // starts out behind: at the turn the straight line at the target is blocked and the chaser has to
                // find its way round rather than lose the target.
                int sx = (int) origin.x;
                int sz = (int) origin.z;
                int wallX = sx + 4;
                int turnZ = sz + 10;
                List<String> course = List.of(fill(wallX, -60, sz - 6, wallX, -59, sz + 6, "minecraft:stone"));
                List<String> commands = List.of(
                        "effect give " + b + " minecraft:resistance 1 4 true",
                        "player " + b + " move forward for 50",
                        "player " + a + " nav chase attack 3.0 0 " + b);
                boolean[] turned = {false};
                double[] worst = {0.0D};
                List<String> all = new ArrayList<>(course);
                all.addAll(commands);
                return new Scenario(900, List.of(new Bot(a, new Vec3(sx + 8.5D, SURFACE_Y, sz - 8.5D)),
                        new Bot(b, new Vec3(sx + 8.5D, SURFACE_Y, sz + 0.5D))), all, server ->
                {
                    ServerPlayer target = player(server, b);
                    if (!turned[0] && target.getZ() >= turnZ - 0.5D)
                    {
                        turned[0] = true;
                        run(server, "player " + b + " move right for 55");
                    }
                    double gap = player(server, a).distanceTo(target);
                    worst[0] = Math.max(worst[0], gap);
                    double walked = target.position().distanceTo(new Vec3(sx + 8.5D, SURFACE_Y, sz + 0.5D));
                    boolean there = turned[0] && walked >= 18.0D;
                    return new Probe(there && gap <= 5.0D && worst[0] <= 20.0D, fmt(
                            "%s walked %.1f blocks round the wall and %s closed to %.1f, worst gap %.1f",
                            b, walked, a, gap, worst[0]));
                });
            }
            case "nav_crowd":
                {
                // Ten bots after one target, with a wall and a single gate between them: they share one flow field
                // rather than each searching, and the tick's search budget holds however many of them there are.
                int sx = (int) origin.x;
                int sz = (int) origin.z;
                int wallZ = sz - 10;
                List<String> course = List.of(
                        fill(sx - 12, -60, wallZ, sx + 4, -59, wallZ, "minecraft:stone"),
                        fill(sx + 7, -60, wallZ, sx + 12, -59, wallZ, "minecraft:stone"));
                List<Bot> bots = new ArrayList<>();
                List<String> commands = new ArrayList<>();
                commands.add("effect give " + b + " minecraft:resistance 1 4 true");
                commands.add("player " + b + " move forward for 400");
                for (int i = 0; i < 10; i++)
                {
                    String chaser = a + "c" + i;
                    bots.add(new Bot(chaser, new Vec3(sx + (i % 5 - 2) * 2.0D, SURFACE_Y, wallZ - 8.0D - (i / 5) * 2.0D)));
                    commands.add("player " + chaser + " nav chase attack 3.0 0 " + b);
                }
                bots.add(new Bot(b, new Vec3(sx + 0.5D, SURFACE_Y, sz + 0.5D)));
                List<String> all = new ArrayList<>(course);
                all.addAll(commands);
                int[] peak = {0};
                boolean[] shared = {false};
                return new Scenario(1200, bots, all, server ->
                {
                    peak[0] = Math.max(peak[0], NavSearchBudget.expansionsLastTick());
                    for (int i = 0; i < 10; i++)
                    {
                        if (pack(server, a + "c" + i).isNavFollowingFlowField()) shared[0] = true;
                    }
                    ServerPlayer target = player(server, b);
                    double closest = Double.MAX_VALUE;
                    double farthest = 0.0D;
                    for (int i = 0; i < 10; i++)
                    {
                        double gap = player(server, a + "c" + i).distanceTo(target);
                        closest = Math.min(closest, gap);
                        farthest = Math.max(farthest, gap);
                    }
                    return new Probe(farthest <= 5.0D && peak[0] <= NavSearchBudget.sharedCap() && shared[0], fmt(
                            "of the ten, closest %.1f and farthest %.1f blocks away, busiest tick spent %d of %d expansions, shared field used: %s",
                            closest, farthest, peak[0], NavSearchBudget.sharedCap(), shared[0]));
                });
            }
            case "nav_tick_budget":
            {
                // A goal 120 blocks off across a field of walls, so the search cannot be finished in one tick and
                // has to be spread over several while the bot stays inside the per-bot expansion cap.
                int x0 = (int) origin.x;
                int z0 = (int) origin.z;
                // A wall across the route with its only gap a dozen blocks off the straight line, so the search
                // cannot be shortcut and has to be spread over more than one tick to find the way round.
                List<String> course = List.of(
                        forceload(x0, z0 - 20, x0 + 130, z0 + 18),
                        fill(x0 + 60, -60, z0 - 20, x0 + 60, -59, z0 + 11, "minecraft:stone"),
                        fill(x0 + 60, -60, z0 + 18, x0 + 60, -59, z0 + 24, "minecraft:stone"));
                Vec3 budgetGoal = new Vec3(x0 + 125.0D, SURFACE_Y, z0 + 0.5D);
                int[] searchTicks = {0};
                int[] running = {0};
                int[] longestRun = {0};
                int[] peak = {0};
                double[] started = {0.0D};
                return new Scenario(1200, List.of(new Bot(a, new Vec3(x0 + 0.5D, SURFACE_Y, z0 + 0.5D))), course,
                        server -> {
                            NavSearchBudget.reset();
                            run(server, "player " + a + " nav goto " + coords(budgetGoal));
                        }, server ->
                {
                    ServerPlayer bot = player(server, a);
                    int spent = NavSearchBudget.maxBotExpansionsLastTick();
                    // A tick the bot spent any of its search budget on is a tick the search was spread over.
                    if (spent > 0)
                    {
                        searchTicks[0]++;
                    }
                    running[0] = pack(server, a).isNavSearching() ? running[0] + 1 : 0;
                    longestRun[0] = Math.max(longestRun[0], running[0]);
                    if (spent == 0 && started[0] == 0.0D && bot.getX() > x0 + 5)
                    {
                        started[0] = bot.getX() - x0;
                    }
                    peak[0] = Math.max(peak[0], spent);
                    double left = bot.position().distanceTo(budgetGoal);
                    return new Probe(left <= 2.0D && longestRun[0] >= 2 && peak[0] <= NavSearchBudget.perBotCap()
                                    && NavSearchBudget.peakExpansions() <= NavSearchBudget.sharedCap(),
                            fmt("%s is %.1f blocks short, search budget spent on %d ticks, longest search %d ticks,"
                                            + " busiest tick %d of %d",
                                    a, left, searchTicks[0], longestRun[0], peak[0], NavSearchBudget.perBotCap()));
                });
            }
            case "nav_smooth":
                {
                // A diagonal goal across open ground: the bot has to walk it in a straight line rather than
                // block by block, so what it covers has to match the distance between the two ends.
                int x0 = (int) origin.x;
                int z0 = (int) origin.z;
                Vec3 start = new Vec3(x0 + 0.5D, SURFACE_Y, z0 + 0.5D);
                Vec3 smoothGoal = new Vec3(x0 + 40.0D, SURFACE_Y, z0 + 40.0D);
                double[] last = {x0 + 0.5D, SURFACE_Y, z0 + 0.5D};
                double[] walked = {0.0D};
                return new Scenario(900, List.of(new Bot(a, start)), List.of(),
                        server -> run(server, "player " + a + " nav goto " + coords(smoothGoal)), server ->
                {
                    ServerPlayer bot = player(server, a);
                    walked[0] += bot.position().distanceTo(new Vec3(last[0], last[1], last[2]));
                    last[0] = bot.getX();
                    last[1] = bot.getY();
                    last[2] = bot.getZ();
                    double straight = bot.position().distanceTo(start);
                    double left = bot.position().distanceTo(smoothGoal);
                    return new Probe(left <= 1.5D && walked[0] <= straight * 1.05D + 0.5D, fmt(
                            "%s walked %.2f blocks to cover %.2f straight (%.1f%% more), %.2f from the goal",
                            a, walked[0], straight, 100.0D * (walked[0] - straight) / straight, left));
                });
            }
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

    private static String setBlock(int x, int y, int z, String block)
    {
        return "setblock " + x + " " + y + " " + z + " " + block;
    }

    /** Keeps a stretch of chunks loaded, so navigation has blocks to plan over before the bot walks there. */
    private static String forceload(int x0, int z0, int x1, int z1)
    {
        return "forceload add " + x0 + " " + z0 + " " + x1 + " " + z1;
    }

    private static String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block)
    {
        return "fill " + x0 + " " + y0 + " " + z0 + " " + x1 + " " + y1 + " " + z1 + " " + block;
    }

    /** The action pack behind a bot, which is where its navigation state is read from. */
    private static EntityPlayerActionPack pack(MinecraftServer server, String name)
    {
        return ((ServerPlayerInterface) player(server, name)).getActionPack();
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
