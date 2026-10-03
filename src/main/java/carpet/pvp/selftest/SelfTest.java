package carpet.pvp.selftest;

import carpet.CarpetSettings;
import carpet.fakes.ServerPlayerInterface;
import carpet.logic.CarpetLogic;
import carpet.logic.program.BotAction;
import carpet.logic.program.BotProgram;
import carpet.logic.program.ProgramExecutor.ProgramInfo;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotBudget;
import carpet.pvp.BotStats;
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
            "spawn", "nav_goto", "nav_come", "nav_patrol", "nav_stop", "nav_follow", "chase_attack", "chase_crit", "script_run", "fill_updates", "logic_program", "logic_forever_budget",
            "sword_hits_require_aim", "sword_duel_damage", "sword_shield_break", "sword_difficulty_order", "bot_budget");

    private static final String REQUESTED = System.getProperty("carpet.selftest");
    private static final double SURFACE_Y = -60.0D;
    private static final double SPACING = 256.0D;
    private static final Gson GSON = new Gson();

    private record Bot(String name, Vec3 pos, double yaw)
    {
        Bot(String name, Vec3 pos)
        {
            this(name, pos, 0.0D);
        }
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
                run(server, "player " + bot.name() + " spawn at " + coords(bot.pos())
                        + " facing " + fmt("%.1f", bot.yaw()) + " 0 in minecraft:overworld in survival");
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
            case "sword_hits_require_aim":
                // The bot starts with its back to the target: it may only land a hit once its view has
                // turned onto it, and every rotation step it takes has to be whole mouse steps.
                Vec3 aheadOf = origin.add(0.0D, 0.0D, 2.5D);
                boolean[] aimedYet = {false};
                boolean[] hitTooEarly = {false};
                double[] angleWhileBlind = {-1.0D};
                double[] angleAtHit = {-1.0D};
                int[] hitTick = {-1};
                boolean[] ready = {false};
                return new Scenario(700, List.of(new Bot(a, origin, 180.0D), new Bot(b, aheadOf)), List.of(),
                        server ->
                {
                    ServerPlayer target = player(server, b);
                    if (!ready[0])
                    {
                        if (warmingUp(server, a, b))
                        {
                            return new Probe(false, fmt("waiting for %s and %s to finish loading", a, b));
                        }
                        swordKit(a).forEach(command -> run(server, command));
                        swordCombat(a, "beginner").forEach(command -> run(server, command));
                        ready[0] = true;
                        return new Probe(false, fmt("%s turned its back on %s", a, b));
                    }
                    BotBody body = body(server, a);
                    if (body == null)
                    {
                        return new Probe(false, fmt("waiting for %s to start fighting", a));
                    }
                    double angle = angleTo(player(server, a), target);
                    boolean hurt = target.getHealth() < 20.0F;
                    if (hitTick[0] < 0 && hurt)
                    {
                        hitTick[0] = ticks;
                        angleAtHit[0] = angle;
                    }
                    if (!aimedYet[0])
                    {
                        if (angle <= AIM_ANGLE)
                        {
                            aimedYet[0] = true;
                        }
                        else
                        {
                            angleWhileBlind[0] = angle;
                            hitTooEarly[0] = hurt;
                        }
                    }
                    BotStats stats = body.stats();
                    double limit = body.profile().maxDegPerTick();
                    return new Probe(aimedYet[0] && !hitTooEarly[0] && hitTick[0] > 0
                                    && stats.rotationOnGrid() && stats.maxRotationStep <= limit,
                            fmt("the view was %.1f degrees off the target until tick %d, the hit landed on tick %d with it %.1f degrees off, %d clicks of which %d hit and %d missed; every rotation step on the mouse grid: %s, largest step %.2f of %.2f degrees",
                                    angleWhileBlind[0], hitTick[0], hitTick[0], angleAtHit[0], stats.clicks,
                                    stats.hits, stats.misses, stats.rotationOnGrid(), stats.maxRotationStep, limit)
                            + (stats.rotationOnGrid() ? "" : fmt(", first off-grid step %.6f/%.6f degrees",
                                    stats.offGridYawStep, stats.offGridPitchStep)));
                });
            case "sword_duel_damage":
                // The target walks up to the bot and then stands there without ever fighting back. The
                // bot has to sprint the distance it wants to keep, which is what gives it a sprint hit
                // after its first one, the sprint reset and the W-tap that follows it.
                Vec3 walker = origin.add(0.0D, 0.0D, -4.5D);
                boolean[] armed = {false};
                return new Scenario(1000, List.of(new Bot(a, origin), new Bot(b, walker)), List.of(), server ->
                {
                    ServerPlayer target = player(server, b);
                    if (!armed[0])
                    {
                        if (warmingUp(server, a, b))
                        {
                            return new Probe(false, fmt("waiting for %s and %s to finish loading", a, b));
                        }
                        swordKit(a).forEach(command -> run(server, command));
                        swordKit(b).forEach(command -> run(server, command));
                        swordCombat(a, "expert").forEach(command -> run(server, command));
                        run(server, "player " + b + " move forward for 20");
                        armed[0] = true;
                        return new Probe(false, fmt("%s walks up to %s and then stands there", b, a));
                    }
                    BotBody body = body(server, a);
                    if (body == null)
                    {
                        return new Probe(false, fmt("waiting for %s to start fighting", a));
                    }
                    BotStats stats = body.stats();
                    return new Probe(target.getHealth() < 20.0F && stats.crits >= 1 && stats.sprintHits >= 1
                                    && stats.hits >= 2,
                            fmt("target has %.1f health after %d hits (%d crits, %d sprint hits, %d misses, %.1f damage dealt, %.1f taken); planner %d calls, %d simulated ticks, %d starved, %d shield ticks",
                                    target.getHealth(), stats.hits, stats.crits, stats.sprintHits, stats.misses,
                                    stats.damageDealt, stats.damageTaken, stats.plannerCalls,
                                    stats.simulatedTicks, stats.starvedTicks, stats.blockTicks));
                });
            case "sword_shield_break":
                Vec3 holder = origin.add(0.0D, 0.0D, 2.5D);
                boolean[] raised = {false};
                return new Scenario(1000, List.of(new Bot(a, origin), new Bot(b, holder)), List.of(), server ->
                {
                    ServerPlayer target = player(server, b);
                    if (!raised[0])
                    {
                        if (warmingUp(server, a, b))
                        {
                            return new Probe(false, fmt("waiting for %s and %s to finish loading", a, b));
                        }
                        swordKit(a, true).forEach(command -> run(server, command));
                        shieldKit(b).forEach(command -> run(server, command));
                        swordCombat(a, "skilled").forEach(command -> run(server, command));
                        run(server, "player " + b + " use continuous");
                        raised[0] = true;
                        return new Probe(false, fmt("%s is holding its shield up against %s", b, a));
                    }
                    BotBody body = body(server, a);
                    if (body == null)
                    {
                        return new Probe(false, fmt("waiting for %s to start fighting", a));
                    }
                    BotStats stats = body.stats();
                    boolean broken = stats.shieldBreaks >= 1 && !target.isBlocking();
                    return new Probe(broken && target.getHealth() < 20.0F,
                            fmt("shield breaks: %d, target blocking: %s, it has %.1f health after %d hits (%d clicks, %d misses, %d throttled); planner %d calls, %d starved, %d block ticks",
                                    stats.shieldBreaks, target.isBlocking(), target.getHealth(), stats.hits,
                                    stats.clicks, stats.misses, stats.throttledClicks, stats.plannerCalls,
                                    stats.starvedTicks, stats.blockTicks));
                });
            case "sword_difficulty_order":
                return new Scenario(DUEL_TICKS * DUELS + 600, List.of(), List.of(),
                        server -> spawnDuels(server, origin), SelfTest::duelProbe);
            case "bot_budget":
                List<Bot> crowd = new ArrayList<>();
                for (int pair = 0; pair < BUDGET_PAIRS; pair++)
                {
                    Vec3 spot = origin.add(0.0D, 0.0D, pair * 60.0D);
                    crowd.add(new Bot(fighterName(pair, "a"), spot));
                    crowd.add(new Bot(fighterName(pair, "b"), spot.add(0.0D, 0.0D, 3.0D)));
                }
                return new Scenario(700, crowd, List.of(), server ->
                {
                    budgetPhase = SETTLING;
                    budgetArmed = false;
                    budgetSettle = 0;
                    budgetWatch = 0;
                    budgetMax = 0;
                    budgetFighters = 0;
                    budgetTickNanos = System.nanoTime();
                    budgetTickNanosTotal = 0;
                    starvedSeen = 0;
                    budgetPlannedBefore = 0;
                    budgetStarvedBefore = 0;
                    budgetStarvedFrom = 0;
                    starvedWhileFull = 0;
                }, SelfTest::budgetProbe);
            default:
                return null;
        }
    }

    // ===== PvP combat bots =====

    /** Ticks a bot of the aim scenario needs before its view can possibly be on its target. */
    private static final int AIM_TICKS = 8;
    /** Degrees off the target within which a click of the aim scenario counts as aimed. */
    private static final double AIM_ANGLE = 30.0D;
    /** Duels of the difficulty scenario, all of them run one after the other. */
    private static final int DUELS = 6;
    /** Duels of expert against beginner the expert has to win to pass. */
    private static final int DUELS_TO_WIN = 5;
    /** Ticks one duel of the difficulty scenario may take. */
    private static final int DUEL_TICKS = 300;
    /** Pairs of fighting bots of the budget scenario. */
    private static final int BUDGET_PAIRS = 4;
    /** Ticks the budget scenario watches the normal budget for. */
    private static final int BUDGET_WATCH_TICKS = 60;
    /** Ticks every bot has to be seen fighting before the split is measured. */
    private static final int BUDGET_SETTLE = 10;
    /** Ticks the starved budget is watched for. */
    private static final int BUDGET_STARVE_TICKS = 8;

    private static final int SETTLING = 0;
    private static final int WATCHING = 1;
    private static final int STARVING = 2;
    private static final int RESTORED = 3;
    /** A budget so small that eight fighters cannot each afford one rollout of the planner. */
    private static final int BUDGET_STARVED_RULE = 64;


    private static int duelIndex = -1;
    private static int duelTicks;
    private static int expertWins;
    private static int draws;
    private static int budgetPhase;
    private static int budgetWatch;
    private static int budgetSettle;
    private static int budgetPlannedBefore;
    private static int budgetStarvedBefore;
    private static int budgetStarvedFrom;
    private static int budgetOriginal;
    private static int starvedWhileFull;
    private static boolean budgetArmed;
    private static int budgetMax;
    private static int budgetFighters;
    private static long budgetTickNanos;
    private static long budgetTickNanosTotal;
    private static int starvedSeen;

    /** Joins command lists into the mutable list the scenario machinery wants. */
    @SafeVarargs
    private static List<String> joined(List<String>... parts)
    {
        List<String> all = new ArrayList<>();
        for (List<String> part : parts)
        {
            all.addAll(part);
        }
        return all;
    }

    /** Kit of a sword bot: a diamond sword and a full set of diamond armour. */
    private static List<String> swordKit(String name)
    {
        return swordKit(name, false);
    }

    /** Kit of a sword bot that also has to break shields. */
    private static List<String> swordKit(String name, boolean withAxe)
    {
        List<String> kit = new ArrayList<>();
        kit.add("player " + name + " equip mainhand minecraft:diamond_sword");
        kit.add("player " + name + " equip head minecraft:diamond_helmet");
        kit.add("player " + name + " equip chest minecraft:diamond_chestplate");
        kit.add("player " + name + " equip legs minecraft:diamond_leggings");
        kit.add("player " + name + " equip feet minecraft:diamond_boots");
        if (withAxe)
        {
            kit.add("give " + name + " minecraft:diamond_axe");
        }
        return kit;
    }

    private static List<String> shieldKit(String name)
    {
        return List.of("give " + name + " minecraft:shield",
                "player " + name + " equip offhand minecraft:shield");
    }

    /** Turns a bot into a sword fighter of the given difficulty. */
    private static List<String> swordCombat(String name, String difficulty)
    {
        return List.of("bot option " + name + " difficulty " + difficulty,
                "bot option " + name + " shieldbreak true",
                "bot option " + name + " combat true");
    }

    /**
     * False while any of the named bots is still inside the window in which the game makes a player
     * immune to damage: a fake player has no real client, so it only counts as loaded after the
     * client-load timeout of the server runs out.
     */
    private static boolean warmingUp(MinecraftServer server, String... names)
    {
        for (String name : names)
        {
            ServerPlayer player = player(server, name);
            if (player != null && !player.connection.hasClientLoaded())
            {
                return true;
            }
        }
        return false;
    }

    /** The body of the combat bot of that name, or null while it has not started fighting yet. */
    private static BotBody body(MinecraftServer server, String name)
    {
        return player(server, name) instanceof EntityPlayerMPFake bot ? bot.getBotBrain().body() : null;
    }

    private static BotStats stats(ServerPlayer bot)
    {
        BotBody body = bot instanceof EntityPlayerMPFake fake ? fake.getBotBrain().body() : null;
        return body == null ? new BotStats() : body.stats();
    }

    /** Tells the bot to get its body built, for the scenarios that check on its counters. */
    private static boolean waiting(MinecraftServer server, String... names)
    {
        for (String name : names)
        {
            if (body(server, name) == null)
            {
                return true;
            }
        }
        return false;
    }

    /** Angle in degrees between where a bot looks and where its target is, in the horizontal plane. */
    private static double angleTo(ServerPlayer bot, ServerPlayer target)
    {
        double wanted = Math.toDegrees(Math.atan2(target.getZ() - bot.getZ(), target.getX() - bot.getX())) - 90.0D;
        double difference = wanted - bot.getYRot();
        return Math.abs(difference - 360.0D * Math.round(difference / 360.0D));
    }

    /** Spawns all the pairs of the difficulty scenario at once. */
    private static void spawnDuels(MinecraftServer server, Vec3 origin)
    {
        for (int duel = 0; duel < DUELS; duel++)
        {
            Vec3 spot = origin.add(0.0D, 0.0D, duel * 40.0D);
            spawn(server, name(duel, "a"), spot);
            spawn(server, name(duel, "b"), spot.add(0.0D, 0.0D, 3.0D));
        }
    }

    private static String name(int duel, String side)
    {
        return "SelfX" + duel + side;
    }

    private static String fighterName(int pair, String side)
    {
        return "SelfB" + pair + side;
    }

    private static void spawn(MinecraftServer server, String bot, Vec3 pos)
    {
        run(server, "player " + bot + " spawn at " + coords(pos) + " facing 0 0 in minecraft:overworld in survival");
    }

    /** One duel of the difficulty scenario: the expert takes a different side every other duel. */
    private static void startDuel(MinecraftServer server)
    {
        String first = name(duelIndex, "a");
        String second = name(duelIndex, "b");
        boolean firstIsExpert = duelIndex % 2 == 0;
        swordKit(first).forEach(command -> run(server, command));
        swordKit(second).forEach(command -> run(server, command));
        swordCombat(first, firstIsExpert ? "expert" : "beginner").forEach(command -> run(server, command));
        swordCombat(second, firstIsExpert ? "beginner" : "expert").forEach(command -> run(server, command));
        run(server, "bot duel " + first + " " + second);
    }

    /** Counts the expert bot winning the current duel of the difficulty scenario. */
    private static Probe duelProbe(MinecraftServer server)
    {
        if (duelIndex < 0)
        {
            for (int duel = 0; duel < DUELS; duel++)
            {
                if (player(server, name(duel, "a")) == null || player(server, name(duel, "b")) == null)
                {
                    return new Probe(false, fmt("waiting for the bots of duel %d to log in", duel));
                }
                if (warmingUp(server, name(duel, "a"), name(duel, "b")))
                {
                    return new Probe(false, fmt("waiting for the bots of duel %d to finish loading", duel));
                }
            }
            duelIndex = 0;
            startDuel(server);
        }
        duelTicks++;
        String first = name(duelIndex, "a");
        String second = name(duelIndex, "b");
        boolean firstIsExpert = duelIndex % 2 == 0;
        ServerPlayer expert = player(server, firstIsExpert ? first : second);
        ServerPlayer beginner = player(server, firstIsExpert ? second : first);
        if (duelTicks <= DUEL_TICKS && !(expert.isDeadOrDying() || beginner.isDeadOrDying()))
        {
            return new Probe(false, fmt("duel %d after %d ticks: expert %.1f health, beginner %.1f health",
                    duelIndex, duelTicks, expert.getHealth(), beginner.getHealth()));
        }
        boolean expertDown = expert.isDeadOrDying();
        if (expertDown)
        {
            draws++;
        }
        else
        {
            expertWins++;
        }
        run(server, "player " + first + " disconnect");
        run(server, "player " + second + " disconnect");
        duelIndex++;
        duelTicks = 0;
        if (duelIndex >= DUELS)
        {
            return new Probe(expertWins >= DUELS_TO_WIN, fmt("the expert preset won %d of %d duels against the beginner preset, %d went the other way or ran out of time",
                    expertWins, DUELS, draws));
        }
        startDuel(server);
        return new Probe(false, fmt("%d of %d expert wins so far", expertWins, DUELS));
    }

    /**
     * Watches the shared simulation budget with eight bots fighting in four pairs: first with the rule
     * as it is, then cut down so the bots get nothing to plan with, and once more after restoring it.
     */
    private static Probe budgetProbe(MinecraftServer server)
    {
        if (!budgetArmed)
        {
            // Nothing may fight before every bot has finished loading, or the first ticks of the budget
            // would go to bots that cannot be hurt yet.
            for (int pair = 0; pair < BUDGET_PAIRS; pair++)
            {
                for (String bot : List.of(fighterName(pair, "a"), fighterName(pair, "b")))
                {
                    if (warmingUp(server, bot))
                    {
                        return new Probe(false, fmt("waiting for %s to finish loading", bot));
                    }
                }
            }
            for (int pair = 0; pair < BUDGET_PAIRS; pair++)
            {
                String one = fighterName(pair, "a");
                String two = fighterName(pair, "b");
                swordKit(one).forEach(command -> run(server, command));
                swordKit(two).forEach(command -> run(server, command));
                swordCombat(one, "average").forEach(command -> run(server, command));
                swordCombat(two, "average").forEach(command -> run(server, command));
                run(server, "bot duel " + one + " " + two);
            }
            budgetArmed = true;
        }

        BotBudget budget = BotBudget.instance();
        long now = System.nanoTime();
        if (budgetTickNanos != 0L)
        {
            budgetTickNanosTotal += now - budgetTickNanos;
        }
        budgetTickNanos = now;
        int total = CarpetSettings.botSimBudget;
        // The bots of this tick have not asked for their share yet, so what the budget spent is the
        // number of the tick before.
        budgetMax = Math.max(budgetMax, budget.usedLastTick());
        budgetFighters = Math.max(budgetFighters, budget.fightersLastTick());
        budgetWatch++;
        if (budget.usedLastTick() > total)
        {
            return new Probe(false, fmt("tick %d used %d of the %d simulated ticks of the budget",
                    ticks, budget.usedLastTick(), total));
        }
        int planned = 0;
        int starved = 0;
        for (int pair = 0; pair < BUDGET_PAIRS; pair++)
        {
            for (String bot : List.of(fighterName(pair, "a"), fighterName(pair, "b")))
            {
                BotStats stats = stats(player(server, bot));
                planned += stats.plannerCalls;
                starved += stats.starvedTicks;
            }
        }

        // The first tick of a fight splits the whole budget to whoever asks first, so the split is only
        // measured once every bot has been seen fighting for a few ticks.
        if (budgetPhase == SETTLING)
        {
            if (budget.fightersLastTick() < BUDGET_PAIRS * 2)
            {
                budgetSettle = 0;
                return new Probe(false, fmt("%d of %d bots shared the last tick",
                        budget.fightersLastTick(), BUDGET_PAIRS * 2));
            }
            if (++budgetSettle < BUDGET_SETTLE)
            {
                return new Probe(false, fmt("%d of %d ticks with every bot fighting", budgetSettle, BUDGET_SETTLE));
            }
            budgetPlannedBefore = planned;
            budgetStarvedBefore = starved;
            budgetPhase = WATCHING;
            return new Probe(false, fmt("watching the budget of %d ticks", BUDGET_WATCH_TICKS));
        }
        if (budgetPhase == WATCHING)
        {
            if (budgetWatch < BUDGET_WATCH_TICKS)
            {
                return new Probe(false, fmt("%d of %d ticks watched, %d planned calls, %d starved",
                        budgetWatch, BUDGET_WATCH_TICKS, planned - budgetPlannedBefore, starved - budgetStarvedBefore));
            }
            if (planned <= budgetPlannedBefore)
            {
                return new Probe(false, "the bots planned nothing at all");
            }
            starvedWhileFull = starved - budgetStarvedBefore;
            budgetPhase = STARVING;
            budgetStarvedBefore = starved;
            budgetPlannedBefore = planned;
            budgetStarvedFrom = budgetWatch;
            budgetOriginal = total;
            run(server, "carpet botSimBudget " + BUDGET_STARVED_RULE);
            return new Probe(false, fmt("budget cut to %d", BUDGET_STARVED_RULE));
        }
        if (budgetPhase == STARVING)
        {
            if (budgetWatch < budgetStarvedFrom + BUDGET_STARVE_TICKS)
            {
                return new Probe(false, fmt("watching the starved budget, %d starved ticks so far",
                        starved - budgetStarvedBefore));
            }
            starvedSeen = starved - budgetStarvedBefore;
            budgetPhase = RESTORED;
            budgetPlannedBefore = planned;
            run(server, "carpet botSimBudget " + budgetOriginal);
            return new Probe(false, fmt("budget restored, %d starved ticks while it was %d",
                    starvedSeen, BUDGET_STARVED_RULE));
        }
        if (planned <= budgetPlannedBefore)
        {
            return new Probe(false, "waiting for the bots to plan again");
        }
        return new Probe(starvedSeen > 0 && starvedSeen >= BUDGET_PAIRS * 2,
                fmt("budget of %d simulated ticks per server tick: peak use %d with %d fighters, %d ticks without a share while it was full and %d while it was %d, average server tick %.2f ms over %d ticks",
                        budgetOriginal, budgetMax, budgetFighters, starvedWhileFull, starvedSeen,
                        BUDGET_STARVED_RULE, tickMillis(), budgetTickNanosTotal == 0 ? 0 : budgetWatch));
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

    /** Average wall-clock time between two self-test ticks of the budget scenario, milliseconds. */
    private static double tickMillis()
    {
        return budgetTickNanosTotal / 1000000.0D / (budgetWatch - 1);
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
