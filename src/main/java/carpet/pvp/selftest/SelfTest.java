package carpet.pvp.selftest;

import carpet.CarpetSettings;
import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.logic.CarpetLogic;
import carpet.helpers.OptimizedExplosion;
import carpet.logic.program.BotAction;
import carpet.logic.program.BotProgram;
import carpet.logic.program.ProgramExecutor.ProgramInfo;
import carpet.logic.web.Api;
import carpet.logic.web.AuthManager;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotBudget;
import carpet.pvp.BotStats;
import carpet.pvp.nav.NavSearchBudget;
import carpet.pvp.selftest.SelfTestReport.Result;
import carpet.CarpetSettings;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitEntry;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import carpet.utils.SpawnReporter;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
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
            "spawn_exact_name", "spawn_gamemode", "shield_disable", "kit_give", "kit_roundtrip", "sword_block",
            "explosion_rules", "xp_explosions", "scarpet_events", "scarpet_explosion", "update_suppression_block", "stackable_shulker_boxes",
            "structure_block_ignored", "persistent_parrots", "lag_free_spawning", "logic_bot_snapshot",
            "nav_maze", "nav_parkour", "nav_ladder", "nav_partial_blocks", "nav_moving_target", "nav_crowd",
            "nav_tick_budget", "nav_smooth",
            "sword_hits_require_aim", "sword_shield_break", "sword_difficulty_order", "bot_budget",
            "animate_use", "item_cd", "kit_folder", "kill",
            "interaction_updates", "punish_wrong_tool_hits", "scarpet_item_use_events", "sculk_sensor_range", "summon_natural_lightning", "explosion_state_leak", "scarpet_world_data", "tick_synced_world_borders");

    /** What every built-in kit has to put on the player it is given to. */
    record KitExpectation(String kit, String mainHand, String chestplate, String enchantment, int level, String stack, int count) {}

    private static final List<KitExpectation> KIT_EXPECTATIONS = List.of(
            new KitExpectation("sword", "diamond_sword", "diamond_chestplate", "minecraft:protection", 4, "golden_apple", 4),
            new KitExpectation("axe", "diamond_sword", "diamond_chestplate", "minecraft:protection", 4, "golden_apple", 4),
            new KitExpectation("smp", "netherite_sword", "netherite_chestplate", "minecraft:protection", 4, "experience_bottle", 16),
            new KitExpectation("mace", "mace", "netherite_chestplate", "minecraft:protection", 4, "wind_charge", 16),
            new KitExpectation("crystal", "netherite_sword", "netherite_chestplate", "minecraft:blast_protection", 4, "end_crystal", 8));

    private static final String REQUESTED = System.getProperty("carpet.selftest");
    static final double SURFACE_Y = -60.0D;
    private static final double SPACING = 256.0D;
    private static final Gson GSON = new Gson();
    /** How long the server thread gets to stop, and then how long its worker threads get to finish. */
    private static final long EXIT_WAIT_MILLIS = 120_000L;
    private static volatile MinecraftServer stoppingServer;
    private static final float SWORD_BLOCK_HIT = 4.0F;
    /** How many bolts the lightning scenario sums up, enough that missing every skeleton horse roll is not a thing. */
    private static final int LIGHTNING_BOLTS = 600;
    private static final double BORDER_FROM = 1000.0D;
    private static final double BORDER_TO = 20.0D;
    private static final long BORDER_DURATION_MILLIS = 5_000L;
    /** Six seconds of game time, which is what the ticked border needs to finish, and longer in wall clock at 40 tps. */
    private static final int BORDER_WAIT = 160;

    record Bot(String name, Vec3 pos, String gamemode, double yaw)
    {
        Bot(String name, Vec3 pos) { this(name, pos, "survival", 0.0D); }
        Bot(String name, Vec3 pos, String gamemode) { this(name, pos, gamemode, 0.0D); }
        Bot(String name, Vec3 pos, double yaw) { this(name, pos, "survival", yaw); }
    }

    record Probe(boolean ok, String detail) {}

    /** Scenarios that drive the bot with commands only. */
    static final Consumer<MinecraftServer> NOTHING = server -> {};

    /** The commands are issued once every bot has joined; the check is then polled every tick. */
    record Scenario(int timeout, List<Bot> bots, List<String> commands, Consumer<MinecraftServer> start, Function<MinecraftServer, Probe> check)
    {
        Scenario(int timeout, List<Bot> bots, List<String> commands, Function<MinecraftServer, Probe> check)
        {
            this(timeout, bots, commands, server -> {}, check);
        }
    }

    private static final List<Result> results = new ArrayList<>();
    private static final ItemStack SHIELD = new ItemStack(Items.SHIELD);
    /** A session that was never issued to a player, as /carpetlogic from the console makes. */
    private static final AuthManager.Session CONSOLE_SESSION = new AuthManager.Session(null, "Server", Long.MAX_VALUE);
    private static final ItemStack PEARL = new ItemStack(Items.ENDER_PEARL);

    /** Two kits dropped into the world's kit folder, in the two shapes a kit file can be written in. */
    private static final String HAND_WRITTEN_KIT = "self_handwritten";
    private static final String SAVED_KIT = "self_saved";

    private static final String HAND_WRITTEN_KIT_JSON = """
            {
              "name": "self_handwritten",
              "items": [
                { "item": "minecraft:netherite_sword", "slot": 0,
                  "enchantments": [ { "id": "minecraft:sharpness", "level": 3 } ] },
                { "item": "minecraft:cooked_beef", "count": 7, "slot": 1 }
              ]
            }
            """;

    private static final String SAVED_KIT_JSON = """
            {
              "name": "self_saved",
              "items": [
                { "slot": 0, "stack": { "id": "minecraft:diamond_pickaxe", "count": 1 } },
                { "slot": "offhand", "stack": { "id": "minecraft:shield", "count": 1 } }
              ]
            }
            """;
    private static List<String> names;
    private static Scenario current;
    private static boolean acting;
    private static boolean finished;
    private static int drainTicks;
    private static final int DRAINED_CHUNKS = 600;
    private static final int DRAIN_MIN_TICKS = 60;
    private static final int DRAIN_TIMEOUT_TICKS = 1200;
    private static int ticks;

    private SelfTest() {}

    public static void tick(MinecraftServer server)
    {
        if (REQUESTED == null || finished) return;
        if (names == null)
        {
            List<String> all = new ArrayList<>(SCENARIOS);
            all.addAll(ScenarioIndex.SCENARIOS.keySet());
            names = SelfTestReport.parseNames(REQUESTED, all);
            run(server, "carpet fakePlayerNavigation true");
            // A flat world breeds slimes everywhere, and one wandering into a fight changes its outcome.
            run(server, "gamerule spawn_mobs false");
            run(server, "kill @e[type=!player]");
            // Sprinting only removes the wait between ticks; the scenarios take the same ticks either way.
            run(server, "tick sprint 1d");
        }
        if (current == null)
        {
            if (results.size() == names.size())
            {
                if (drained(server)) finish(server);
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
            // Whatever this scenario changes in the rules goes back when it is over, pass or fail.
            RuleGuard.enter();
            for (Bot bot : current.bots())
            {
                run(server, "player " + bot.name() + " spawn at " + coords(bot.pos())
                        + " facing " + fmt("%.1f", bot.yaw()) + " 0 in minecraft:overworld in " + bot.gamemode());
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

    static Probe probe(MinecraftServer server)
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

    static Scenario scenario(String name, int index)
    {
        // a is the bot under test, b the second player it follows or fights, c and d the ones a scenario
        // needs attackers of its own. Everyone spawns looking along +z.
        String a = "SelfA" + index;
        String b = "SelfB" + index;
        String c = "SelfC" + index;
        String d = "SelfD" + index;
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
            {
                // nav stop has to let go of the movement inputs it was driving, so the bot halts where it
                // is instead of walking on at its last speed.
                Vec3 far = origin.add(40.0D, 0.0D, 0.0D);
                boolean[] stopping = {false};
                double[] lastX = {origin.x};
                int[] since = {0};
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
                    double moved = Math.abs(x - lastX[0]);
                    lastX[0] = x;
                    // a few ticks of slack for the tick nav stop landed on, then the bot has to be still
                    if (moved <= 0.01D) since[0]++;
                    return new Probe(since[0] >= 4, fmt(
                            "%s moved %.3f blocks in the last tick, %d ticks after nav stop", a, moved, since[0]));
                });
            }
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
            case "logic_bot_snapshot":
                // What the web panel reads: two bots on the server, one of them hurt, through the route itself.
                boolean[] hit = {false};
                return new Scenario(300, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 2.0D))),
                        List.of(), server ->
                {
                    if (!hit[0])
                    {
                        // A fake player cannot be hurt while its connection is still counted as loading.
                        if (!player(server, b).connection.hasClientLoaded()) return pending(b + " is still loading");
                        damage(server, player(server, b));
                        hit[0] = true;
                        return pending(fmt("hit %s for %.1f health", b, SWORD_BLOCK_HIT));
                    }
                    Api.Response response = new Api(server, CarpetLogic.INSTANCE)
                            .handle("GET", "/api/bots", CONSOLE_SESSION, "");
                    if (response.status() != 200) return new Probe(false, "GET /api/bots answered " + response.status());
                    JsonObject snapshot = response.body().getAsJsonObject().getAsJsonObject("bots");
                    if (!snapshot.has(a) || !snapshot.has(b))
                    {
                        return new Probe(false, fmt("the snapshot holds %s, both %s and %s were on the server",
                                snapshot.keySet(), a, b));
                    }
                    JsonObject untouched = snapshot.getAsJsonObject(a);
                    JsonObject hurt = snapshot.getAsJsonObject(b);
                    Set<String> expected = Set.of("name", "dimension", "x", "y", "z", "yaw", "pitch", "health", "maxHealth",
                            "absorption", "foodLevel", "armor", "alive", "gamemode", "sprinting", "sneaking", "equipment",
                            "pvp", "target", "program");
                    for (JsonObject bot : List.of(untouched, hurt))
                    {
                        if (!bot.keySet().equals(expected))
                        {
                            return new Probe(false, fmt("the snapshot of %s has %s, expected %s", bot.get("name"),
                                    bot.keySet(), new TreeSet<>(expected)));
                        }
                    }
                    float whole = player(server, a).getHealth();
                    float wounded = player(server, b).getHealth();
                    boolean healthy = untouched.get("health").getAsFloat() == whole && hurt.get("health").getAsFloat() == wounded;
                    boolean tookTheHit = wounded < whole && hurt.get("health").getAsFloat() == player(server, b).getHealth();
                    boolean styled = "MELEE".equals(hurt.getAsJsonObject("pvp").get("style").getAsString());
                    boolean idle = hurt.get("program").isJsonNull() && hurt.get("target").isJsonNull();
                    return new Probe(healthy && tookTheHit && styled && idle, fmt(
                            "the snapshot has %s with %.1f health and %s with %.1f, style %s",
                            a, untouched.get("health").getAsFloat(), b, hurt.get("health").getAsFloat(),
                            hurt.getAsJsonObject("pvp").get("style")));
                });
            case "animate_use":
            {
                // animate attack is the main-hand swing and animate use the off-hand one, so the two are
                // told apart by which hand the player ends up swinging.
                int[] phase = {0};
                InteractionHand[] seen = new InteractionHand[2];
                return new Scenario(300, List.of(new Bot(a, origin)), List.of(), server ->
                {
                    ServerPlayer bot = player(server, a);
                    InteractionHand hand = swingHand(bot);
                    switch (phase[0])
                    {
                        case 0:
                            if (hand != null) return pending(a + " is already swinging");
                            run(server, "player " + a + " animate use");
                            phase[0] = 1;
                            return pending("issued animate use");
                        case 1:
                            if (hand == null) return pending("waiting for the animate use swing");
                            seen[0] = hand;
                            phase[0] = 2;
                            return pending(fmt("animate use swung the %s hand", handName(hand)));
                        case 2:
                            if (hand != null) return pending("waiting for the animate use swing to finish");
                            run(server, "player " + a + " animate attack");
                            phase[0] = 3;
                            return pending("issued animate attack");
                        case 3:
                            if (hand == null) return pending("waiting for the animate attack swing");
                            seen[1] = hand;
                            phase[0] = 4;
                            return pending(fmt("animate attack swung the %s hand", handName(hand)));
                        default:
                            boolean right = seen[0] == InteractionHand.OFF_HAND
                                    && seen[1] == InteractionHand.MAIN_HAND;
                            return new Probe(right, fmt("animate use swung the %s hand and animate attack the %s hand",
                                    handName(seen[0]), handName(seen[1])));
                    }
                });
            }
            case "item_cd":
            {
                // Throwing an ender pearl puts the item on a 20 tick cooldown, and the bare form of itemCd
                // has to clear every cooldown the player is carrying rather than only say that it will.
                int[] phase = {0};
                int[] cleared = {-1};
                boolean[] gone = {false};
                return new Scenario(300, List.of(new Bot(a, origin)), List.of(
                        "player " + a + " equip mainhand minecraft:ender_pearl",
                        "player " + a + " look down",
                        "player " + a + " use once"), server ->
                {
                    ServerPlayer bot = player(server, a);
                    if (phase[0] == 0)
                    {
                        if (!bot.getCooldowns().isOnCooldown(PEARL)) return pending(a + " is not on the ender pearl cooldown yet");
                        cleared[0] = result(server, "player " + a + " itemCd");
                        gone[0] = !bot.getCooldowns().isOnCooldown(PEARL);
                        phase[0] = 1;
                    }
                    // The cooldown only lasts 20 ticks, so it has to be gone on the tick the command ran.
                    return new Probe(gone[0] && cleared[0] > 0, fmt(
                            "itemCd reported %d cleared, the ender pearl is %s", cleared[0],
                            gone[0] ? "off cooldown" : "still on cooldown"));
                });
            }
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
            case "kit_give":
                List<Bot> kitBots = new ArrayList<>();
                List<String> kitCommands = new ArrayList<>();
                for (int i = 0; i < KIT_EXPECTATIONS.size(); i++)
                {
                    String botName = a + "k" + i;
                    kitBots.add(new Bot(botName, origin.add(2.0D * i, 0.0D, 0.0D)));
                    kitCommands.add("bot kit give " + botName + " " + KIT_EXPECTATIONS.get(i).kit());
                }
                return new Scenario(200, kitBots, kitCommands, server ->
                        kitsGiven(server, kitBots.stream().map(Bot::name).toList()));
            case "kit_roundtrip":
                return new Scenario(200, List.of(new Bot(a, origin)), List.of(
                        "give " + a + " minecraft:diamond_sword",
                        "give " + a + " minecraft:golden_apple 5",
                        "give " + a + " minecraft:netherite_chestplate",
                        "give " + a + " minecraft:shield",
                        "give " + a + " minecraft:cooked_beef 32"), server ->
                {
                    ServerPlayer bot = player(server, a);
                    List<ItemStack> before = slots(bot);
                    int selected = bot.getInventory().getSelectedSlot();
                    KitStore store = KitStore.of(server);

                    // The three calls /bot kit give and /bot kit restore make for a real player.
                    KitInventory.save(bot);
                    KitInventory.apply(bot, store.get("sword").orElseThrow(), server.registryAccess());
                    if (!holds(bot.getMainHandItem(), "diamond_sword")) return new Probe(false, "the kit did not go on");
                    KitInventory.restore(bot);
                    if (bot.getInventory().getSelectedSlot() != selected) return new Probe(false, "the selected slot changed");
                    String problem = sameInventory(before, slots(bot));
                    if (problem != null) return new Probe(false, "after restore " + problem);

                    // The same inventory again, but through the kit file a player would save.
                    String kitName = "self_test_" + a;
                    try
                    {
                        store.save(KitInventory.capture(bot, kitName));
                        store.reload();
                    }
                    catch (IOException e)
                    {
                        return new Probe(false, "could not save the kit: " + e);
                    }
                    Kit saved = store.get(kitName).orElse(null);
                    if (saved == null) return new Probe(false, kitName + " did not come back from its file");
                    KitInventory.apply(bot, saved, server.registryAccess());
                    problem = sameInventory(before, slots(bot));
                    if (problem != null) return new Probe(false, "from the kit file " + problem);
                    if (!store.delete(kitName) || store.get(kitName).isPresent()) return new Probe(false, kitName + " was not deleted");
                    return new Probe(true, fmt("all %d slots of %s survived both round trips", before.size(), a));
                });
            case "kit_folder":
            {
                // Both shapes of kit file dropped into the world's kit folder have to be picked up by a
                // reload, without the server being restarted.
                boolean[] written = {false};
                boolean[] given = {false};
                return new Scenario(300, List.of(new Bot(a, origin), new Bot(b, origin.add(2.0D, 0.0D, 0.0D))),
                        List.of(), server ->
                {
                    KitStore store = KitStore.of(server);
                    if (!written[0])
                    {
                        String problem = writeKitFiles(server);
                        if (problem != null) return new Probe(false, problem);
                        written[0] = true;
                        if (result(server, "bot kit reload") == 0)
                        {
                            return new Probe(false, "bot kit reload reported a kit that did not load");
                        }
                        return pending("wrote both kit files and reloaded them");
                    }
                    if (!store.problems().isEmpty()) return new Probe(false, "kits did not load: " + store.problems());
                    if (!store.customNames().containsAll(List.of(HAND_WRITTEN_KIT, SAVED_KIT)))
                    {
                        return new Probe(false, "the folder holds " + store.customNames());
                    }
                    if (!given[0])
                    {
                        run(server, "bot kit give " + a + " " + HAND_WRITTEN_KIT);
                        run(server, "bot kit give " + b + " " + SAVED_KIT);
                        given[0] = true;
                        return pending("gave both kits");
                    }
                    String problem = checkKit(player(server, a), HAND_WRITTEN_KIT, server.registryAccess());
                    if (problem != null) return new Probe(false, HAND_WRITTEN_KIT + ": " + problem);
                    problem = checkKit(player(server, b), SAVED_KIT, server.registryAccess());
                    if (problem != null) return new Probe(false, SAVED_KIT + ": " + problem);
                    store.delete(HAND_WRITTEN_KIT);
                    store.delete(SAVED_KIT);
                    return new Probe(true, fmt("%s and %s both loaded out of the world's kit folder",
                            HAND_WRITTEN_KIT, SAVED_KIT));
                });
            }
            case "sword_block":
            {
                // One player holds its sword up, the other one stands idle, both take the same fixed hit. With the rule
                // on the blocking one must lose swordBlockDamageMultiplier of it, with the rule off it must lose all of it.
                // The knockback half is measured on the same two players: each is hit once by its own attacker, and
                // the blocking one has to be pushed by swordBlockKnockbackMultiplier of what the idle one is pushed by.
                int[] phase = {0};
                float[] guarding = new float[2];
                float[] open = new float[2];
                double[] peak = new double[2];
                return new Scenario(600, List.of(
                                new Bot(a, origin),
                                new Bot(b, origin.add(0.0D, 0.0D, 2.0D)),
                                new Bot(c, origin.add(0.0D, 0.0D, -2.0D)),
                                new Bot(d, origin.add(0.0D, 0.0D, 4.0D))),
                        List.of("carpet swordBlockHitting true",
                                "player " + a + " equip mainhand minecraft:diamond_sword",
                                "player " + a + " use continuous",
                                "player " + c + " equip mainhand minecraft:wooden_sword",
                                "player " + d + " equip mainhand minecraft:wooden_sword",
                                "player " + d + " turn back"), server ->
                {
                    ServerPlayer blocker = player(server, a);
                    ServerPlayer idle = player(server, b);
                    if (!blocker.isUsingItem()) return pending(a + " is not holding the sword up yet");
                    if (phase[0] == 0)
                    {
                        if (!hittable(blocker, idle)) return pending(hitWaitReason(blocker, idle));
                        guarding[0] = damage(server, blocker);
                        open[0] = damage(server, idle);
                        phase[0] = 1;
                        return pending(fmt("with the rule on %s lost %.1f health while blocking and %s lost %.1f",
                                a, guarding[0], b, open[0]));
                    }
                    if (phase[0] == 1)
                    {
                        peak[0] = Math.max(peak[0], blocker.getDeltaMovement().horizontalDistance());
                        peak[1] = Math.max(peak[1], idle.getDeltaMovement().horizontalDistance());
                        if (peak[0] < 0.05D || peak[1] < 0.05D)
                        {
                            // Asked for again every tick, because a swing below full attack strength is
                            // dropped, so this is one hit per player as soon as their attacker is ready.
                            run(server, "player " + c + " attack once");
                            run(server, "player " + d + " attack once");
                            return pending(fmt("%s was pushed %.3f and %s %.3f so far, waiting for a hit to land",
                                    a, peak[0], b, peak[1]));
                        }
                        run(server, "player " + c + " stop");
                        run(server, "player " + d + " stop");
                        run(server, "carpet swordBlockHitting false");
                        phase[0] = 2;
                        return pending(fmt("with the rule on %s was pushed %.3f and the idle %s %.3f",
                                a, peak[0], b, peak[1]));
                    }
                    if (phase[0] == 2)
                    {
                        if (!hittable(blocker, idle)) return pending(hitWaitReason(blocker, idle));
                        guarding[1] = damage(server, blocker);
                        open[1] = damage(server, idle);
                        phase[0] = 3;
                        return pending(fmt("with the rule off %s lost %.1f health while blocking and %s lost %.1f",
                                a, guarding[1], b, open[1]));
                    }
                    // both idle players have to take the whole hit, otherwise the numbers below mean nothing
                    boolean hitsLanded = same(open[0], SWORD_BLOCK_HIT) && same(open[1], SWORD_BLOCK_HIT);
                    float factor = (float) CarpetSettings.swordBlockDamageMultiplier;
                    boolean halved = same(guarding[0], SWORD_BLOCK_HIT * factor);
                    boolean wholeAgain = same(guarding[1], SWORD_BLOCK_HIT);
                    // roughly half, with room for the ground friction eating a different share of each push
                    boolean knockedBackLess = peak[0] < peak[1] * 0.75D && peak[0] > peak[1] * 0.25D;
                    return new Probe(hitsLanded && halved && wholeAgain && knockedBackLess, fmt(
                            "damage: with the rule on %s lost %.1f of the %.1f a hit takes, with it off %.1f; knockback: %s %.3f and the idle %s %.3f",
                            a, guarding[0], SWORD_BLOCK_HIT, guarding[1], a, peak[0], b, peak[1]));
                });
            }
            case "kill":
            {
                // The victim is spawned by a command rather than through the scenario's bot list, because
                // the check has to see it gone from there.
                int[] phase = {0};
                return new Scenario(300, List.of(), List.of(
                        "player " + a + " spawn at " + coords(origin) + " facing 0 0 in minecraft:overworld in survival"), server ->
                {
                    if (phase[0] == 0)
                    {
                        if (player(server, a) == null) return pending(a + " has not joined yet");
                        run(server, "player " + a + " kill");
                        phase[0] = 1;
                        return pending("issued kill on " + a);
                    }
                    boolean gone = player(server, a) == null;
                    return new Probe(gone, gone ? a + " is gone from the player list"
                            : a + " is still in the player list after kill");
                });
            }
            case "explosion_rules":
                return explosionRules(a, origin);
            case "xp_explosions":
                return xpExplosions(a, origin);
            case "scarpet_events":
                return scarpetEvents(b, origin);
            case "scarpet_explosion":
                return scarpetExplosion(a, origin);
            case "update_suppression_block":
                return updateSuppressionBlock(origin);
            case "stackable_shulker_boxes":
                return stackableShulkerBoxes(origin);
            case "structure_block_ignored":
                return structureBlockIgnored(origin);
            case "persistent_parrots":
                return persistentParrots(a, b, c, origin);
            case "lag_free_spawning":
                return lagFreeSpawning(a, b, origin);
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
                boolean[] walkAsked = {false};
                return new Scenario(900, List.of(new Bot(a, start)), List.of(), server ->
                {
                    ServerPlayer bot = player(server, a);
                    if (!walkAsked[0])
                    {
                        // A sprinting server does not wait for chunks, and a goal in a chunk that has not loaded
                        // reads as solid: the walk is only asked for once the ground along it is there.
                        for (int step = 0; step <= 40; step += 8)
                        {
                            if (!bot.level().hasChunk((x0 + step) >> 4, (z0 + step) >> 4))
                            {
                                return new Probe(false, "waiting for the chunks along the walk to load");
                            }
                        }
                        run(server, "player " + a + " nav goto " + coords(smoothGoal));
                        walkAsked[0] = true;
                    }
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
            case "interaction_updates":
                return interactionUpdates(a, origin);
            case "punish_wrong_tool_hits":
                return punishWrongToolHits(a, origin);
            case "scarpet_item_use_events":
                return scarpetItemUseEvents(a, origin);
            case "sculk_sensor_range":
                return sculkSensorRange(a, origin);
            case "summon_natural_lightning":
                return summonNaturalLightning(origin);
            case "explosion_state_leak":
                return explosionStateLeak(a, origin);
            case "scarpet_world_data":
                return scarpetWorldData();
            case "tick_synced_world_borders":
                return tickSyncedWorldBorders();
            default:
                ScenarioIndex.Factory factory = ScenarioIndex.SCENARIOS.get(name);
                return factory == null ? null : factory.create(a, b, c, origin);
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
    static List<String> joined(List<String>... parts)
    {
        List<String> all = new ArrayList<>();
        for (List<String> part : parts)
        {
            all.addAll(part);
        }
        return all;
    }

    /** Kit of a sword bot: a diamond sword and a full set of diamond armour. */
    static List<String> swordKit(String name)
    {
        return swordKit(name, false);
    }

    /** Kit of a sword bot that also has to break shields. */
    static List<String> swordKit(String name, boolean withAxe)
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

    static List<String> shieldKit(String name)
    {
        return List.of("give " + name + " minecraft:shield",
                "player " + name + " equip offhand minecraft:shield");
    }

    /** Turns a bot into a sword fighter of the given difficulty. */
    static List<String> swordCombat(String name, String difficulty)
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
    static boolean warmingUp(MinecraftServer server, String... names)
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
    static BotBody body(MinecraftServer server, String name)
    {
        return player(server, name) instanceof EntityPlayerMPFake bot ? bot.getBotBrain().body() : null;
    }

    static BotStats stats(ServerPlayer bot)
    {
        BotBody body = bot instanceof EntityPlayerMPFake fake ? fake.getBotBrain().body() : null;
        return body == null ? new BotStats() : body.stats();
    }

    /** Tells the bot to get its body built, for the scenarios that check on its counters. */
    static boolean waiting(MinecraftServer server, String... names)
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
    static double angleTo(ServerPlayer bot, ServerPlayer target)
    {
        double wanted = Math.toDegrees(Math.atan2(target.getZ() - bot.getZ(), target.getX() - bot.getX())) - 90.0D;
        double difference = wanted - bot.getYRot();
        return Math.abs(difference - 360.0D * Math.round(difference / 360.0D));
    }

    /** Spawns all the pairs of the difficulty scenario at once. */
    static void spawnDuels(MinecraftServer server, Vec3 origin)
    {
        for (int duel = 0; duel < DUELS; duel++)
        {
            Vec3 spot = origin.add(0.0D, 0.0D, duel * 40.0D);
            spawn(server, name(duel, "a"), spot);
            spawn(server, name(duel, "b"), spot.add(0.0D, 0.0D, 3.0D));
        }
    }

    static String name(int duel, String side)
    {
        return "SelfX" + duel + side;
    }

    static String fighterName(int pair, String side)
    {
        return "SelfB" + pair + side;
    }

    static void spawn(MinecraftServer server, String bot, Vec3 pos)
    {
        run(server, "player " + bot + " spawn at " + coords(pos) + " facing 0 0 in minecraft:overworld in survival");
    }

    /** One duel of the difficulty scenario: the expert takes a different side every other duel. */
    static void startDuel(MinecraftServer server)
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
    static Probe duelProbe(MinecraftServer server)
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
    static Probe budgetProbe(MinecraftServer server)
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

    /** The hand the player's current swing is with, or null while it is not swinging. */
    static InteractionHand swingHand(ServerPlayer player)
    {
        //? if >=26.3 {
        LivingEntity.SwingDescription swing = player.getCurrentSwing();
        return swing == null ? null : swing.hand();
        //?} else {
        /*return player.swinging ? player.swingingArm : null;
        *///?}
    }

    static String handName(InteractionHand hand)
    {
        return hand == InteractionHand.OFF_HAND ? "off" : "main";
    }

    /** Puts the two shapes of kit file into the world's kit folder, the way a server admin would. */
    static String writeKitFiles(MinecraftServer server)
    {
        Path folder = server.getWorldPath(LevelResource.ROOT).resolve("carpet-kits");
        try
        {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(HAND_WRITTEN_KIT + ".json"), HAND_WRITTEN_KIT_JSON);
            Files.writeString(folder.resolve(SAVED_KIT + ".json"), SAVED_KIT_JSON);
            return null;
        }
        catch (IOException e)
        {
            return "could not write the kit files: " + e;
        }
    }

    /** What a kit from the world's folder has to have put on the player it was given to. */
    static String checkKit(ServerPlayer bot, String kit, RegistryAccess registries)
    {
        String mainHand = kit.equals(HAND_WRITTEN_KIT) ? "netherite_sword" : "diamond_pickaxe";
        if (!holds(bot.getMainHandItem(), mainHand))
            return "holds " + describe(bot.getMainHandItem()) + " instead of " + mainHand;
        if (kit.equals(HAND_WRITTEN_KIT))
        {
            Holder<Enchantment> sharpness = registries.lookupOrThrow(Registries.ENCHANTMENT)
                    .get(Identifier.parse("minecraft:sharpness")).orElseThrow();
            int level = EnchantmentHelper.getItemEnchantmentLevel(sharpness, bot.getMainHandItem());
            if (level != 3) return fmt("its sword has sharpness %d, expected 3", level);
            int beef = 0;
            for (ItemStack stack : bot.getInventory().getNonEquipmentItems())
            {
                if (holds(stack, "cooked_beef")) beef += stack.getCount();
            }
            if (beef != 7) return fmt("has %d cooked_beef, expected 7", beef);
        }
        else if (!holds(bot.getItemBySlot(EquipmentSlot.OFFHAND), "shield"))
        {
            return "holds " + describe(bot.getItemBySlot(EquipmentSlot.OFFHAND)) + " in its off hand instead of a shield";
        }
        return null;
    }

    // A bot program started through the Java API, the way the web editor's execute endpoint starts one, and
    // never as the console: a program started from a command has no player to run its commands as.
    static void startProgram(MinecraftServer server, String botName, String actions)
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

    static String status(String botName)
    {
        ProgramInfo info = CarpetLogic.INSTANCE.getProgramExecutor().getPrograms().get(botName);
        return info == null ? "gone" : info.status();
    }

    /** The first slot where the two lists differ, or null when they hold the same things. */
    static String sameInventory(List<ItemStack> expected, List<ItemStack> actual)
    {
        if (expected.size() != actual.size()) return fmt("there are %d slots, not %d", actual.size(), expected.size());
        for (int i = 0; i < expected.size(); i++)
        {
            if (!ItemStack.matches(expected.get(i), actual.get(i)))
            {
                return fmt("slot %d is %s, was %s", i, describe(actual.get(i)), describe(expected.get(i)));
            }
        }
        return null;
    }

    static Probe kitsGiven(MinecraftServer server, List<String> bots)
    {
        KitStore store = KitStore.of(server);
        if (!store.problems().isEmpty()) return new Probe(false, "kits did not load: " + store.problems());

        for (String name : store.builtInNames())
        {
            Kit kit = store.get(name).orElseThrow();
            for (KitEntry entry : kit.entries())
            {
                try
                {
                    entry.createStack(server.registryAccess());
                }
                catch (IllegalArgumentException e)
                {
                    return new Probe(false, "kit " + name + " does not build: " + e.getMessage());
                }
            }
        }

        List<String> given = new ArrayList<>();
        for (int i = 0; i < bots.size(); i++)
        {
            KitExpectation expected = KIT_EXPECTATIONS.get(i);
            String problem = checkKit(player(server, bots.get(i)), expected, server);
            if (problem != null) return new Probe(false, expected.kit() + ": " + problem);
            given.add(expected.kit());
        }
        return new Probe(true, fmt("%s each gave their weapon, chestplate and stack", String.join(", ", given)));
    }

    static String checkKit(ServerPlayer bot, KitExpectation expected, MinecraftServer server)
    {
        if (!holds(bot.getMainHandItem(), expected.mainHand()))
            return "holds " + describe(bot.getMainHandItem()) + " instead of " + expected.mainHand();

        ItemStack chest = bot.getItemBySlot(EquipmentSlot.CHEST);
        if (!holds(chest, expected.chestplate())) return "wears " + describe(chest) + " instead of " + expected.chestplate();

        Holder<Enchantment> enchantment = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .get(Identifier.parse(expected.enchantment())).orElseThrow();
        int level = EnchantmentHelper.getItemEnchantmentLevel(enchantment, chest);
        if (level != expected.level()) return fmt("%s has %s %d, expected %d", expected.chestplate(), expected.enchantment(), level, expected.level());

        int count = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems())
        {
            if (holds(stack, expected.stack())) count += stack.getCount();
        }
        if (count != expected.count()) return fmt("has %d %s, expected %d", count, expected.stack(), expected.count());
        return null;
    }

    /** Every slot a kit can touch: the hotbar and inventory, the armour and the offhand. */
    static List<ItemStack> slots(ServerPlayer player)
    {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) slots.add(player.getInventory().getItem(i).copy());
        for (EquipmentSlot slot : EquipmentSlot.values())
        {
            if (slot.isArmor() || slot == EquipmentSlot.OFFHAND) slots.add(player.getItemBySlot(slot).copy());
        }
        return slots;
    }

    static boolean holds(ItemStack stack, String item)
    {
        Identifier key = Identifier.withDefaultNamespace(item);
        return !stack.isEmpty() && BuiltInRegistries.ITEM.containsKey(key) && stack.getItem() == BuiltInRegistries.ITEM.getValue(key);
    }

    static String describe(ItemStack stack)
    {
        return stack.isEmpty() ? "nothing" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * rules explosionNoBlockDamage and optimizedTNT (mixin Explosion_optimizedTntMixin, which hands the explosion to
     * carpet.helpers.OptimizedExplosion and that casts it to ExplosionAccessor): a primed tnt leaves the stone next
     * to it standing while explosionNoBlockDamage is on and blows it away while it is off. optimizedTNT is on
     * throughout, so both blasts go through the optimized path, and each scenario waits for the primed tnt to be
     * gone before it looks at the blocks. The block is stone and not dirt because a floating dirt block one above the
     * grass of the flat world is now and then rewritten by a grass random tick, which says nothing about explosions.
     */
    static Scenario explosionRules(String a, Vec3 origin)
    {
        BlockPos spared = BlockPos.containing(origin);
        BlockPos doomed = spared.east(16);
        // a fake player nearby is what makes the chunk tick entities, and a primed tnt only counts down there
        return new Scenario(600, List.of(new Bot(a, origin.add(0.0D, 0.0D, 6.0D))), List.of(
                forceload(spared),
                "carpet optimizedTNT true",
                "carpet explosionNoBlockDamage true",
                setBlock(spared, "minecraft:stone"),
                summonTnt(spared.east(2))), server ->
        {
            if (primed(server, spared) > 0) explosionRulesPhase[2] = 1;
            if (explosionRulesPhase[0] == 0)
            {
                if (explosionRulesPhase[2] == 0) return new Probe(false, "waiting for the first explosion");
                if (primed(server, spared) > 0) return new Probe(false, "waiting for the first explosion");
                explosionRulesPhase[0] = 1;
                explosionRulesPhase[1] = ticks + 2;
                run(server, "carpet explosionNoBlockDamage false");
                run(server, setBlock(doomed, "minecraft:stone"));
                run(server, summonTnt(doomed.east(2)));
                return new Probe(false, "second tnt primed with the rule off");
            }
            if (ticks < explosionRulesPhase[1]) return new Probe(false, "waiting for the second explosion");
            if (primed(server, spared) > 0) return new Probe(false, "waiting for the second explosion");
            BlockState sparedState = server.overworld().getBlockState(spared);
            BlockState doomedState = server.overworld().getBlockState(doomed);
            return new Probe(sparedState.is(Blocks.STONE) && doomedState.isAir(), fmt(
                    "the stone next to a tnt primed with explosionNoBlockDamage on is %s and the one with it off is %s",
                    sparedState, doomedState));
        });
    }

    /**
     * rule xpFromExplosions (mixin Explosion_xpFromBlocksMixin): an ore block blown up only drops experience while
     * the rule is on.
     */
    static Scenario xpExplosions(String a, Vec3 origin)
    {
        BlockPos dryOre = BlockPos.containing(origin);
        BlockPos wetOre = dryOre.east(16);
        return new Scenario(600, List.of(new Bot(a, origin.add(0.0D, 0.0D, 6.0D))), List.of(
                forceload(dryOre),
                "carpet explosionNoBlockDamage false",
                "carpet xpFromExplosions false",
                setBlock(dryOre, "minecraft:diamond_ore"),
                summonTnt(dryOre.east(2))), server ->
        {
            if (primed(server, dryOre) > 0) xpPhase[2] = 1;
            if (xpPhase[0] == 0)
            {
                if (xpPhase[2] == 0 || primed(server, dryOre) > 0) return new Probe(false, "waiting for the first explosion");
                xpPhase[0] = 1;
                xpPhase[1] = ticks + 3;
                run(server, "carpet xpFromExplosions true");
                run(server, setBlock(wetOre, "minecraft:diamond_ore"));
                run(server, summonTnt(wetOre.east(2)));
                return new Probe(false, "second tnt primed with the rule on");
            }
            if (ticks < xpPhase[1]) return new Probe(false, "waiting for the second explosion");
            if (primed(server, dryOre) > 0) return new Probe(false, "waiting for the second explosion");
            int dry = count(server, ExperienceOrb.class, dryOre, 8.0D);
            int wet = count(server, ExperienceOrb.class, wetOre, 8.0D);
            return new Probe(dry == 0 && wet > 0, fmt(
                    "the ore blown up with xpFromExplosions off dropped %d xp and the one with it on dropped %d xp",
                    dry, wet));
        });
    }

    private static String paletteLog = "";

    /** phase, earliest tick of the check, and whether the primed tnt has been seen at all */
    private static int[] explosionRulesPhase = new int[3];
    private static int[] xpPhase = new int[3];

    /**
     * mixin Player_scarpetEventsMixin, and with it rules damageTickOverrides and damageTickOther: a freshly spawned
     * fake player is invulnerable for a while, which is why the first /damage is only sent once it wears off. The
     * scarpet event __on_player_takes_damage only reaches a script once an app with a handler is loaded, and hits
     * fifteen ticks apart only both land while damageTickOverrides is off.
     */
    static Scenario scarpetEvents(String b, Vec3 origin)
    {
        int[] unhandled = {-1};
        int[] second = {-1};
        int[] swallowed = {-1};
        int[] landed = {-1};
        return new Scenario(400, List.of(new Bot(b, origin)), List.of("script run global_selftest_hits = 0"), server ->
        {
            switch (ticks)
            {
                case 90:
                    run(server, "damage " + b + " 1 minecraft:generic");
                    return new Probe(false, "first hit sent with no scarpet handler loaded");
                case 91:
                    unhandled[0] = result(server, "script run global_selftest_hits");
                    run(server, "script run __on_player_takes_damage(source, amount, damage_type, attacker) -> global_selftest_hits = global_selftest_hits + amount");
                    run(server, "carpet damageTickOverrides true");
                    run(server, "carpet damageTickOther 60");
                    return new Probe(false, "the unhandled hit counted " + unhandled[0] + ", handler loaded, damageTickOther turned to 60");
                case 110:
                    run(server, "damage " + b + " 1 minecraft:generic");
                    return new Probe(false, "second hit sent fifteen ticks after the first");
                case 121:
                    second[0] = result(server, "script run global_selftest_hits");
                    return new Probe(false, "the second hit counted " + second[0]);
                case 125:
                    run(server, "damage " + b + " 1 minecraft:generic");
                    return new Probe(false, "third hit sent inside the damageTickOther window");
                case 136:
                    swallowed[0] = result(server, "script run global_selftest_hits");
                    return new Probe(false, swallowed[0] == second[0]
                            ? "the third hit was swallowed"
                            : "the third hit was counted despite damageTickOther 60");
                case 180:
                    run(server, "damage " + b + " 1 minecraft:generic");
                    return new Probe(false, "fourth hit sent after the window expired");
                case 181:
                    landed[0] = result(server, "script run global_selftest_hits");
                    run(server, "carpet damageTickOverrides false");
                    run(server, "carpet damageTickOther 10");
                    return new Probe(false, "the fourth hit counted " + landed[0]);
                default:
                    return new Probe(unhandled[0] == 0 && second[0] == 1 && swallowed[0] == 1 && landed[0] == 2, fmt(
                            "__on_player_takes_damage counted %d hits with no handler, %d once it was loaded, %d fifteen ticks into the damageTickOther 60 window and %d after it expired",
                            unhandled[0], second[0], swallowed[0], landed[0]));
            }
        });
    }

    /** mixin Explosion_scarpetEventMixin: __on_explosion_outcome fires for a plain tnt explosion. */
    static Scenario scarpetExplosion(String a, Vec3 origin)
    {
        BlockPos spot = BlockPos.containing(origin);
        return new Scenario(600, List.of(new Bot(a, origin.add(0.0D, 0.0D, 6.0D))), List.of(
                forceload(spot),
                "carpet explosionNoBlockDamage false",
                "script run global_selftest_boom = 0",
                summonTnt(spot.east(2))), server ->
        {
            if (primed(server, spot) > 0) explosionPhase[2] = 1;
            if (explosionPhase[0] == 0)
            {
                if (explosionPhase[2] == 0 || primed(server, spot) > 0) return new Probe(false, "waiting for the first explosion");
                run(server, "script run __on_explosion_outcome(pos, power, source, causer, mode, fire, blocks, entities) -> global_selftest_boom = global_selftest_boom + 1");
                run(server, summonTnt(spot.east(2)));
                explosionPhase[0] = 1;
                explosionPhase[1] = ticks + 3;
                return new Probe(false, "second tnt primed with the handler loaded");
            }
            if (ticks < explosionPhase[1]) return new Probe(false, "waiting for the second explosion");
            if (primed(server, spot) > 0) return new Probe(false, "waiting for the second explosion");
            int after = result(server, "script run global_selftest_boom");
            return new Probe(after == 1, fmt("__on_explosion_outcome counted %d explosions, the first one without a handler", after));
        });
    }

    /** phase, earliest tick of the check, and whether the primed tnt has been seen at all */
    private static int[] explosionPhase = new int[3];

    /**
     * rules updateSuppressionBlock and lagFreeSpawning aside, updateSuppressionBlock (mixin
     * BarrierBlock_updateSuppressionBlockMixin, which reads the neighbour updater out of carpet.fakes.LevelInterface):
     * telling a barrier that has an unpowered activator rail above it that the block changed makes it schedule a tick
     * and lower the neighbour updater budget, while the same change with the rule off does neither.
     */
    static Scenario updateSuppressionBlock(Vec3 origin)
    {
        BlockPos quietBarrier = BlockPos.containing(origin);
        BlockPos liveBarrier = quietBarrier.east(4);
        return new Scenario(100, List.of(), List.of(
                forceload(quietBarrier),
                "carpet updateSuppressionBlock -1",
                setBlock(quietBarrier, "minecraft:barrier"),
                setBlock(quietBarrier.above(), "minecraft:activator_rail[powered=false]"),
                "carpet updateSuppressionBlock 0",
                setBlock(liveBarrier, "minecraft:barrier"),
                setBlock(liveBarrier.above(), "minecraft:activator_rail[powered=false]")), server ->
        {
            // both barriers were just told about the rail above them; only the one with the rule on schedules a tick
            ServerLevel level = server.overworld();
            boolean quiet = level.getBlockTicks().hasScheduledTick(quietBarrier, Blocks.BARRIER);
            boolean live = level.getBlockTicks().hasScheduledTick(liveBarrier, Blocks.BARRIER);
            return new Probe(!quiet && live, fmt(
                    "the barrier over an unpowered rail with updateSuppressionBlock off scheduled a tick: %s, with it at 0: %s",
                    quiet, live));
        });
    }

    /**
     * rule stackableShulkerBoxes (mixin ItemStack_stackableShulkerBoxesMixin): an empty shulker box may stack up to
     * the configured size while the rule is on, and any other block item is left alone.
     */
    static Scenario stackableShulkerBoxes(Vec3 origin)
    {
        int[] off = {-1, -1};
        int[] on = {-1, -1};
        return new Scenario(100, List.of(), List.of("carpet stackableShulkerBoxes false"), server ->
        {
            if (off[0] < 0)
            {
                off[0] = new ItemStack(Items.SHULKER_BOX).getMaxStackSize();
                off[1] = new ItemStack(Items.CHEST).getMaxStackSize();
                run(server, "carpet stackableShulkerBoxes 16");
                return new Probe(false, "with the rule off an empty shulker box stacks " + off[0] + ", a chest " + off[1]);
            }
            if (on[0] < 0)
            {
                on[0] = new ItemStack(Items.SHULKER_BOX).getMaxStackSize();
                on[1] = new ItemStack(Items.CHEST).getMaxStackSize();
                run(server, "carpet stackableShulkerBoxes false");
                return new Probe(off[0] == 1 && on[0] == 16 && on[1] == 64, fmt(
                        "an empty shulker box stacks %d with the rule off and %d with it at 16, a chest stacks %d",
                        off[0], on[0], on[1]));
            }
            return new Probe(false, "measuring");
        });
    }

    /**
     * rules structureBlockIgnored and structureBlockIgnoredBlock (mixin StructureBlockEntity_limitsMixin): the
     * configured block joins the list of blocks a structure block leaves out, so a saved structure drops it from the
     * palette once the rule names it.
     */
    static Scenario structureBlockIgnored(Vec3 origin)
    {
        BlockPos corner = BlockPos.containing(origin).above();
        return new Scenario(100, List.of(), List.of(
                forceload(corner),
                setBlock(corner, "minecraft:dirt")), server ->
        {
            if (ticks < 2) return new Probe(false, "preparing");
            run(server, "carpet structureBlockIgnored minecraft:structure_void");
            boolean defaultRule = paletteHas(server, "selftest_void", corner, "minecraft:dirt");
            String defaultPalette = paletteLog;
            run(server, "carpet structureBlockIgnored minecraft:dirt");
            boolean ignoreDirt = paletteHas(server, "selftest_dirt", corner, "minecraft:dirt");
            String dirtPalette = paletteLog;
            run(server, "carpet structureBlockIgnored minecraft:structure_void");
            return new Probe(defaultRule && !ignoreDirt, fmt(
                    "a saved one block structure keeps its dirt in the palette with the default rule: %s, and with the rule set to dirt: %s (%s)",
                    defaultRule, ignoreDirt, defaultPalette + " / " + dirtPalette));
        });
    }

    /**
     * rule persistentParrots (mixins Player_parrotMixin and ServerPlayer_parrotMixin): what sits on the shoulder
     * survives damage while the rule is on and is dropped while it is off.
     */
    static Scenario persistentParrots(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        // the tick the last step happened on: a fake player cannot be hurt until it counts as loaded, so the hits
        // wait for that instead of for a fixed tick
        int[] since = {0};
        return new Scenario(400, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 3.0D)), new Bot(c, origin.add(0.0D, 0.0D, 6.0D))), List.of(), server ->
        {
            switch (phase[0])
            {
                case 0:
                    shoulder(server, a);
                    shoulder(server, b);
                    phase[0] = 1;
                    return new Probe(false, "shoulder slots filled");
                case 1:
                    // removeEntitiesOnShoulder only clears a slot the parrot has sat in for a moment
                    if (ticks < 25 || !hittable(player(server, a))) return new Probe(false, "waiting for the shoulder timer");
                    run(server, "carpet persistentParrots false");
                    if (result(server, "damage " + a + " 0.2 minecraft:generic") < 1) return new Probe(false, a + " cannot be hurt yet");
                    phase[0] = 2;
                    since[0] = ticks;
                    return new Probe(false, "hit with the rule off");
                case 2:
                    if (ticks < since[0] + 5) return new Probe(false, "waiting for the hit to land");
                    shoulder(server, c);
                    phase[0] = 3;
                    since[0] = ticks;
                    return new Probe(false, "third shoulder slot filled");
                case 3:
                    if (ticks < since[0] + 30 || !hittable(player(server, c))) return new Probe(false, "waiting for the shoulder timer again");
                    run(server, "carpet persistentParrots true");
                    if (result(server, "damage " + c + " 0.2 minecraft:generic") < 1) return new Probe(false, c + " cannot be hurt yet");
                    phase[0] = 4;
                    since[0] = ticks;
                    return new Probe(false, "hit with the rule on");
                case 4:
                    if (ticks < since[0] + 5) return new Probe(false, "waiting for the second hit to land");
                    boolean dropped = player(server, a).getShoulderEntityLeft().isEmpty();
                    boolean kept = !player(server, c).getShoulderEntityLeft().isEmpty();
                    run(server, "carpet persistentParrots false");
                    return new Probe(dropped && kept, fmt(
                            "after being hit the shoulder slot of the bot with persistentParrots off is %s and of the one with it on is %s",
                            dropped ? "empty" : "taken", kept ? "taken" : "empty"));
                default:
                    return new Probe(false, "done");
            }
        });
    }

    /**
     * rule lagFreeSpawning (mixin NaturalSpawnerMixin, which reads the pre-cooked mob map out of
     * carpet.fakes.LevelInterface): the natural spawner keeps running while the rule is on, which cannot happen at
     * all while LevelInterface has no implementation. Spawning is mocked, so the reporter counts the attempts
     * without putting mobs into the world.
     */
    static Scenario lagFreeSpawning(String a, String b, Vec3 origin)
    {
        BlockPos area = BlockPos.containing(origin);
        long[] attempts = {-1};
        return new Scenario(400, List.of(new Bot(a, origin), new Bot(b, origin.add(3.0D, 0.0D, 0.0D))), List.of(
                forceload(area),
                "gamerule spawn_mobs true",
                "carpet lagFreeSpawning true",
                "spawn tracking start",
                "spawn mocking true"), server ->
        {
            long now = SpawnReporter.spawn_attempts.isEmpty() ? 0 : SpawnReporter.spawn_attempts.values().stream().mapToLong(Long::longValue).sum();
            if (attempts[0] < 0 && ticks < 200) return new Probe(false, "waiting for the spawner, " + now + " attempts so far");
            if (attempts[0] < 0)
            {
                attempts[0] = now;
                run(server, "spawn mocking false");
                run(server, "spawn tracking stop");
                run(server, "gamerule spawn_mobs false");
                return new Probe(attempts[0] > 0, fmt("the spawner made %d attempts with lagFreeSpawning on", attempts[0]));
            }
            return new Probe(false, "measuring");
        });
    }

    /**
     * rules interactionUpdates (mixin ServerGamePacketListenerImpl_interactionUpdatesMixin) and, on the same class,
     * scarpetItemUseEvents. A redstone block placed by a use-item-on packet lights the lamp next to it while
     * interactionUpdates is on and leaves it dark while it is off, because the rule holds
     * CarpetSettings.impendingFillSkipUpdates over the game mode call and the level then skips the neighbour update
     * and onPlace. The packet is handed to the bot's own connection so the listener, the game mode and the rule all
     * run for real; a fake player may not use anything until the client-load timer of its connection (60 ticks) has
     * run down, and that is what the first wait is for.
     */
    private static Scenario interactionUpdates(String a, Vec3 origin)
    {
        BlockPos quietLamp = BlockPos.containing(origin).south();
        BlockPos liveLamp = quietLamp.east(4);
        int[] phase = {0};
        int[] placed = {0};
        boolean[] quiet = {true, true};
        return new Scenario(200, List.of(new Bot(a, origin)), List.of(
                forceload(quietLamp),
                setBlock(quietLamp, "minecraft:redstone_lamp"),
                setBlock(liveLamp, "minecraft:redstone_lamp"),
                "player " + a + " equip mainhand minecraft:redstone_block",
                "carpet interactionUpdates false"), server ->
        {
            switch (phase[0])
            {
                case 0:
                    ServerPlayer bot = player(server, a);
                    if (!bot.connection.hasClientLoaded())
                        return pending(a + " may not use anything for another " + Math.max(0, 60 - ticks) + " ticks");
                    placeByPacket(server, a, quietLamp, Direction.EAST);
                    placed[0] = ticks;
                    phase[0] = 1;
                    return new Probe(false, "placed a redstone block with interactionUpdates off");
                case 1:
                    if (ticks < placed[0] + 3) return pending("waiting for the lamp placed with the rule off");
                    quiet[0] = lit(server, quietLamp);
                    run(server, "player " + a + " equip mainhand minecraft:redstone_block");
                    run(server, "carpet interactionUpdates true");
                    placeByPacket(server, a, liveLamp, Direction.EAST);
                    placed[0] = ticks;
                    phase[0] = 2;
                    return new Probe(false, "placed a redstone block with interactionUpdates on");
                case 2:
                    if (ticks < placed[0] + 3) return pending("waiting for the lamp placed with the rule on");
                    quiet[1] = lit(server, liveLamp);
                    run(server, "carpet interactionUpdates true");
                    return new Probe(!quiet[0] && quiet[1], fmt(
                            "the lamp next to a block placed with interactionUpdates off is %s and the one placed with it on is %s",
                            quiet[0] ? "lit" : "dark", quiet[1] ? "lit" : "dark"));
                default:
                    return new Probe(false, "done");
            }
        });
    }

    /**
     * rule punishWrongToolHits, registered on Fabric's AttackBlockCallback. That callback fires at the head of
     * ServerPlayerGameMode.handleBlockBreakAction, which only a mob gets that far in vanilla, so the packet a client
     * sends when it starts breaking a block is the way in. Hitting stone with bare hands costs a heart while the
     * rule is on and nothing at all while it is off. A fake player cannot be hurt for the 60 ticks its client-load
     * timer runs, and hurtTime has to be back to zero before the second hit is counted.
     */
    private static Scenario punishWrongToolHits(String a, Vec3 origin)
    {
        BlockPos spared = BlockPos.containing(origin).south();
        BlockPos doomed = spared.east(2);
        int[] phase = {0};
        float[] lost = {-1.0F, -1.0F};
        return new Scenario(300, List.of(new Bot(a, origin)), List.of(
                forceload(spared),
                setBlock(spared, "minecraft:stone"),
                setBlock(doomed, "minecraft:stone"),
                "carpet punishWrongToolHits true"), server ->
        {
            ServerPlayer bot = player(server, a);
            switch (phase[0])
            {
                case 0:
                    if (!hittable(bot)) return pending(hitWaitReason(bot));
                    float before = bot.getHealth();
                    hitBlock(server, a, spared);
                    lost[0] = before - bot.getHealth();
                    phase[0] = 1;
                    return new Probe(false, fmt("hit %s with bare hands with the rule on, lost %.1f health", spared, lost[0]));
                case 1:
                    if (bot.hurtTime > 0) return pending(a + " is still on the damage cooldown of the first hit");
                    run(server, "carpet punishWrongToolHits false");
                    before = bot.getHealth();
                    hitBlock(server, a, doomed);
                    lost[1] = before - bot.getHealth();
                    phase[0] = 2;
                    return new Probe(false, fmt("hit %s with bare hands with the rule off, lost %.1f health", doomed, lost[1]));
                case 2:
                    run(server, "carpet punishWrongToolHits false");
                    return new Probe(same(lost[0], 1.0F) && same(lost[1], 0.0F), fmt(
                            "hitting a block that needs a tool with bare hands cost %s %.1f health with punishWrongToolHits on and %.1f with it off",
                            a, lost[0], lost[1]));
                default:
                    return new Probe(false, "done");
            }
        });
    }

    /**
     * rule scarpetItemUseEvents (mixin ServerGamePacketListenerImpl_scarpetEventsMixin, which reads the rule before
     * handing an item use to a script). The action pack never sends packets, so the use has to arrive the way a client
     * sends it to reach the handler that reads the rule at all. With the rule on the app is asked and counts the use,
     * with it off the item goes on being used and the app is never called. The handle is a bow, so what the counts say
     * is the rule's doing and nothing else.
     */
    private static Scenario scarpetItemUseEvents(String a, Vec3 origin)
    {
        long[] on = {-1L};
        long[] off = {-1L};
        return new Scenario(300, List.of(new Bot(a, origin)), List.of(
                "carpet scarpetItemUseEvents true",
                "script run global_selftest_uses = 0",
                "script run __on_player_uses_item(player, hand, item) -> global_selftest_uses = global_selftest_uses + 1",
                "player " + a + " equip mainhand minecraft:bow"), server ->
        {
            ServerPlayer bot = player(server, a);
            if (on[0] < 0)
            {
                if (!bot.connection.hasClientLoaded())
                    return pending(a + " may not use anything for another " + Math.max(0, 60 - ticks) + " ticks");
                useItemByPacket(server, a);
                on[0] = result(server, "script run global_selftest_uses");
                run(server, "carpet scarpetItemUseEvents false");
                bot.releaseUsingItem();
                useItemByPacket(server, a);
                off[0] = result(server, "script run global_selftest_uses");
                run(server, "carpet scarpetItemUseEvents true");
                return new Probe(on[0] > 0 && off[0] == on[0], fmt(
                        "__on_player_uses_item counted %d uses with scarpetItemUseEvents on and stayed at %d with it off", on[0], off[0]));
            }
            return new Probe(false, "done");
        });
    }

    /**
     * rule sculkSensorRange (mixin SculkSensorBlockEntityVibrationConfig_sculkSensorRangeMixin, which answers
     * VibrationUser.getListenerRadius). The dispatcher only hands a game event to a listener whose radius covers it,
     * so a step twelve blocks away is out of reach at the default eight and inside the sixteen the rule sets, while
     * one twenty four blocks away stays out of reach either way. What is read is whether the sensor took the vibration
     * into its queue, which is the decision the radius makes: whether it goes on to arrive and make the sensor fire
     * also depends on the chunk ticking, which a forceloaded chunk with nobody in it does not always do. The sensors
     * are put back in place once their chunks are there, because a sensor that lands in a chunk the chunk map has not
     * got to yet gets no game event listener at all.
     */
    private static Scenario sculkSensorRange(String a, Vec3 origin)
    {
        int[] phase = {0};
        int[] stepped = {0};
        boolean[] quiet = {false, false};
        boolean[] live = {false, false};
        BlockPos sensor = BlockPos.containing(origin);
        BlockPos step = sensor.east(12);
        BlockPos beyond = sensor.east(36);
        return new Scenario(300, List.of(), List.of(
                forceload(sensor),
                forceload(beyond),
                setBlock(sensor, "minecraft:sculk_sensor"),
                setBlock(beyond, "minecraft:sculk_sensor")), server ->
        {
            ServerLevel level = server.overworld();
            switch (phase[0])
            {
                case 0:
                    if (ticks < 40) return pending(fmt("waiting for the forceloaded chunks, %d ticks to go", 40 - ticks));
                    for (BlockPos at : List.of(sensor, beyond))
                    {
                        run(server, setBlock(at, "minecraft:air"));
                        run(server, setBlock(at, "minecraft:sculk_sensor"));
                    }
                    step(level, step, a);
                    stepped[0] = ticks;
                    phase[0] = 1;
                    return new Probe(false, "a step 12 blocks from the sensor, sent at the default range");
                case 1:
                    if (ticks < stepped[0] + 5) return pending("waiting for the sensors to turn the vibration down");
                    quiet[0] = heard(level, sensor);
                    quiet[1] = heard(level, beyond);
                    run(server, "carpet sculkSensorRange 16");
                    step(level, step, a);
                    stepped[0] = ticks;
                    phase[0] = 2;
                    return new Probe(false, fmt("at the default range of 8 the sensor 12 blocks away took the vibration: %s, the one 36 blocks away: %s",
                            quiet[0], quiet[1]));
                case 2:
                    if (ticks < stepped[0] + 5) return pending("waiting for the sensors to turn the second vibration down");
                    live[0] = heard(level, sensor);
                    live[1] = heard(level, beyond);
                    run(server, "carpet sculkSensorRange 8");
                    return new Probe(!quiet[0] && !quiet[1] && live[0] && !live[1], fmt(
                            "a step 12 blocks from a sensor was taken over: %s and the one 36 blocks away %s at the default range of 8, and %s and %s at the range of 16 the rule sets",
                            quiet[0], quiet[1], live[0], live[1]));
                default:
                    return new Probe(false, "done");
            }
        });
    }

    /** Whether a sensor took a vibration into its queue, which is what the listener radius decides. */
    private static boolean heard(ServerLevel level, BlockPos sensor)
    {
        return level.getBlockEntity(sensor) instanceof SculkSensorBlockEntity sculk
                && sculk.getVibrationData().getSelectionStrategy().chosenCandidate(level.getGameTime()).isPresent();
    }

    /**
     * rule summonNaturalLightning (mixin SummonCommand_lightningMixin, which gives a summoned bolt the skeleton horse
     * roll that ServerLevel.tickThunder only runs for a storm). That horse is the whole of the difference, so the
     * scenario counts them, at two spots far enough apart that the counts cannot mix. The roll is one chance in
     * fifty to twenty, so the first spot sums up LIGHTNING_BOLTS of them: on hard in a fresh world the effective
     * difficulty is around 2.25 to 3, which puts the chance of missing every one of 600 rolls below one in a million,
     * and the second spot has to come up empty because vanilla never rolls at all. Both spots wait for the
     * forceloaded chunk to be there before a single bolt is summed, because entities only become countable once the
     * chunk map has picked the ticket up.
     */
    private static Scenario summonNaturalLightning(Vec3 origin)
    {
        BlockPos live = BlockPos.containing(origin);
        BlockPos quiet = live.east(32);
        int[] phase = {0};
        int[] struck = {0};
        int[] on = {-1};
        int[] off = {-1};
        return new Scenario(400, List.of(), List.of(
                forceload(live),
                forceload(quiet),
                "spawn mocking true",
                "difficulty hard",
                "gamerule spawn_mobs true",
                "carpet summonNaturalLightning true"), server ->
        {
            switch (phase[0])
            {
                case 0:
                    // entities in a forceloaded chunk only become countable once the chunk map has picked the
                    // ticket up, so a marker is summed up and waited for before any bolt is
                    if (count(server, net.minecraft.world.entity.decoration.ArmorStand.class, live, 8.0D) == 0)
                    {
                        if (ticks % 20 == 0) run(server, "summon minecraft:armor_stand " + live.getX() + " " + live.getY() + " " + live.getZ());
                        return pending("no entity can be counted in the forceloaded chunk yet");
                    }
                    strikeLightning(server, live, LIGHTNING_BOLTS);
                    struck[0] = ticks;
                    phase[0] = 1;
                    return new Probe(false, LIGHTNING_BOLTS + " bolts summed with the rule on");
                case 1:
                    if (ticks < struck[0] + 10) return pending("letting the horses settle");
                    on[0] = trapHorses(server, live, 8.0D);
                    run(server, "carpet summonNaturalLightning false");
                    strikeLightning(server, quiet, LIGHTNING_BOLTS / 30);
                    struck[0] = ticks;
                    phase[0] = 2;
                    return new Probe(false, fmt("%d of the %d bolts with the rule on made a trap horse, %d summed at the other spot with it off",
                            on[0], LIGHTNING_BOLTS, LIGHTNING_BOLTS / 30));
                case 2:
                    if (ticks < struck[0] + 10) return pending("letting the second lot settle");
                    off[0] = trapHorses(server, quiet, 8.0D);
                    run(server, "carpet summonNaturalLightning true");
                    run(server, "difficulty peaceful");
                    run(server, "spawn mocking false");
                    return new Probe(on[0] > 0 && off[0] == 0, fmt(
                            "%d of the %d bolts summed with summonNaturalLightning on made a skeleton horse trap and %d of the %d with it off did",
                            on[0], LIGHTNING_BOLTS, off[0], LIGHTNING_BOLTS / 30));
                default:
                    return new Probe(false, "done");
            }
        });
    }

    /**
     * carpet.helpers.OptimizedExplosion, whose caches are static: an explosion that computes no block positions, the
     * branch explosionNoBlockDamage takes, must not leave anything queued for the next block-damaging one, or that
     * one blows up the blocks of the previous one as well. Nothing in the game can leave the position set dirty - the
     * branch that fills it also empties it - so the scenario queues one leftover block the way a leaving explosion
     * would, and the same two blasts explosion_rules makes check that the block is still standing afterwards.
     */
    private static Scenario explosionStateLeak(String a, Vec3 origin)
    {
        BlockPos spared = BlockPos.containing(origin);
        BlockPos doomed = spared.east(16);
        return new Scenario(600, List.of(new Bot(a, origin.add(0.0D, 0.0D, 6.0D))), List.of(
                forceload(spared),
                "carpet optimizedTNT true",
                "carpet explosionNoBlockDamage true",
                setBlock(spared, "minecraft:stone"),
                setBlock(doomed, "minecraft:stone"),
                summonTnt(spared.east(2))), server ->
        {
            if (explosionLeakPhase[2] == 0 && primed(server, spared) > 0) explosionLeakPhase[2] = 1;
            if (explosionLeakPhase[0] == 0)
            {
                if (explosionLeakPhase[2] == 0 || primed(server, spared) > 0) return pending("waiting for the first explosion");
                explosionLeakPhase[0] = 1;
                explosionLeakPhase[1] = ticks + 3;
                queueLeftoverPositions(spared);
                run(server, "carpet explosionNoBlockDamage false");
                run(server, summonTnt(doomed.east(2)));
                return new Probe(false, "second tnt primed with the rule off, a leftover position queued");
            }
            if (ticks < explosionLeakPhase[1] || primed(server, spared) > 0) return pending("waiting for the second explosion");
            BlockState sparedState = server.overworld().getBlockState(spared);
            BlockState doomedState = server.overworld().getBlockState(doomed);
            run(server, "carpet explosionNoBlockDamage false");
            return new Probe(sparedState.is(Blocks.STONE) && doomedState.isAir(), fmt(
                    "the stone an earlier explosion left queued is %s and the stone the second explosion stands on is %s",
                    sparedState, doomedState));
        });
    }

    /** phase, earliest tick of the check, and whether the primed tnt has been seen at all */
    private static int[] explosionLeakPhase = new int[3];

    /**
     * A Scarpet app that saves the world data and reads it back. The helpers behind that used to cast to
     * carpet.fakes.ServerWorldInterface, which nothing implements, so anything that touched them threw a
     * ClassCastException instead of doing its work; ServerWorldInterfaceTest pins that the cast is gone, and this
     * scenario goes through /script run to show that saving world data works on both versions.
     */
    private static Scenario scarpetWorldData()
    {
        int[] saved = {-1};
        int[] readBack = {-1};
        return new Scenario(100, List.of(), List.of("script run global_selftest_save = 0"), server ->
        {
            if (saved[0] < 0)
            {
                saved[0] = result(server, "script run global_selftest_save = save()");
                readBack[0] = result(server, "script run global_selftest_save");
                return new Probe(false, fmt("save() returned %d", saved[0]));
            }
            return new Probe(saved[0] == 1 && readBack[0] == 1, fmt(
                    "an app that saves the world data got %d out of save() and read %d back", saved[0], readBack[0]));
        });
    }

    /**
     * rule tickSyncedWorldBorders (mixin WorldBorder_syncedWorldBorderMixin, which swaps in
     * carpet.patches.TickSyncedBorderExtent): the vanilla moving border measures its lerp against the wall clock, the
     * ticked one against game ticks. The run is at 40 ticks a second here, so BORDER_WAIT game ticks are longer than
     * BORDER_DURATION game ticks of game time: the ticked border has finished its lerp by then and the vanilla one
     * has barely started. The border is put back and left to grow back before the check answers.
     */
    private static Scenario tickSyncedWorldBorders()
    {
        int[] phase = {0};
        int[] started = {0};
        double[] size = {-1.0D, -1.0D};
        return new Scenario(900, List.of(), List.of(
                "carpet tickSyncedWorldBorders true",
                "tick rate 40"), server ->
        {
            WorldBorder border = server.overworld().getWorldBorder();
            switch (phase[0])
            {
                case 0:
                    border.lerpSizeBetween(BORDER_FROM, BORDER_TO, BORDER_DURATION_MILLIS, Util.getMillis());
                    started[0] = ticks;
                    phase[0] = 1;
                    return new Probe(false, fmt("started a %.0f second border lerp from %.0f to %.0f at 40 ticks a second",
                            BORDER_DURATION_MILLIS / 1000.0D, BORDER_FROM, BORDER_TO));
                case 1:
                    if (ticks < started[0] + BORDER_WAIT)
                        return pending(fmt("%d of the %d ticks to wait are left", started[0] + BORDER_WAIT - ticks, BORDER_WAIT));
                    size[0] = border.getSize();
                    run(server, "carpet tickSyncedWorldBorders false");
                    border.lerpSizeBetween(BORDER_FROM, BORDER_TO, BORDER_DURATION_MILLIS, Util.getMillis());
                    started[0] = ticks;
                    phase[0] = 2;
                    return new Probe(false, fmt("with the rule on the border is %.1f after %d game ticks", size[0], BORDER_WAIT));
                case 2:
                    if (ticks < started[0] + BORDER_WAIT)
                        return pending(fmt("%d of the %d ticks to wait are left", started[0] + BORDER_WAIT - ticks, BORDER_WAIT));
                    size[1] = border.getSize();
                    border.setSize(WorldBorder.MAX_SIZE);
                    phase[0] = 3;
                    return new Probe(false, fmt("with the rule off the border is %.1f after %d game ticks, putting it back", size[1], BORDER_WAIT));
                case 3:
                    if (border.getSize() < BORDER_FROM * 1000.0D) return pending("letting the border grow back");
                    run(server, "tick sprint 1d");
                    return new Probe(Math.abs(size[0] - BORDER_TO) < 0.5D && size[1] > BORDER_FROM / 2.0D, fmt(
                            "after %d game ticks at 40 ticks a second a %.0f second lerp from %.0f to %.0f read %.1f with tickSyncedWorldBorders on and %.1f with it off",
                            BORDER_WAIT, BORDER_DURATION_MILLIS / 1000.0D, BORDER_FROM, BORDER_TO, size[0], size[1]));
                default:
                    return new Probe(false, "done");
            }
        });
    }

    static void conclude(MinecraftServer server, boolean passed, String detail)
    {
        // Whatever a scenario changed in the rules goes back now, so that one which failed or ran out of time
        // cannot hand the next one a server the run was not written against.
        String restored = RuleGuard.leave(server);
        // the two scenarios that need mobs to spawn turn this on for themselves
        run(server, "gamerule spawn_mobs false");
        Result result = new Result(names.get(results.size()), passed, ticks,
                restored.isEmpty() ? detail : detail + "; rules put back: " + restored);
        results.add(result);
        log(server, fmt("%s %s after %d ticks: %s", passed ? "PASS" : "FAIL", result.name(), result.ticks(), detail));
    }

    /**
     * The scenarios leave tens of thousands of chunks on their way out, and a sprinting server has no spare time
     * to unload them in, so they pile up. Vanilla runs every queued chunk task inline once it is stopping, one
     * stack frame set per task, and overflows its stack on a queue that long ("Exception stopping the server").
     * A server at its normal tick rate does not get there (checked with thirty bots online, 400 blocks apart),
     * so the run stops sprinting and gives the server a few seconds of ordinary ticks before it stops.
     */
    static boolean drained(MinecraftServer server)
    {
        if (drainTicks++ == 0)
        {
            run(server, "forceload remove all");
            run(server, "tick sprint stop");
        }
        int loaded = server.overworld().getChunkSource().getLoadedChunksCount();
        if ((drainTicks < DRAIN_MIN_TICKS || loaded > DRAINED_CHUNKS) && drainTicks < DRAIN_TIMEOUT_TICKS) return false;
        log(server, fmt("%d chunks still loaded %d ticks after the last scenario", loaded, drainTicks));
        return true;
    }

    static void finish(MinecraftServer server)
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
    static void watchExit(Thread serverThread, int exitCode)
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
    static String trackedPlayers(ServerLevel level)
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

    static List<Thread> lingeringThreads()
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
    static ServerPlayer player(MinecraftServer server, String name)
    {
        return server.getPlayerList().getPlayerByName(name);
    }

    static void run(MinecraftServer server, String command)
    {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** Issues a command and reads back its result, the way the console reports success. */
    static int result(MinecraftServer server, String command)
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

    static String forceload(BlockPos pos)
    {
        return "forceload add " + pos.getX() + " " + pos.getZ();
    }

    static String setBlock(BlockPos pos, String block)
    {
        return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block;
    }

    static String summonTnt(BlockPos pos)
    {
        return "summon tnt " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    static String setBlock(int x, int y, int z, String block)
    {
        return "setblock " + x + " " + y + " " + z + " " + block;
    }

    /** Keeps a stretch of chunks loaded, so navigation has blocks to plan over before the bot walks there. */
    static String forceload(int x0, int z0, int x1, int z1)
    {
        return "forceload add " + x0 + " " + z0 + " " + x1 + " " + z1;
    }

    static String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block)
    {
        return "fill " + x0 + " " + y0 + " " + z0 + " " + x1 + " " + y1 + " " + z1 + " " + block;
    }

    /** The action pack behind a bot, which is where its navigation state is read from. */
    static EntityPlayerActionPack pack(MinecraftServer server, String name)
    {
        return ((ServerPlayerInterface) player(server, name)).getActionPack();
    }

    static boolean lit(MinecraftServer server, BlockPos pos)
    {
        return server.overworld().getBlockState(pos).getValue(RedstoneLampBlock.LIT);
    }

    /** Runs a command as if it came from the given position, which nav come navigates to. */
    static void run(MinecraftServer server, String command, Vec3 sourcePos)
    {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPosition(sourcePos), command);
    }

    static Probe pending(String detail)
    {
        return new Probe(false, detail);
    }

    /** A hit counts in full only when the player is loaded, off the damage cooldown of the last one and healthy. */
    static boolean hittable(ServerPlayer... players)
    {
        for (ServerPlayer player : players)
        {
            // a fake player cannot be hurt while its connection is still counted as loading
            if (!player.connection.hasClientLoaded()) return false;
            if (player.hurtTime > 0 || player.getHealth() <= SWORD_BLOCK_HIT) return false;
        }
        return true;
    }

    static String hitWaitReason(ServerPlayer... players)
    {
        for (ServerPlayer player : players)
        {
            if (!player.connection.hasClientLoaded()) return player.getName().getString() + " is still loading";
            if (player.getHealth() <= SWORD_BLOCK_HIT) return player.getName().getString() + " has too little health left";
        }
        return "the last hit is still on cooldown";
    }

    /** Hits a player for a fixed amount and returns what it cost them in health. */
    static float damage(MinecraftServer server, ServerPlayer victim)
    {
        float before = victim.getHealth();
        result(server, "damage " + victim.getName().getString() + " " + SWORD_BLOCK_HIT);
        return before - victim.getHealth();
    }

    static boolean same(float one, float other)
    {
        return Math.abs(one - other) < 0.05F;
    }

    static int primed(MinecraftServer server, BlockPos pos)
    {
        return count(server, net.minecraft.world.entity.item.PrimedTnt.class, pos, 48.0D);
    }

    static <T extends Entity> int count(MinecraftServer server, Class<T> type, BlockPos pos, double radius)
    {
        return server.overworld().getEntitiesOfClass(type, new AABB(pos).inflate(radius)).size();
    }

    /** The biggest shulker box stack lying on the ground within four blocks of the drop point. */
    static int biggestShulkerStack(MinecraftServer server, Vec3 pos)
    {
        int biggest = 0;
        for (ItemEntity item : server.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(pos, pos).inflate(4.0D)))
        {
            if (item.getItem().is(Items.SHULKER_BOX)) biggest = Math.max(biggest, item.getItem().getCount());
        }
        return biggest;
    }

    /** Fills the fake player's left shoulder slot; the rule only ever looks at whether the slot is taken. */
    static void shoulder(MinecraftServer server, String name)
    {
        CompoundTag parrot = new CompoundTag();
        parrot.putString("id", "minecraft:parrot");
        player(server, name).setEntityOnShoulder(parrot);
    }

    /** Saves a one block structure and reports whether air made it into the saved palette. */
    /**
     * Saves a one block structure and reports whether the given block made it into the palette that was written to
     * disk. The file is looked up by name because the save path and the load path of the structure manager disagree
     * about which folder they use.
     */
    static boolean paletteHas(MinecraftServer server, String name, BlockPos corner, String block)
    {
        ServerLevel level = server.overworld();
        Identifier id = Identifier.withDefaultNamespace("selftest_" + name);
        boolean stored = StructureBlockEntity.saveStructure(level, id, corner, new Vec3i(3, 3, 3), false, "selftest", true, List.of());
        CompoundTag saved;
        try (var files = Files.walk(Path.of("world", "generated"), 4))
        {
            Path file = files.filter(path -> path.getFileName().toString().equals(id.getPath() + ".nbt")).findFirst()
                    .orElseThrow(() -> new IOException("no file for " + id));
            saved = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            paletteLog = "stored=" + stored + " palette=" + saved.getListOrEmpty(StructureTemplate.PALETTE_TAG);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("could not read back the structure " + id + " (stored=" + stored + ")", e);
        }
        for (Tag entry : saved.getListOrEmpty(StructureTemplate.PALETTE_TAG))
        {
            // 26.3 writes the block id under "id", 26.2 still spells it "Name"
            if (entry instanceof CompoundTag state && (state.getString("id").orElse(state.getString("Name").orElse(""))).equals(block)) return true;
        }
        return false;
    }

    /** A use-item-on packet the way a client sends it, so the packet handler and the rules it reads run. */
    static void placeByPacket(MinecraftServer server, String name, BlockPos against, Direction face)
    {
        ServerPlayer bot = player(server, name);
        Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5D, 0.0D, face.getStepZ() * 0.5D);
        bot.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, against, false), 0));
    }

    /** A use-item packet the way a client sends it. */
    private static void useItemByPacket(MinecraftServer server, String name)
    {
        ServerPlayer bot = player(server, name);
        bot.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, bot.getYRot(), bot.getXRot()));
    }

    /** The start of a block break, the way a client sends it. */
    private static void hitBlock(MinecraftServer server, String name, BlockPos pos)
    {
        player(server, name).connection.handlePlayerAction(
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP, 0));
    }

    /** Posts a step game event where a footstep of the named fake player would. */
    private static void step(ServerLevel level, BlockPos at, String source)
    {
        level.gameEvent(GameEvent.STEP, Vec3.atCenterOf(at), GameEvent.Context.of(player(level.getServer(), source)));
    }

    /** The frequency of the last vibration a sensor heard, kept until it is reloaded. */
    private static int frequency(ServerLevel level, BlockPos sensor)
    {
        return level.getBlockEntity(sensor) instanceof SculkSensorBlockEntity sculk ? sculk.getLastVibrationFrequency() : -1;
    }

    /** Sums up the given number of lightning bolts, the way an operator does with /summon. */
    private static void strikeLightning(MinecraftServer server, BlockPos at, int count)
    {
        for (int i = 0; i < count; i++)
        {
            run(server, "summon minecraft:lightning_bolt " + at.getX() + " " + at.getY() + " " + at.getZ());
        }
    }

    /** The skeleton horses with the trap flag near a spot, which only natural lightning spawns. */
    private static int trapHorses(MinecraftServer server, BlockPos near, double radius)
    {
        int traps = 0;
        for (SkeletonHorse horse : server.overworld().getEntitiesOfClass(SkeletonHorse.class, new AABB(near).inflate(radius)))
        {
            if (horse.isTrap()) traps++;
        }
        return traps;
    }

    /**
     * Puts a block into the position set carpet.helpers.OptimizedExplosion carries from one explosion to the next, the
     * way an explosion that skipped the walk would. Read and written by reflection, the way the watchdog reads the
     * player tracker: nothing outside the helper has any business there.
     */
    private static void queueLeftoverPositions(BlockPos leftover)
    {
        try
        {
            Field field = OptimizedExplosion.class.getDeclaredField("affectedBlockPositionsSet");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Collection<BlockPos> positions = (Collection<BlockPos>) field.get(null);
            positions.clear();
            positions.add(leftover.immutable());
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("could not reach the explosion position cache", e);
        }
    }

    static void log(MinecraftServer server, String message)
    {
        server.sendSystemMessage(Component.literal("[selftest] " + message));
    }

    /** Average wall-clock time between two self-test ticks of the budget scenario, milliseconds. */
    static double tickMillis()
    {
        return budgetTickNanosTotal / 1000000.0D / (budgetWatch - 1);
    }

    static String coords(Vec3 pos)
    {
        return pos.x + " " + pos.y + " " + pos.z;
    }

    static String fmt(String format, Object... args)
    {
        return String.format(Locale.ROOT, format, args);
    }
}
