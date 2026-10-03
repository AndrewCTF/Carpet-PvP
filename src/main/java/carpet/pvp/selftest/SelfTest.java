package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTestReport.Result;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitEntry;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
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
    private static final List<String> SCENARIOS = List.of("spawn", "nav_goto", "nav_follow", "chase_attack", "chase_crit", "kit_give", "kit_roundtrip");

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
            default:
                return null;
        }
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
        int exitCode = passed ? 0 : 1;
        // halt(), not exit(): exiting runs MinecraftServer's shutdown hook, which stops the server a
        // second time while its own thread is already inside stopServer waiting for the next tick,
        // and the run then never ends. The report is written and the test world is thrown away, so
        // there is nothing left worth shutting down in an orderly fashion.
        Runtime.getRuntime().halt(exitCode);
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
