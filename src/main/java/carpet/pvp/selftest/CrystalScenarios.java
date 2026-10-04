package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotStats;
import carpet.pvp.crystal.LevelExplosionView;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import carpet.pvp.sim.Box;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.CrystalPlacement;
import carpet.pvp.sim.SeenPercent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The crystal style on a real server: what a blast costs an armoured fighter, that the bot puts a crystal
 * down itself and sets it off a click later, that it walks away from its own, that it puts a totem back,
 * and that two of them fighting comes out the way the difficulty order says it should.
 *
 * <p>Every arena is bedrock, which no blast in these scenarios breaks, so a scenario that measures an
 * explosion measures the explosion and not the hole it left. The self-test server starts on peaceful,
 * where {@code Player.hurtServer} takes the whole of a blast away, so every scenario that fights with
 * blasts turns the difficulty up and puts it back before it finishes. Every fighter waits for
 * {@link SelfTest#warmingUp} before the first hit lands, every bot a scenario spawns itself is taken off
 * the server again, and each probe counts its own ticks.</p>
 */
final class CrystalScenarios
{
    /** The layer the arenas are floored with, so nothing underfoot is hollow. */
    private static final int FLOOR = -62;
    /** The layer the arenas are walked on. */
    private static final int SURFACE = -61;
    /** The layer the pit of the suicide scenario is floored with, a layer below an arena's. */
    private static final int PIT_FLOOR = -62;
    /** Health a blast costs within rounding of what the crystal model computes. */
    private static final float TOLERANCE = 0.05F;
    /** Rounds an expert has to win out of {@link #ROUNDS} against a beginner. */
    private static final int ROUNDS = 5;
    private static final int ROUNDS_TO_WIN = 4;
    /** Ticks one round of the crystal style may take. */
    private static final int ROUND_TICKS = 400;
    /** Ticks the two experts may take to pop the first totem between them. */
    private static final int EXPERT_TICKS = 1400;
    /** Ticks a probe waits on a bot that has stopped making progress before it calls it stalled. */
    private static final int STALL_TICKS = 400;
    /**
     * Ticks after the first anchor is clicked that the scenario waits for one of them to cost the fighter
     * something: one blast is a tick of the action pack's queue and a few more, and the bot may have to walk
     * in and charge another before it finds one that reaches.
     */
    private static final int BLOWN_WAIT = 400;
    /** Ticks the suicide scenario watches a bot refuse for once it has refused the first blast. */
    private static final int WATCHED_TICKS = 200;

    /**
     * {@code Player.hurtServer} scales an explosion by the difficulty and takes all of it away on peaceful,
     * which is where the self-test server starts, so a scenario that measures or fights with blasts turns
     * it up and puts it back before it finishes.
     */
    private static final String HURTING = "difficulty normal";
    private static final String QUIET = "difficulty peaceful";

    private CrystalScenarios() {}

    // ===== kits and arenas =====

    /**
     * The kit a crystal fighter fights with, which is the one {@code /bot spawn <name> crystal} hands out.
     * Its armour carries blast protection, which is both what makes a blast survivable from close by and
     * what the safety rule has to be told about.
     */
    private static List<String> crystalKit(String name)
    {
        return List.of("bot kit give " + name + " crystal");
    }

    /** Turns a fake player into a crystal fighter of the given difficulty. */
    private static List<String> crystalCombat(String name, String difficulty)
    {
        return List.of("bot option " + name + " combatstyle crystal",
                "bot option " + name + " difficulty " + difficulty,
                "bot option " + name + " combat true");
    }

    /**
     * What an anchor fighter is given: a sword, anchors to put down, glowstone to charge them and armour that
     * can take its own blast. No end crystal and no obsidian, because then the anchor is the only blast this
     * scenario can watch, which is what it is about.
     */
    private static List<String> anchorKit(String name)
    {
        return List.of("give " + name + " minecraft:netherite_sword",
                "give " + name + " minecraft:respawn_anchor 16",
                "give " + name + " minecraft:glowstone 16",
                SelfTest.cmd(name + " equip head minecraft:netherite_helmet"),
                SelfTest.cmd(name + " equip chest minecraft:netherite_chestplate"),
                SelfTest.cmd(name + " equip legs minecraft:netherite_leggings"),
                SelfTest.cmd(name + " equip feet minecraft:netherite_boots"));
    }

    /** The same for the anchor half of the crystal style. */
    private static List<String> anchorCombat(String name)
    {
        return List.of("bot option " + name + " combatstyle anchor",
                "bot option " + name + " difficulty expert",
                "bot option " + name + " combat true");
    }

    /**
     * A walled bedrock platform to fight on, with a ring of chunks kept loaded so the bot has blocks to plan
     * over. The wall keeps a bot that is walking in towards a blast, or out of one, on the ground: an arena
     * with an edge to it measures how well the bots avoid falling off it.
     */
    private static List<String> arena(int x0, int z0, int size)
    {
        int x1 = x0 + size - 1;
        int z1 = z0 + size - 1;
        List<String> course = new ArrayList<>();
        course.add(SelfTest.forceload(x0 - 3, z0 - 3, x1 + 3, z1 + 3));
        course.add(SelfTest.fill(x0, FLOOR, z0, x1, SURFACE, z1, "minecraft:bedrock"));
        course.add(SelfTest.fill(x0 - 1, SURFACE, z0 - 1, x0 - 1, SURFACE + 2, z1 + 1, "minecraft:bedrock"));
        course.add(SelfTest.fill(x1 + 1, SURFACE, z0 - 1, x1 + 1, SURFACE + 2, z1 + 1, "minecraft:bedrock"));
        course.add(SelfTest.fill(x0, SURFACE, z0 - 1, x1, SURFACE + 2, z0 - 1, "minecraft:bedrock"));
        course.add(SelfTest.fill(x0, SURFACE, z1 + 1, x1, SURFACE + 2, z1 + 1, "minecraft:bedrock"));
        course.add(HURTING);
        return course;
    }

    /** The block a blast is measured from: bedrock, which EndCrystalItem.useOn accepts. */
    private static BlockPos base(int x, int z)
    {
        return new BlockPos(x, SURFACE, z);
    }

    /**
     * A covered pit three blocks across and two deep, dug into the ground. A fighter inside it can only put a
     * crystal down among its own feet, so every blast it could set off is one it is standing in.
     */
    private static List<String> pitArena(int x0, int z0)
    {
        List<String> course = new ArrayList<>();
        course.add(SelfTest.forceload(x0 - 3, z0 - 3, x0 + 6, z0 + 6));
        course.add(SelfTest.fill(x0, PIT_FLOOR, z0, x0 + 3, PIT_FLOOR, z0 + 3, "minecraft:bedrock"));
        course.add(SelfTest.fill(x0, PIT_FLOOR + 1, z0, x0 + 3, PIT_FLOOR + 5, z0 + 3, "minecraft:bedrock"));
        course.add(SelfTest.fill(x0 + 1, PIT_FLOOR + 1, z0 + 1, x0 + 2, PIT_FLOOR + 2, z0 + 2, "minecraft:air"));
        course.add(HURTING);
        return course;
    }

    /** Where a fighter stands on an arena. */
    private static Vec3 spot(Vec3 origin, int x, int z)
    {
        return new Vec3((int) origin.x + x + 0.5, SURFACE + 1.0, (int) origin.z + z + 0.5);
    }

    /** Where a fighter stands at the bottom of the pit, whose floor is a layer lower than an arena's. */
    private static Vec3 pitSpot(Vec3 origin, int x, int z)
    {
        return new Vec3((int) origin.x + x + 0.5, PIT_FLOOR + 1.0, (int) origin.z + z + 0.5);
    }

    /** Puts two fighters on the same arena with their kits and makes them target each other. */
    private static void arm(MinecraftServer server, String first, String second, String firstDifficulty,
            String secondDifficulty)
    {
        crystalKit(first).forEach(command -> SelfTest.run(server, command));
        crystalKit(second).forEach(command -> SelfTest.run(server, command));
        crystalCombat(first, firstDifficulty).forEach(command -> SelfTest.run(server, command));
        crystalCombat(second, secondDifficulty).forEach(command -> SelfTest.run(server, command));
        SelfTest.run(server, "bot duel " + first + " " + second);
    }

    /** The armour pieces of the crystal kit, in the order the replaceitem command wants them. */
    private static final String[] PIECES = {"helmet", "chestplate", "leggings", "boots"};

    /**
     * Puts plain netherite armour on two fighters. Blast protection is worth twenty points of protection,
     * which is what makes a blast survivable from close by and what a crystal player wears, but it also cuts
     * a blast down to a few points of damage, so a duel in it takes far longer than one round of this
     * scenario. The phase that only has to reach a totem pop is fought without it.
     */
    private static void stripBlastProtection(MinecraftServer server, String... names)
    {
        String[] slots = {"head", "chest", "legs", "feet"};
        for (String name : names)
        {
            for (int piece = 0; piece < slots.length; piece++)
            {
                SelfTest.run(server, "replaceitem entity " + name + " armor." + slots[piece]
                        + " replace with minecraft:netherite_" + PIECES[piece]);
            }
        }
    }

    private static String fighter(int duel, String side)
    {
        return "SelfX" + duel + side;
    }

    /** The exposure ServerExplosion itself computes for the same box, which the model's ray walk has to match. */
    private static float gameSeen(double cx, double cy, double cz, ServerPlayer fighter)
    {
        return ServerExplosion.getSeenPercent(new Vec3(cx, cy, cz), fighter);
    }

    /**
     * The difficulty the server is on, which Player.hurtServer scales an explosion by and which the crystal
     * model has to be told about.
     */
    private static int difficulty(MinecraftServer server)
    {
        return server.getWorldData().getDifficulty().getId();
    }

    // ===== crystal_damage_matches_model =====

    /**
     * A crystal set off at a measured offset from an armoured fighter costs exactly what the crystal model
     * says it should. One blast per offset, with the distance and the exposure read off the level the blast
     * really happens in and worked through CombatMath, and with the model's exposure checked against the one
     * ServerExplosion computes for the same box.
     */
    static Scenario damageMatchesModel(String a, String b, String c, Vec3 origin)
    {
        return new Scenario(600, List.of(new Bot(a, spot(origin, 12, 6))), arena((int) origin.x, (int) origin.z, 19),
                server ->
                {
                    SelfTest.run(server, SelfTest.cmd(a + " equip head minecraft:netherite_helmet"));
                    SelfTest.run(server, SelfTest.cmd(a + " equip chest minecraft:netherite_chestplate"));
                    SelfTest.run(server, SelfTest.cmd(a + " equip legs minecraft:netherite_leggings"));
                    SelfTest.run(server, SelfTest.cmd(a + " equip feet minecraft:netherite_boots"));
                }, new DamageProbe(a, origin));
    }

    /** Sets one blast off after another at a growing distance and compares what each one cost. */
    private static final class DamageProbe implements Function<MinecraftServer, Probe>
    {
        private static final int SUMMONING = 0;
        private static final int READING = 1;
        private static final int SETTLING = 2;
        /** Ticks a fighter waits between two measured blasts, which is as long as a hit holds it off. */
        private static final int SETTLE_TICKS = 14;
        /** How far from the crystal each of the five measured blasts stands, in blocks. */
        private static final int[] OFFSETS = {6, 7, 8, 9, 10};

        private final String fighter;
        private final Vec3 origin;
        private final float[] expected = new float[OFFSETS.length];
        private final float[] exposure = new float[OFFSETS.length];
        private final float[] gameSeen = new float[OFFSETS.length];
        private final double[] distance = new double[OFFSETS.length];
        private final float[] taken = new float[OFFSETS.length];

        private int tick;
        private int step;
        private int stage = SUMMONING;
        private int settling;

        DamageProbe(String fighter, Vec3 origin)
        {
            this.fighter = fighter;
            this.origin = origin;
        }

        @Override
        public Probe apply(MinecraftServer server)
        {
            tick++;
            if (tick <= 62)
            {
                return SelfTest.pending("waiting for " + fighter + " to finish loading");
            }
            if (step >= OFFSETS.length)
            {
                SelfTest.run(server, QUIET);
                return conclude();
            }
            ServerPlayer target = SelfTest.player(server, fighter);
            if (target == null)
            {
                return SelfTest.pending(fighter + " has not joined yet");
            }
            if (SelfTest.warmingUp(server, fighter))
            {
                return SelfTest.pending(fighter + " is still loading");
            }
            double[] centre = blastCentre();
            if (stage == SETTLING)
            {
                return settle(server, target);
            }
            if (stage == SUMMONING)
            {
                SelfTest.run(server, "summon minecraft:end_crystal " + centre[0] + " " + centre[1] + " "
                        + centre[2]);
                stage = READING;
                return SelfTest.pending(SelfTest.fmt("put a crystal %d blocks from %s", OFFSETS[step], fighter));
            }
            if (crystalPresent(server))
            {
                exposure[step] = SeenPercent.of(new LevelExplosionView(server.overworld()), centre[0], centre[1],
                        centre[2], Box.player(target.getX(), target.getY(), target.getZ()));
                gameSeen[step] = gameSeen(centre[0], centre[1], centre[2], target);
                double dx = target.getX() - centre[0];
                double dy = target.getY() - centre[1];
                double dz = target.getZ() - centre[2];
                distance[step] = Math.sqrt(dx * dx + dy * dy + dz * dz);
                float[] armour = defences(target);
                float raw = CombatMath.explosionDamage(distance[step], CrystalPlacement.CRYSTAL_POWER,
                        exposure[step]);
                expected[step] = CombatMath.damageAfterDefences(
                        CombatMath.playerDifficultyScale(raw, difficulty(server)), armour[0], armour[1], 0,
                        armour[2]);
                // One hurt packet on a crystal is what sets it off, the same call the game makes when
                // something hits it.
                SelfTest.run(server, "damage @e[type=minecraft:end_crystal,limit=1,sort=nearest,x=" + centre[0]
                        + ",y=" + centre[1] + ",z=" + centre[2] + "] 1 minecraft:player_attack");
                return SelfTest.pending(SelfTest.fmt("the blast went off %.3f blocks from %s, which the model"
                        + " says costs %.2f of its 20", distance[step], fighter, expected[step]));
            }
            taken[step] = 20.0F - target.getHealth();
            // Put the fighter back where it started and whole, then let the hit of that blast roll off its
            // damage cooldown, so the next one is measured on its own.
            SelfTest.run(server, "tp " + fighter + " " + SelfTest.fmt("%.1f %.1f %.1f",
                    (int) origin.x + 12.5, SelfTest.SURFACE_Y, (int) origin.z + 6.5));
            SelfTest.run(server, "effect give " + fighter + " minecraft:instant_health 1 5");
            stage = SETTLING;
            return SelfTest.pending(SelfTest.fmt("the blast cost %s %.2f health, leaving it on %.1f", fighter,
                    taken[step], target.getHealth()));
        }

        private Probe settle(MinecraftServer server, ServerPlayer target)
        {
            if (++settling < SETTLE_TICKS || !SelfTest.hittable(target))
            {
                return SelfTest.pending(SelfTest.fmt("waiting out the damage cooldown of the last blast,"
                        + " %d ticks of %d", settling, SETTLE_TICKS));
            }
            settling = 0;
            step++;
            stage = SUMMONING;
            return SelfTest.pending("the fighter is whole again");
        }

        private Probe conclude()
        {
            StringBuilder report = new StringBuilder();
            boolean matched = true;
            for (int i = 0; i < OFFSETS.length; i++)
            {
                matched &= Math.abs(taken[i] - expected[i]) <= TOLERANCE;
                matched &= Math.abs(exposure[i] - gameSeen[i]) <= TOLERANCE;
                report.append(SelfTest.fmt("at %.3f blocks, exposure %.2f against the game's %.2f, lost %.2f of"
                        + " 20 against a predicted %.2f; ", distance[i], exposure[i], gameSeen[i], taken[i],
                        expected[i]));
            }
            return new Probe(matched, report.toString());
        }

        /** Where the blast of this round goes, so that it sits the offset of the round away from the fighter. */
        private double[] blastCentre()
        {
            return CrystalPlacement.crystalCentre((int) origin.x + 12 - OFFSETS[step], SURFACE,
                    (int) origin.z + 6);
        }

        private boolean crystalPresent(MinecraftServer server)
        {
            double[] centre = blastCentre();
            return !server.overworld().getEntitiesOfClass(EndCrystal.class,
                    new AABB(centre[0] - 2, centre[1] - 2, centre[2] - 2, centre[0] + 2, centre[1] + 2,
                            centre[2] + 2)).isEmpty();
        }
    }

    // ===== crystal_place_and_hit =====

    /**
     * The bot puts a crystal down itself, sets it off with a swing at least one click after the click that
     * placed it, and the target loses health for it.
     */
    static Scenario placeAndHit(String a, String b, String c, Vec3 origin)
    {
        return new Scenario(900, List.of(new Bot(a, spot(origin, 2, 2)), new Bot(b, spot(origin, 2, 7))),
                arena((int) origin.x, (int) origin.z, 13), server ->
                {
                    crystalKit(a).forEach(command -> SelfTest.run(server, command));
                    crystalCombat(a, "average").forEach(command -> SelfTest.run(server, command));
                }, new PlaceAndHitProbe(a, b));
    }

    /** Counts the crystal the bot put down itself and the blast it set off a click later. */
    private static final class PlaceAndHitProbe implements Function<MinecraftServer, Probe>
    {
        private final String bot;
        private final String target;

        private int tick;
        private boolean armed;
        private int placedAt = -1;

        PlaceAndHitProbe(String bot, String target)
        {
            this.bot = bot;
            this.target = target;
        }

        @Override
        public Probe apply(MinecraftServer server)
        {
            tick++;
            if (!armed)
            {
                if (SelfTest.player(server, bot) == null)
                {
                    return SelfTest.pending(bot + " has not joined yet");
                }
                if (SelfTest.warmingUp(server, bot, target))
                {
                    return SelfTest.pending(bot + " and " + target + " are still loading");
                }
                armed = true;
                BotBody body = SelfTest.body(server, bot);
                return SelfTest.pending(SelfTest.fmt("%s is an average crystal bot, %d ticks between its clicks",
                        bot, body == null ? 0 : body.clickPeriod()));
            }
            BotBody body = SelfTest.body(server, bot);
            if (body == null)
            {
                return SelfTest.pending(bot + " has not started fighting yet");
            }
            BotStats stats = body.stats();
            if (placedAt < 0 && stats.crystalsPlaced >= 1)
            {
                placedAt = tick;
                return SelfTest.pending(SelfTest.fmt("%s put its first crystal down on tick %d", bot, placedAt));
            }
            ServerPlayer victim = SelfTest.player(server, target);
            if (stats.blasts >= 1 && placedAt >= 0)
            {
                SelfTest.run(server, QUIET);
                int gap = tick - placedAt;
                boolean apartEnough = gap >= body.clickPeriod();
                boolean damaged = victim.getHealth() < 20.0F;
                return new Probe(apartEnough && damaged, SelfTest.fmt(
                        "%s placed %d crystals and set %d of them off, %d ticks between the first placement and"
                                + " its blast where its %d tick click rate allows at least that many; %s has"
                                + " %.1f health; %d clicks, %d missed, %d refusals, %d blocks, %d searches",
                        bot, stats.crystalsPlaced, stats.blasts, gap, body.clickPeriod(), target,
                        victim.getHealth(), stats.clicks, stats.misses, stats.refusedBlasts, stats.blocksPlaced,
                        stats.plannerCalls));
            }
            return SelfTest.pending(SelfTest.fmt("%s has placed %d crystals and set %d of them off over %d"
                    + " searches", bot, stats.crystalsPlaced, stats.blasts, stats.plannerCalls));
        }
    }

    // ===== crystal_never_suicides =====

    /**
     * A bot with nothing to wear, fighting a target standing one block away: every blast that would reach the
     * target is also inside the bot's own reach, so every placement it can make near enough to hurt costs it
     * more than the 20 health it has left. It has to keep refusing to set those off.
     */
    static Scenario neverSuicides(String a, String b, String c, Vec3 origin)
    {
        return new Scenario(900, List.of(new Bot(a, pitSpot(origin, 1, 1)), new Bot(b, pitSpot(origin, 1, 2))),
                pitArena((int) origin.x, (int) origin.z), server ->
                {
                    // Neither of them wears anything, so a blast either of them could reach would finish the bot.
                    crystalCombat(a, "casual").forEach(command -> SelfTest.run(server, command));
                    SelfTest.run(server, "give " + a + " minecraft:end_crystal 24");
                    SelfTest.run(server, "give " + b + " minecraft:totem_of_undying");
                    SelfTest.run(server, SelfTest.cmd(b + " equip offhand minecraft:totem_of_undying"));
                    // A totem of its own, so the scenario can say the bot never popped one.
                    SelfTest.run(server, SelfTest.cmd(a + " equip offhand minecraft:totem_of_undying"));
                }, new NeverSuicidesProbe(a));
    }

    /** Watches a bot that has nowhere to go keep refusing its own blasts, and calls it stalled if it stops. */
    private static final class NeverSuicidesProbe implements Function<MinecraftServer, Probe>
    {
        private final String bot;

        private int tick;
        private boolean armed;
        private int firstRefusal = -1;

        NeverSuicidesProbe(String bot)
        {
            this.bot = bot;
        }

        @Override
        public Probe apply(MinecraftServer server)
        {
            tick++;
            ServerPlayer fighter = SelfTest.player(server, bot);
            if (!armed)
            {
                if (SelfTest.warmingUp(server, bot))
                {
                    return SelfTest.pending(bot + " is still loading");
                }
                armed = true;
                return SelfTest.pending(bot + " is in the pit, unarmoured, with a target that has a totem");
            }
            BotStats stats = SelfTest.stats(fighter);
            if (stats.refusedBlasts > 0 && firstRefusal < 0)
            {
                firstRefusal = tick;
            }
            boolean watched = firstRefusal >= 0 && tick - firstRefusal > WATCHED_TICKS;
            boolean held = fighter.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING);
            if (watched)
            {
                SelfTest.run(server, QUIET);
                // It walked away from a blast it could not survive and never lost health for it. A blast it did
                // set off later was one it had first got out of, which is what the same rule asks of a player.
                return new Probe(fighter.getHealth() >= 20.0F && held && fighter.isAlive(), SelfTest.fmt(
                        "%s put %d crystals down and refused %d of them as ones it could not survive; it set off"
                                + " %d, each from where the blast could not see it, and finished on %.1f of 20"
                                + " health with %s totem in its offhand, %d backed-off ticks and %d blocks up",
                        bot, stats.crystalsPlaced, stats.refusedBlasts, stats.blasts, fighter.getHealth(),
                        held ? "its" : "no", stats.backedOff, stats.blocksPlaced));
            }
            return SelfTest.pending(SelfTest.fmt("%s has refused %d blasts, health %.1f, %d crystals placed",
                    bot, stats.refusedBlasts, fighter.getHealth(), stats.crystalsPlaced));
        }
    }

    // ===== crystal_retotem =====

    /**
     * A popped totem is not replaced on the tick it pops: the bot waits out the delay it is configured with
     * and only then moves a fresh one into the offhand.
     */
    static Scenario reTotem(String a, String b, String c, Vec3 origin)
    {
        return new Scenario(600, List.of(new Bot(a, spot(origin, 2, 2)), new Bot(b, spot(origin, 2, 5))),
                arena((int) origin.x, (int) origin.z, 9), server ->
                {
                    crystalKit(a).forEach(command -> SelfTest.run(server, command));
                    // The brain's own totem reflex would put one back the same tick, so for this scenario to
                    // say anything about the crystal style's delay, that reflex has to be switched off.
                    SelfTest.run(server, "bot option " + a + " autototem false");
                    crystalCombat(a, "casual").forEach(command -> SelfTest.run(server, command));
                }, new ReTotemProbe(a));
    }

    /** Pops the bot's totem once and then watches how long it takes to get another one back. */
    private static final class ReTotemProbe implements Function<MinecraftServer, Probe>
    {
        private static final int ARMING = 0;
        private static final int FIRING = 1;
        private static final int WAITING = 2;

        private final String bot;
        private int tick;
        private int phase = ARMING;
        private int poppedAt;
        private int delay = 20;

        ReTotemProbe(String bot)
        {
            this.bot = bot;
        }

        @Override
        public Probe apply(MinecraftServer server)
        {
            tick++;
            if (!(SelfTest.player(server, bot) instanceof EntityPlayerMPFake fighter))
            {
                return SelfTest.pending(bot + " has not joined yet");
            }
            if (phase == ARMING)
            {
                if (SelfTest.warmingUp(server, bot))
                {
                    return SelfTest.pending(bot + " is still loading");
                }
                if (!SelfTest.hittable(fighter))
                {
                    return SelfTest.pending(bot + " cannot be hurt yet");
                }
                delay = (int) fighter.getPvpConfig().number("crystal.retotem_delay");
                if (SelfTest.result(server, "damage " + bot + " 100") < 1)
                {
                    return SelfTest.pending("could not damage " + bot);
                }
                phase = FIRING;
                return SelfTest.pending("hit " + bot + " for more than it has health");
            }
            boolean held = fighter.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING);
            if (phase == FIRING)
            {
                if (held)
                {
                    return SelfTest.pending(bot + " still holds the totem it should have lost");
                }
                poppedAt = tick;
                phase = WAITING;
                return SelfTest.pending(SelfTest.fmt("the totem of %s popped on tick %d, its offhand is empty",
                        bot, poppedAt));
            }
            int waited = tick - poppedAt;
            if (held)
            {
                SelfTest.run(server, QUIET);
                return new Probe(waited >= delay, SelfTest.fmt(
                        "%s put a totem back in its offhand %d ticks after the pop, where %d were configured;"
                                + " %d re-totems, off %.1f health", bot, waited, delay,
                        SelfTest.stats(fighter).reTotems, fighter.getHealth()));
            }
            return SelfTest.pending(SelfTest.fmt("%d ticks after the pop the offhand of %s is still empty",
                    waited, bot));
        }
    }

    // ===== crystal_anchor =====

    /** A respawn anchor bot places an anchor, charges it and sets it off, and the target pays for it. */
    static Scenario anchor(String a, String b, String c, Vec3 origin)
    {
        // A small walled platform of bedrock with nothing on it but the fighter: the bot carries no end
        // crystal and no obsidian, so an anchor is the only blast it has, and it has to walk the fighter into
        // its own blast rather than set one off from where it started.
        List<String> course = new ArrayList<>(arena((int) origin.x, (int) origin.z, 9));
        // A respawn anchor sets fire as well as blasting, and the crystal model has no fire in it, so the
        // scenario takes the burning out of the picture and leaves only what the scenario is about. A
        // duration of seconds is not a duration at all: only "infinite" lasts the duel.
        course.add("effect give " + a + " minecraft:fire_resistance infinite");
        course.add("effect give " + b + " minecraft:fire_resistance infinite");
        return new Scenario(800, List.of(new Bot(a, spot(origin, 1, 1)), new Bot(b, spot(origin, 6, 6))), course,
                server ->
                {
                    anchorKit(a).forEach(command -> SelfTest.run(server, command));
                    anchorCombat(a).forEach(command -> SelfTest.run(server, command));
                }, new AnchorProbe(a, b));
    }

    /** Watches the anchor bot until it has blown one. */
    private static final class AnchorProbe implements Function<MinecraftServer, Probe>
    {
        private final String bot;
        private final String target;

        private int tick;
        private int seen;
        private int armedAt = -1;
        private int gone;
        private String problem;
        private Probe verdict;
        private int blown;
        private int blownAt = -1;

        AnchorProbe(String bot, String target)
        {
            this.bot = bot;
            this.target = target;
        }

        @Override
        public Probe apply(MinecraftServer server)
        {
            if (problem != null)
            {
                // The harness only ends a scenario when it passes or runs out of time, so once this probe has
                // found a problem it keeps saying what it was.
                return new Probe(false, problem);
            }
            tick++;
            ServerPlayer fighter = SelfTest.player(server, bot);
            ServerPlayer victim = SelfTest.player(server, target);
            if (!(fighter instanceof EntityPlayerMPFake fake))
            {
                if (++gone > 60)
                {
                    SelfTest.run(server, QUIET);
                    problem = bot + " left the server " + gone + " ticks into a duel it never finished";
                    return new Probe(false, problem);
                }
                return SelfTest.pending(bot + " is not on the server");
            }
            if (armedAt < 0)
            {
                if (SelfTest.warmingUp(server, bot, target))
                {
                    return SelfTest.pending(bot + " and " + target + " are still loading");
                }
                armedAt = tick;
                return SelfTest.pending(SelfTest.fmt("/bot spawn made %s an anchor bot holding %s", bot,
                        fake.getMainHandItem().getItem()));
            }
            BotStats stats = SelfTest.stats(fake);
            if (stats.anchorsPlaced != seen)
            {
                seen = stats.anchorsPlaced;
                armedAt = tick;
            }
            if (verdict != null)
            {
                return verdict;
            }
            if (stats.anchorsBlown > blown)
            {
                // The bot has clicked an anchor. Its blast goes off a tick later, out of the action pack's
                // queue, so the difficulty has to stay at normal until the fighter has paid for it: on a
                // peaceful server the whole of a blast is taken away and the click would be for nothing.
                if (blownAt < 0)
                {
                    blownAt = tick;
                }
                blown = stats.anchorsBlown;
            }
            if (blown > 0)
            {
                // A power five anchor blast kills an unarmoured fighter outright, and a fake player is back at
                // full health a tick later, so health alone is not everything it cost: dying counts as paying.
                // Only the blast counts: a fighter knocked off the ledge pays for the fall, not the anchor.
                DamageSource source = victim.getLastDamageSource();
                boolean blasted = source != null && source.is(DamageTypeTags.IS_EXPLOSION);
                boolean killed = victim instanceof EntityPlayerMPFake fallen && fallen.diedTick() > 0L;
                boolean paid = blasted && (killed || victim.getHealth() < 20.0F);
                if (paid || tick - blownAt > BLOWN_WAIT)
                {
                    SelfTest.run(server, QUIET);
                    verdict = new Probe(paid, SelfTest.fmt(
                            "%s placed %d anchors and set %d of them off, with %d crystals and %d blocks along"
                                    + " the way; %d ticks after the first click %s is on %.1f health, %s, and its"
                                    + " last damage was %s; %d refusals, %d backed-off ticks, %d searches",
                            bot, stats.anchorsPlaced, stats.anchorsBlown, stats.crystalsPlaced,
                            stats.blocksPlaced, tick - blownAt, target, victim.getHealth(),
                            killed ? "which a blast finished off" : "which a blast left standing",
                            source == null ? "nothing" : source.getMsgId(), stats.refusedBlasts,
                            stats.backedOff, stats.plannerCalls));
                }
                else
                {
                    return SelfTest.pending(SelfTest.fmt("%d anchors down and blown, %s is still on %.1f",
                            stats.anchorsBlown, target, victim.getHealth()));
                }
                return verdict;
            }
            if (tick - armedAt > STALL_TICKS * 2)
            {
                SelfTest.run(server, QUIET);
                problem = SelfTest.fmt("%s stalled after %d anchors, %d crystals, %d blocks and"
                        + " %d searches in %d ticks", bot, stats.anchorsPlaced, stats.crystalsPlaced,
                        stats.blocksPlaced, stats.plannerCalls, tick - armedAt);
                return new Probe(false, problem);
            }
            if (tick < 40)
            {
                SelfTest.log(server, SelfTest.fmt("%s at %.2f %.2f %.2f on %.1f health: %d anchors, %d blown,"
                                + " %d crystals, %d refusals, %d clicks", bot, fake.getX(), fake.getY(),
                        fake.getZ(), fake.getHealth(), stats.anchorsPlaced, stats.anchorsBlown,
                        stats.crystalsPlaced, stats.refusedBlasts, stats.clicks));
            }
            return SelfTest.pending(SelfTest.fmt("%s has %d anchors down, %d blown, %d crystals, %d searches",
                    bot, stats.anchorsPlaced, stats.anchorsBlown, stats.crystalsPlaced, stats.plannerCalls));
        }
    }

    // ===== crystal_duel =====

    /**
     * Two experts fight until one of them pops, and then an expert plays a beginner over several rounds: the
     * harder preset has to come out ahead in a clear majority of them.
     */
    static Scenario duel(String a, String b, String c, Vec3 origin)
    {
        List<String> course = new ArrayList<>(arena((int) origin.x, (int) origin.z, 7));
        for (int round = 0; round < ROUNDS; round++)
        {
            course.addAll(arena((int) origin.x, (int) origin.z + 20 + round * 20, 17));
        }
        return new Scenario(EXPERT_TICKS + ROUND_TICKS * ROUNDS + 900, List.of(), course, server -> {},
                new DuelProbe(origin));
    }

    /**
     * The phases of the duel scenario: two experts to the first totem pop, and then an expert against a
     * beginner over and over. Every pair is taken off the server as soon as its fight is over.
     */
    private static final class DuelProbe implements Function<MinecraftServer, Probe>
    {
        private static final int WARMING = 0;
        private static final int EXPERTS = 1;
        private static final int ROUNDING = 2;

        private final Vec3 origin;
        private String first = "";
        private String second = "";
        private boolean firstIsExpert = true;
        private int phase = WARMING;
        private int tick;
        private int started;
        private String problem;
        private int expertWins;
        private int losses;
        private int totemsBefore;

        DuelProbe(Vec3 origin)
        {
            this.origin = origin;
        }

        @Override
        public Probe apply(MinecraftServer server)
        {
            if (problem != null)
            {
                // A probe that has found a problem keeps saying so: the harness only ends a scenario when
                // it passes or runs out of time, so the last thing said has to be the real reason.
                return new Probe(false, problem);
            }
            tick++;
            return switch (phase)
            {
                case WARMING -> warmUp(server, 0, true, true);
                case EXPERTS -> experts(server);
                case ROUNDING -> rounds(server);
                default -> new Probe(false, "the duel scenario ran out of phases");
            };
        }

        /** Spawns a pair on the arena of its duel and waits for both of them to finish loading. */
        private Probe warmUp(MinecraftServer server, int duel, boolean expertFirst, boolean expertsFirst)
        {
            if (first.isEmpty())
            {
                first = fighter(duel, "a");
                second = fighter(duel, "b");
                int z = expertsFirst ? 4 : 4 + duel * 20;
                SelfTest.run(server, SelfTest.cmd(first + " spawn at "
                        + SelfTest.coords(spot(origin, 2, z)) + " facing 90 0 in minecraft:overworld in survival"));
                SelfTest.run(server, SelfTest.cmd(second + " spawn at "
                        + SelfTest.coords(spot(origin, 5, z)) + " facing 270 0 in minecraft:overworld"
                        + " in survival"));
                return SelfTest.pending(first + " and " + second + " are logging in");
            }
            for (String name : List.of(first, second))
            {
                if (SelfTest.player(server, name) == null)
                {
                    return SelfTest.pending("waiting for " + name + " to join");
                }
                if (SelfTest.warmingUp(server, name))
                {
                    return SelfTest.pending("waiting for " + name + " to finish loading");
                }
            }
            firstIsExpert = expertFirst;
            arm(server, first, second, expertFirst || expertsFirst ? "expert" : "beginner",
                    expertsFirst ? "expert" : expertFirst ? "beginner" : "expert");
            if (expertsFirst)
            {
                stripBlastProtection(server, first, second);
            }
            started = tick;
            totemsBefore = totems(SelfTest.player(server, first)) + totems(SelfTest.player(server, second));
            phase = expertsFirst ? EXPERTS : ROUNDING;
            return SelfTest.pending(first + " and " + second + " are fighting with " + totemsBefore
                    + " totems between them");
        }

        /** The first phase: somebody has to pop a totem, and neither of them may fall over doing it. */
        private Probe experts(MinecraftServer server)
        {
            ServerPlayer one = SelfTest.player(server, first);
            ServerPlayer two = SelfTest.player(server, second);
            if (!(one instanceof EntityPlayerMPFake) || !(two instanceof EntityPlayerMPFake))
            {
                SelfTest.run(server, QUIET);
                leave(server);
                problem = "an expert left the server mid-fight, " + (tick - started) + " ticks in";
                return new Probe(false, problem);
            }
            if (totems(one) + totems(two) < totemsBefore)
            {
                Probe report = SelfTest.pending(SelfTest.fmt(
                        "the two experts popped a totem between them after %d ticks, on %.1f and %.1f health",
                        tick - started, one.getHealth(), two.getHealth()));
                SelfTest.run(server, QUIET);
                leave(server);
                phase = ROUNDING;
                return report;
            }
            if (tick - started > EXPERT_TICKS)
            {
                BotStats stats = SelfTest.stats(one);
                SelfTest.run(server, QUIET);
                leave(server);
                problem = SelfTest.fmt(
                        "two experts fought %d ticks without a totem popping: %.1f and %.1f health, with %d"
                                + " crystals and %d blasts between them", tick - started, one.getHealth(),
                        two.getHealth(), stats.crystalsPlaced, stats.blasts);
                return new Probe(false, problem);
            }
            return SelfTest.pending(SelfTest.fmt("the two experts are on %.1f and %.1f health after %d ticks",
                    one.getHealth(), two.getHealth(), tick - started));
        }

        /** The rounds: an expert against a beginner, over and over, each on its own piece of bedrock. */
        private Probe rounds(MinecraftServer server)
        {
            int round = expertWins + losses;
            if (round >= ROUNDS)
            {
                SelfTest.run(server, QUIET);
                return new Probe(expertWins >= ROUNDS_TO_WIN, SelfTest.fmt(
                        "an expert crystal bot won %d of %d rounds against a beginner; %d went the other way"
                                + " or ran out of time", expertWins, ROUNDS, losses));
            }
            if (first.isEmpty())
            {
                return warmUp(server, round + 1, round % 2 == 0, false);
            }
            ServerPlayer one = SelfTest.player(server, first);
            ServerPlayer two = SelfTest.player(server, second);
            if (!(one instanceof EntityPlayerMPFake) || !(two instanceof EntityPlayerMPFake))
            {
                SelfTest.run(server, QUIET);
                leave(server);
                problem = "a fighter left the server mid-round";
                return new Probe(false, problem);
            }
            boolean over = one.isDeadOrDying() || two.isDeadOrDying();
            if (tick - started <= ROUND_TICKS && !over)
            {
                return SelfTest.pending(SelfTest.fmt("round %d after %d ticks: %.1f and %.1f health",
                        round, tick - started, one.getHealth(), two.getHealth()));
            }
            ServerPlayer expert = firstIsExpert ? one : two;
            ServerPlayer beginner = firstIsExpert ? two : one;
            if (expert.isDeadOrDying())
            {
                losses++;
            }
            else
            {
                expertWins++;
            }
            BotStats stats = SelfTest.stats(expert);
            SelfTest.run(server, SelfTest.cmd(first + " disconnect"));
            SelfTest.run(server, SelfTest.cmd(second + " disconnect"));
            first = "";
            return SelfTest.pending(SelfTest.fmt("round %d went to the %s: the expert is on %.1f health with"
                    + " %d crystals placed and %d blasts, the beginner on %.1f with %d hits missed",
                    round, expert.isDeadOrDying() ? "beginner" : "expert", expert.getHealth(),
                    stats.crystalsPlaced, stats.blasts, beginner.getHealth(), stats.misses));
        }

        private void leave(MinecraftServer server)
        {
            if (!first.isEmpty())
            {
                SelfTest.run(server, SelfTest.cmd(first + " disconnect"));
            }
            if (!second.isEmpty())
            {
                SelfTest.run(server, SelfTest.cmd(second + " disconnect"));
            }
            first = "";
            second = "";
        }
    }

    // ===== shared reading =====

    /** How many totems a fighter carries, the offhand one included. */
    private static int totems(ServerPlayer player)
    {
        if (player == null)
        {
            return 0;
        }
        int count = player.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING) ? 1 : 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
        {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.TOTEM_OF_UNDYING))
            {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** The armour value, armour toughness and blast protection of a fighter, as the game reads them off it. */
    private static float[] defences(ServerPlayer player)
    {
        ServerLevel level = player.level() instanceof ServerLevel server ? server : null;
        float protection = level == null ? 0.0F
                : EnchantmentHelper.getDamageProtection(level, player,
                        player.damageSources().explosion(player, player));
        return new float[] {player.getArmorValue(),
                (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS), protection};
    }
}