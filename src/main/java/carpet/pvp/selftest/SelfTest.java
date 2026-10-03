package carpet.pvp.selftest;

import carpet.logic.CarpetLogic;
import carpet.logic.program.BotAction;
import carpet.logic.program.BotProgram;
import carpet.logic.program.ProgramExecutor.ProgramInfo;
import carpet.logic.web.Api;
import carpet.logic.web.AuthManager;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import carpet.utils.SpawnReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
            "structure_block_ignored", "persistent_parrots", "lag_free_spawning", "logic_bot_snapshot");

    /** What every built-in kit has to put on the player it is given to. */
    private record KitExpectation(String kit, String mainHand, String chestplate, String enchantment, int level, String stack, int count) {}

    private static final List<KitExpectation> KIT_EXPECTATIONS = List.of(
            new KitExpectation("sword", "diamond_sword", "diamond_chestplate", "minecraft:protection", 4, "golden_apple", 4),
            new KitExpectation("axe", "diamond_sword", "diamond_chestplate", "minecraft:protection", 4, "golden_apple", 4),
            new KitExpectation("smp", "netherite_sword", "netherite_chestplate", "minecraft:protection", 4, "experience_bottle", 16),
            new KitExpectation("mace", "mace", "netherite_chestplate", "minecraft:protection", 4, "wind_charge", 16),
            new KitExpectation("crystal", "netherite_sword", "netherite_chestplate", "minecraft:blast_protection", 4, "end_crystal", 8));

    private static final String REQUESTED = System.getProperty("carpet.selftest");
    private static final double SURFACE_Y = -60.0D;
    private static final double SPACING = 256.0D;
    private static final Gson GSON = new Gson();
    /** How long the server thread gets to stop, and then how long its worker threads get to finish. */
    private static final long EXIT_WAIT_MILLIS = 120_000L;
    private static volatile MinecraftServer stoppingServer;
    private static final float SWORD_BLOCK_HIT = 4.0F;

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
    /** A session that was never issued to a player, as /carpetlogic from the console makes. */
    private static final AuthManager.Session CONSOLE_SESSION = new AuthManager.Session(null, "Server", Long.MAX_VALUE);
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
        String c = "SelfC" + index;
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

    /** The first slot where the two lists differ, or null when they hold the same things. */
    private static String sameInventory(List<ItemStack> expected, List<ItemStack> actual)
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

    private static Probe kitsGiven(MinecraftServer server, List<String> bots)
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

    private static String checkKit(ServerPlayer bot, KitExpectation expected, MinecraftServer server)
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
    private static List<ItemStack> slots(ServerPlayer player)
    {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) slots.add(player.getInventory().getItem(i).copy());
        for (EquipmentSlot slot : EquipmentSlot.values())
        {
            if (slot.isArmor() || slot == EquipmentSlot.OFFHAND) slots.add(player.getItemBySlot(slot).copy());
        }
        return slots;
    }

    private static boolean holds(ItemStack stack, String item)
    {
        Identifier key = Identifier.withDefaultNamespace(item);
        return !stack.isEmpty() && BuiltInRegistries.ITEM.containsKey(key) && stack.getItem() == BuiltInRegistries.ITEM.getValue(key);
    }

    private static String describe(ItemStack stack)
    {
        return stack.isEmpty() ? "nothing" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * rules explosionNoBlockDamage and optimizedTNT (mixin Explosion_optimizedTntMixin, which hands the explosion to
     * carpet.helpers.OptimizedExplosion and that casts it to ExplosionAccessor): a primed tnt leaves the dirt next
     * to it standing while explosionNoBlockDamage is on and blows it away while it is off. optimizedTNT is on
     * throughout, so both blasts go through the optimized path, and each scenario waits for the primed tnt to be
     * gone before it looks at the blocks.
     */
    private static Scenario explosionRules(String a, Vec3 origin)
    {
        BlockPos spared = BlockPos.containing(origin);
        BlockPos doomed = spared.east(16);
        // a fake player nearby is what makes the chunk tick entities, and a primed tnt only counts down there
        return new Scenario(600, List.of(new Bot(a, origin.add(0.0D, 0.0D, 6.0D))), List.of(
                forceload(spared),
                "carpet optimizedTNT true",
                "carpet explosionNoBlockDamage true",
                setBlock(spared, "minecraft:dirt"),
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
                run(server, setBlock(doomed, "minecraft:dirt"));
                run(server, summonTnt(doomed.east(2)));
                return new Probe(false, "second tnt primed with the rule off");
            }
            if (ticks < explosionRulesPhase[1]) return new Probe(false, "waiting for the second explosion");
            if (primed(server, spared) > 0) return new Probe(false, "waiting for the second explosion");
            BlockState sparedState = server.overworld().getBlockState(spared);
            BlockState doomedState = server.overworld().getBlockState(doomed);
            return new Probe(sparedState.is(Blocks.DIRT) && doomedState.isAir(), fmt(
                    "the dirt next to a tnt primed with explosionNoBlockDamage on is %s and the one with it off is %s",
                    sparedState, doomedState));
        });
    }

    /**
     * rule xpFromExplosions (mixin Explosion_xpFromBlocksMixin): an ore block blown up only drops experience while
     * the rule is on.
     */
    private static Scenario xpExplosions(String a, Vec3 origin)
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
    private static Scenario scarpetEvents(String b, Vec3 origin)
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
    private static Scenario scarpetExplosion(String a, Vec3 origin)
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
    private static Scenario updateSuppressionBlock(Vec3 origin)
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
    private static Scenario stackableShulkerBoxes(Vec3 origin)
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
    private static Scenario structureBlockIgnored(Vec3 origin)
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
    private static Scenario persistentParrots(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        return new Scenario(200, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 3.0D)), new Bot(c, origin.add(0.0D, 0.0D, 6.0D))), List.of(), server ->
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
                    if (ticks < 25) return new Probe(false, "waiting for the shoulder timer");
                    run(server, "carpet persistentParrots false");
                    run(server, "damage " + a + " 0.2 minecraft:generic");
                    phase[0] = 2;
                    return new Probe(false, "hit with the rule off");
                case 2:
                    if (ticks < 30) return new Probe(false, "waiting for the hit to land");
                    shoulder(server, c);
                    phase[0] = 3;
                    return new Probe(false, "third shoulder slot filled");
                case 3:
                    if (ticks < 60) return new Probe(false, "waiting for the shoulder timer again");
                    run(server, "carpet persistentParrots true");
                    run(server, "damage " + c + " 0.2 minecraft:generic");
                    phase[0] = 4;
                    return new Probe(false, "hit with the rule on");
                case 4:
                    if (ticks < 65) return new Probe(false, "waiting for the second hit to land");
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
    private static Scenario lagFreeSpawning(String a, String b, Vec3 origin)
    {
        BlockPos area = BlockPos.containing(origin);
        long[] attempts = {-1};
        return new Scenario(400, List.of(new Bot(a, origin), new Bot(b, origin.add(3.0D, 0.0D, 0.0D))), List.of(
                forceload(area),
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
                return new Probe(attempts[0] > 0, fmt("the spawner made %d attempts with lagFreeSpawning on", attempts[0]));
            }
            return new Probe(false, "measuring");
        });
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

    private static String forceload(BlockPos pos)
    {
        return "forceload add " + pos.getX() + " " + pos.getZ();
    }

    private static String setBlock(BlockPos pos, String block)
    {
        return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block;
    }

    private static String summonTnt(BlockPos pos)
    {
        return "summon tnt " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
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

    private static int primed(MinecraftServer server, BlockPos pos)
    {
        return count(server, net.minecraft.world.entity.item.PrimedTnt.class, pos, 48.0D);
    }

    private static <T extends Entity> int count(MinecraftServer server, Class<T> type, BlockPos pos, double radius)
    {
        return server.overworld().getEntitiesOfClass(type, new AABB(pos).inflate(radius)).size();
    }

    /** The biggest shulker box stack lying on the ground within four blocks of the drop point. */
    private static int biggestShulkerStack(MinecraftServer server, Vec3 pos)
    {
        int biggest = 0;
        for (ItemEntity item : server.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(pos, pos).inflate(4.0D)))
        {
            if (item.getItem().is(Items.SHULKER_BOX)) biggest = Math.max(biggest, item.getItem().getCount());
        }
        return biggest;
    }

    /** Fills the fake player's left shoulder slot; the rule only ever looks at whether the slot is taken. */
    private static void shoulder(MinecraftServer server, String name)
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
    private static boolean paletteHas(MinecraftServer server, String name, BlockPos corner, String block)
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
    private static void placeByPacket(MinecraftServer server, String name, BlockPos against, Direction face)
    {
        ServerPlayer bot = player(server, name);
        Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5D, 0.0D, face.getStepZ() * 0.5D);
        bot.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, against, false), 0));
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
