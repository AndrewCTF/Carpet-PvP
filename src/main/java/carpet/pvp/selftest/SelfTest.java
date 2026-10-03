package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTestReport.Result;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
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
import net.minecraft.world.InteractionHand;
import carpet.utils.SpawnReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import java.util.function.Function;

/**
 * Headless self-test. Started with {@code -Dcarpet.selftest=<names|all>}, the server runs scripted fake-player
 * scenarios from its tick loop, writes {@code selftest-report.json} and exits with 0 (all passed) or 1.
 * Expects a flat world. Uses only Minecraft and JDK types, so it is not tied to one mod loader.
 */
public final class SelfTest
{
    private static final List<String> SCENARIOS = List.of("spawn", "nav_goto", "nav_follow", "chase_attack", "chase_crit", "script_run", "fill_updates",
            "explosion_rules", "xp_explosions", "scarpet_events", "scarpet_explosion", "update_suppression_block",
            "stackable_shulker_boxes", "structure_block_ignored", "persistent_parrots", "lag_free_spawning");

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
        String c = "SelfC" + index;
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
