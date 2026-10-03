package carpet.pvp.selftest;

import carpet.logic.CarpetLogic;
import carpet.logic.program.BotAction;
import carpet.logic.program.BotProgram;
import carpet.logic.program.ProgramExecutor.ProgramInfo;
import carpet.pvp.selftest.SelfTestReport.Result;
import carpet.CarpetSettings;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitEntry;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.storage.LevelResource;
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
            "spawn_exact_name", "spawn_gamemode", "animate_use", "item_cd", "shield_disable", "kit_give",
            "kit_roundtrip", "kit_folder", "sword_block", "kill");

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
            default:
                return null;
        }
    }

    /** The hand the player's current swing is with, or null while it is not swinging. */
    private static InteractionHand swingHand(ServerPlayer player)
    {
        //? if >=26.3 {
        LivingEntity.SwingDescription swing = player.getCurrentSwing();
        return swing == null ? null : swing.hand();
        //?} else {
        /*return player.swinging ? player.swingingArm : null;
        *///?}
    }

    private static String handName(InteractionHand hand)
    {
        return hand == InteractionHand.OFF_HAND ? "off" : "main";
    }

    /** Puts the two shapes of kit file into the world's kit folder, the way a server admin would. */
    private static String writeKitFiles(MinecraftServer server)
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
    private static String checkKit(ServerPlayer bot, String kit, RegistryAccess registries)
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
