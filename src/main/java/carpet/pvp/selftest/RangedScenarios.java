package carpet.pvp.selftest;

import carpet.pvp.ranged.Bow;
import carpet.pvp.ranged.LevelExplosionView;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import carpet.pvp.sim.BowShot;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.CrystalSearch;
import carpet.pvp.sim.DuelSim;
import carpet.pvp.sim.ProjectileAim;
import carpet.pvp.sim.ProjectileSim;
import carpet.pvp.sim.SpearMath;
import carpet.pvp.sim.TntCartPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The ranged weapons: what the bot shoots, what it hits, and what it does when it cannot keep its distance.
 *
 * <p>Each scenario counts what the game did rather than what the bot meant: a bow shot is a draw that ended, a
 * hit is the target's hurt time going off, a crossbow load is the charge component appearing on the weapon. The
 * hit rate a bow measures is then set against what {@link BowShot} predicts from the aim error the same run
 * measured, so the two numbers can be compared rather than one of them trusted.</p>
 */
final class RangedScenarios
{
    /** Ticks of spear charge past the weapon's wind-up before the scenario accepts the shot. */
    private static final int SPEAR_WIND_UP = 40;
    /** Distance the two bow scenarios put their target at. */
    private static final double BOW_RANGE = 20.0;
    /** Arrows a bow scenario keeps firing before it reads its own hit rate off them. */
    private static final int WANTED_SHOTS = 24;
    /** How far off the predicted hit rate the measured one may be before the run is called a failure. */
    private static final double RATE_TOLERANCE = 0.4;
    /** How far out a target is still inside the spear's reach and outside a sword's. */
    private static final double SPEAR_RANGE = 4.2;

    private RangedScenarios()
    {
    }

    // --- the bow

    /** A stationary target at twenty blocks: how often the arrows land. */
    static Scenario bowHitsStatic(String a, String b, String c, Vec3 origin)
    {
        return bowHits(a, b, origin, false);
    }

    /** A target walking sideways at twenty blocks, which is what the lead is for. */
    static Scenario bowHitsMoving(String a, String b, String c, Vec3 origin)
    {
        return bowHits(a, b, origin, true);
    }

    private static Scenario bowHits(String a, String b, Vec3 origin, boolean walking)
    {
        Vec3 target = origin.add(0.0D, 0.0D, BOW_RANGE);
        int[] tick = {0};
        boolean[] armed = {false};
        boolean[] drawing = {false};
        int[] shots = {0};
        Hits[] hits = {new Hits()};
        boolean[] direction = {true};
        double[] errorSum = {0.0D};
        double[] errorSq = {0.0D};
        double[] gapSum = {0.0D};
        double[] gapMin = {Double.MAX_VALUE};
        double[] gapMax = {0.0D};
        String[] note = {""};
        List<String> course = new ArrayList<>(rangedCourse(a));
        course.add("bot option " + a + " ranged.crossbow false");
        course.add("bot option " + a + " strafe false");
        course.add("bot option " + a + " ranged.keep " + SelfTest.fmt("%.1f", BOW_RANGE));
        course.add("effect give " + b + " minecraft:resistance 100000 4 true");
        if (walking)
        {
            // The target walks sideways across the line of fire rather than away from it, which is what the
            // lead in the aim is for. It is turned round once it has walked far enough, so it stays inside
            // the range the bot can see it at and inside the chunks that are in memory.
            course.add(SelfTest.forceload((int) origin.x - 32, (int) origin.z - 8,
                    (int) origin.x + 32, (int) origin.z + 32));
        }
        return new Scenario(900, List.of(new Bot(a, origin), new Bot(b, target)), course, server ->
        {
            tick[0]++;
            ServerPlayer shooter = SelfTest.player(server, a);
            ServerPlayer victim = SelfTest.player(server, b);
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                armed[0] = true;
                return SelfTest.pending(a + " is aiming at " + b + " from " + SelfTest.fmt("%.1f", BOW_RANGE)
                        + " blocks");
            }
            int landed = hits[0].take(victim);
            if (walking)
            {
                // Walking is re-asked for every tick, so the target never stops, and reversed once it has
                // wandered far enough that the bot would lose sight of it.
                if (Math.abs(victim.getX() - target.x) > 12.0D)
                {
                    direction[0] = !direction[0];
                }
                SelfTest.run(server, "player " + b + " move " + (direction[0] ? "right" : "left") + " for 40");
            }
            boolean nowDrawing = shooter.isUsingItem();
            if (drawing[0] && !nowDrawing)
            {
                shots[0]++;
                // The angle and the range the shot was let go at, which is what the prediction is built from.
                double error = angle(shooter, victim);
                double gap = Math.hypot(victim.getX() - shooter.getX(), victim.getZ() - shooter.getZ());
                errorSum[0] += error;
                errorSq[0] += error * error;
                gapSum[0] += gap;
                gapMin[0] = Math.min(gapMin[0], gap);
                gapMax[0] = Math.max(gapMax[0], gap);
                drawing[0] = false;
            }
            else if (!drawing[0] && nowDrawing)
            {
                drawing[0] = true;
            }
            if (shots[0] < WANTED_SHOTS && tick[0] < WANTED_SHOTS * 40)
            {
                note[0] = SelfTest.fmt("%d ticks in, %d shots and %d hits, holding %s for %d ticks in slot %d, "
                                + "%.1f apart",
                        tick[0], shots[0], landed, shooter.getMainHandItem().getItem(),
                        shooter.isUsingItem() ? shooter.getTicksUsingItem() : -1,
                        shooter.getInventory().getSelectedSlot(),
                        Math.hypot(victim.getX() - shooter.getX(), victim.getZ() - shooter.getZ()));
                return SelfTest.pending(note[0]);
            }
            double measured = shots[0] == 0 ? 0.0D : (double) landed / shots[0];
            double sigma = shots[0] == 0 ? 0.0D
                    : Math.sqrt(Math.max(0.0D, errorSq[0] / shots[0] - (errorSum[0] / shots[0]) * (errorSum[0] / shots[0])));
            // The three errors the bot has: how far off its view was when it let go, the window inside which
            // it was willing to let go, and the spread the game adds to the arrow itself.
            double total = BowShot.combined(
                    BowShot.combined(sigma, BowShot.releaseSigmaDegrees(Bow.RELEASE_TOLERANCE)),
                    BowShot.arrowSigmaDegrees(ProjectileSim.bowPower(ProjectileSim.BOW_FULL_DRAW)));
            double predicted = BowShot.hitChance(total, gapSum[0] / Math.max(1, shots[0]));
            boolean ok = shots[0] >= WANTED_SHOTS && landed >= 1
                    && Math.abs(measured - predicted) <= RATE_TOLERANCE;
            return new Probe(ok, SelfTest.fmt(
                    "%d of %d arrows hit, %.0f%%, at %.1f to %.1f blocks (mean %.1f) from an aim error of "
                            + "%.2f degrees rms, which predicts %.0f%%; %d clicks, %.1f damage dealt",
                    landed, shots[0], 100.0D * measured, gapMin[0], gapMax[0],
                    gapSum[0] / Math.max(1, shots[0]), sigma, 100.0D * predicted,
                    SelfTest.stats(shooter).clicks, SelfTest.stats(shooter).damageDealt)
                    + (ok ? "" : "; last seen " + note[0]));
        });
    }

    // --- the crossbow

    /** Load, fire, load again: the crossbow cycle a player goes through. */
    static Scenario crossbowCycle(String a, String b, String c, Vec3 origin)
    {
        Vec3 target = origin.add(0.0D, 0.0D, 14.0D);
        boolean[] armed = {false};
        int[] loads = {0};
        boolean[] loaded = {false};
        Hits[] hits = {new Hits()};
        List<String> course = new ArrayList<>(rangedCourse(a));
        course.add("bot option " + a + " ranged.bow false");
        course.add("bot option " + a + " ranged.keep 14");
        course.add("effect give " + b + " minecraft:resistance 100000 4 true");
        return new Scenario(900, List.of(new Bot(a, origin), new Bot(b, target)), course, server ->
        {
            ServerPlayer shooter = SelfTest.player(server, a);
            ServerPlayer victim = SelfTest.player(server, b);
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                armed[0] = true;
                return SelfTest.pending(a + " is loading its crossbow at " + b);
            }
            int landed = hits[0].take(victim);
            ItemStack crossbow = crossbowOf(shooter);
            boolean charged = !crossbow.isEmpty() && net.minecraft.world.item.CrossbowItem.isCharged(crossbow);
            if (charged && !loaded[0])
            {
                loads[0]++;
                loaded[0] = true;
            }
            else if (!charged)
            {
                loaded[0] = false;
            }
            if (loads[0] < 3 || landed < 2)
            {
                return SelfTest.pending(SelfTest.fmt("%d loads and %d hits so far", loads[0], landed));
            }
            return new Probe(true, SelfTest.fmt(
                    "%s loaded its crossbow %d times and put %d of the shots into a target standing still, "
                            + "%.1f health on it",
                    a, loads[0], landed, victim.getHealth()));
        });
    }

    // --- the trident

    /** A trident thrown with the solver lands on a target that a bow would have to be much closer to. */
    static Scenario tridentThrow(String a, String b, String c, Vec3 origin)
    {
        Vec3 target = origin.add(0.0D, 0.0D, 12.0D);
        boolean[] armed = {false};
        int[] hits = {0};
        int[] casts = {0};
        boolean[] charging = {false};
        float[] lastHealth = {20.0F};
        String[] note = {""};
        List<String> course = new ArrayList<>();
        course.add("bot option " + a + " combatstyle ranged");
        course.add("bot option " + a + " difficulty expert");
        course.add("bot option " + a + " targetrange 24");
        course.add("bot option " + a + " ranged.keep " + SelfTest.fmt("%.1f", SPEAR_RANGE));
        course.add("bot option " + a + " combat true");
        return new Scenario(900, List.of(new Bot(a, origin), new Bot(b, target)), course, server ->
        {
            ServerPlayer shooter = SelfTest.player(server, a);
            ServerPlayer victim = SelfTest.player(server, b);
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                // Three tridents, one of them in the hotbar where a click can reach it: a thrown one is spent.
                int given = SelfTest.result(server, "give " + a + " minecraft:trident 2");
                int equipped = SelfTest.result(server, "player " + a + " equip mainhand minecraft:trident");
                if (given < 1 || equipped < 1)
                {
                    return new Probe(false, SelfTest.fmt("could not arm %s: give returned %d, equip returned %d",
                            a, given, equipped));
                }
                armed[0] = true;
                return SelfTest.pending(a + " is going to throw a trident at " + b);
            }
            if (victim.getHealth() < lastHealth[0])
            {
                hits[0]++;
            }
            lastHealth[0] = victim.getHealth();
            boolean held = shooter.isUsingItem();
            if (charging[0] && !held)
            {
                casts[0]++;
            }
            charging[0] = held;
            double gap = Math.hypot(victim.getX() - shooter.getX(), victim.getZ() - shooter.getZ());
            note[0] = SelfTest.fmt("%d casts and %d hits, %.1f blocks apart, slot %d holding %s, using %s, "
                            + "a trident in the hotbar: %s",
                    casts[0], hits[0], gap, shooter.getInventory().getSelectedSlot(),
                    shooter.getMainHandItem().getItem(), shooter.getUseItem().getItem(),
                    hasItem(shooter, Items.TRIDENT));
            if (hits[0] < 2)
            {
                return SelfTest.pending(note[0]);
            }
            return new Probe(hits[0] >= 2 && casts[0] >= 1, SelfTest.fmt(
                    "%s threw a trident %d times and hit %s %d times from %.1f blocks, leaving it on %.1f health",
                    a, casts[0], b, hits[0], gap, victim.getHealth()));
        });
    }

    // --- the spear

    /**
     * The spear reaches past where a sword can and the bot uses it out there: the target walks in on the bot
     * until the two are closer than a sword can reach and further than a spear can, and the bot has to be
     * standing there with a charged spear.
     */
    static Scenario spearReach(String a, String b, String c, Vec3 origin)
    {
        Vec3 target = origin.add(0.0D, 0.0D, 8.0D);
        boolean[] armed = {false};
        double[] gapAtReach = {-1.0D};
        double[] fastest = {0.0D};
        int[] charges = {0};
        List<String> course = new ArrayList<>();
        course.add("bot option " + a + " combatstyle ranged");
        course.add("bot option " + a + " difficulty expert");
        course.add("bot option " + a + " targetrange 24");
        course.add("bot option " + a + " ranged.keep 4.5");
        course.add("bot option " + a + " combat true");
        course.add("player " + b + " sprint");
        // Turned round and walking at the bot, so the two close to the spear's window.
        course.add("player " + b + " turn back");
        course.add("player " + b + " move forward for 900");
        return new Scenario(1200, List.of(new Bot(a, origin), new Bot(b, target)), course, server ->
        {
            ServerPlayer shooter = SelfTest.player(server, a);
            ServerPlayer victim = SelfTest.player(server, b);
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                int given = SelfTest.result(server, "give " + a + " minecraft:netherite_spear");
                int equipped = SelfTest.result(server, "player " + a + " equip mainhand minecraft:netherite_spear");
                if (given < 1 || equipped < 1)
                {
                    return new Probe(false, SelfTest.fmt("could not arm %s: give returned %d, equip returned %d",
                            a, given, equipped));
                }
                armed[0] = true;
                return SelfTest.pending(a + " is charging a spear while " + b + " walks into it");
            }
            double gap = Math.hypot(victim.getX() - shooter.getX(), victim.getZ() - shooter.getZ());
            if (shooter.isUsingItem())
            {
                charges[0]++;
            }
            fastest[0] = Math.max(fastest[0], closing(shooter, victim));
            if (gapAtReach[0] < 0.0D && charges[0] >= SPEAR_WIND_UP && spearReaches(shooter, victim)
                    && !swordReaches(shooter, victim))
            {
                gapAtReach[0] = gap;
                return new Probe(true, SelfTest.fmt(
                        "%s stood off with a charged spear at %.2f blocks, where the spear reaches and the %.2f "
                                + "a sword reaches does not; the closing speed between them peaked at %.1f "
                                + "blocks a second and the spear was up for %d ticks",
                        a, gap, DuelSim.REACH, fastest[0], charges[0]));
            }
            return SelfTest.pending(SelfTest.fmt("%.2f blocks apart, spear reaches: %s, sword reaches: %s, "
                            + "closing %.1f at best, spear up for %d ticks", gap, spearReaches(shooter, victim),
                    swordReaches(shooter, victim), fastest[0], charges[0]));
        });
    }

    /** True when the live target is inside the reach of the spear the bot is holding. */
    private static boolean spearReaches(ServerPlayer bot, ServerPlayer target)
    {
        AABB box = target.getBoundingBox();
        return SpearMath.inReach(bot.getX(), bot.getEyeY(), bot.getZ(), box.minX, box.minY,
                box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /** True when the same target is inside the reach of a sword, which is the interaction range. */
    private static boolean swordReaches(ServerPlayer bot, ServerPlayer target)
    {
        return DuelSim.inReach(bot.getX(), bot.getEyeY(), bot.getZ(), target.getX(),
                target.getY(), target.getZ());
    }

    // --- the tnt minecart

    /**
     * A tnt minecart laid next to a target that is hemmed in: the bot puts a rail down beside it, the blast is
     * worth more than the bot expects to pay for it, and the bot then walks away until the blast can no longer
     * reach where it is standing and it does not light the cart from inside it.
     */
    static Scenario tntCartSafe(String a, String b, String c, Vec3 origin)
    {
        int x = (int) origin.x;
        int z = (int) origin.z;
        // The target stands in the corner two walls make, so it cannot get out from under the cart.
        int wallWestX = x + 9;
        int wallNorthZ = z + 11;
        Vec3 shooter = new Vec3(x + 12.5D, SelfTest.SURFACE_Y, z + 9.5D);
        Vec3 target = new Vec3(x + 10.5D, SelfTest.SURFACE_Y, z + 10.5D);
        boolean[] armed = {false};
        boolean[] hard = {false};
        double[] damage = {0.0D};
        double[] standOff = {0.0D};
        List<String> course = new ArrayList<>(List.of(
                SelfTest.forceload(x, z, x + 24, z + 24),
                SelfTest.fill(wallWestX, -60, z + 10, wallWestX, -59, z + 10, "minecraft:obsidian"),
                SelfTest.fill(x + 10, -60, wallNorthZ, x + 10, -59, wallNorthZ, "minecraft:obsidian"),
                "player " + a + " equip mainhand minecraft:bow",
                // A flaming bow is the only thing a player can set a tnt minecart off with.
                "enchant " + a + " flame 1",
                "give " + a + " minecraft:tnt_minecart",
                "give " + a + " minecraft:rail 8",
                "give " + a + " minecraft:arrow 32",
                "player " + a + " equip head minecraft:diamond_helmet",
                "player " + a + " equip chest minecraft:diamond_chestplate",
                "player " + a + " equip legs minecraft:diamond_leggings",
                "player " + a + " equip feet minecraft:diamond_boots",
                "bot option " + a + " combatstyle ranged",
                "bot option " + a + " difficulty expert",
                "bot option " + a + " targetrange 24",
                "bot option " + a + " ranged.keep 8",
                "bot option " + a + " combat true"));
        return new Scenario(1200, List.of(new Bot(a, shooter), new Bot(b, target)), course, server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            ServerPlayer victim = SelfTest.player(server, b);
            if (!hard[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                // A blast does nothing at all on a peaceful server, whatever the bot thinks of itself.
                SelfTest.run(server, "difficulty normal");
                hard[0] = true;
                return SelfTest.pending("the server is on normal so a blast can hurt");
            }
            MinecartTNT cart = null;
            for (MinecartTNT mine : server.overworld().getEntitiesOfClass(MinecartTNT.class,
                            new AABB(BlockPos.containing(origin.x, SelfTest.SURFACE_Y,
                                    origin.z + 10.0D)).inflate(24.0D)))
            {
                cart = mine;
            }
            if (cart == null)
            {
                return SelfTest.pending(SelfTest.fmt("%s is looking for somewhere to lay a cart, %d rails and "
                                + "%d carts in hand", a, count(bot, Items.RAIL), count(bot, Items.TNT_MINECART)));
            }
            double gap = Math.hypot(cart.getX() - bot.getX(), cart.getZ() - bot.getZ());
            standOff[0] = Math.max(standOff[0], gap);
            if (damage[0] <= 0.0D)
            {
                damage[0] = blastOn(victim, cart);
            }
            boolean safe = gap >= SAFE_TNT_STANDOFF;
            SelfTest.run(server, "difficulty peaceful");
            boolean ok = damage[0] > 0.0D && safe && bot.getHealth() >= 20.0F;
            return new Probe(ok, SelfTest.fmt(
                    "%s laid a rail and a tnt minecart %s next to %s, which the model says the blast is worth "
                            + "%.1f health on, and then walked %.1f blocks away, where a blast of that power "
                            + "no longer reaches it; it is on %.1f health and the cart is still standing",
                    a, SelfTest.fmt("%.1f blocks", Math.hypot(cart.getX() - victim.getX(), cart.getZ() - victim.getZ())),
                    b, damage[0], standOff[0], bot.getHealth()));
        });
    }

    /** Distance beyond which a blast of the power the plan scores cannot reach a fighter in diamond armour. */
    private static final double SAFE_TNT_STANDOFF = 8.0;

    /** What the model says the blast of the cart standing at this place would do to the target. */
    private static double blastOn(ServerPlayer victim, net.minecraft.world.entity.vehicle.minecart.MinecartTNT cart)
    {
        LevelExplosionView view = new LevelExplosionView(
                victim.level() instanceof ServerLevel level ? level : null);
        CrystalSearch.Side side = new CrystalSearch.Side();
        side.x = victim.getX();
        side.y = victim.getY();
        side.z = victim.getZ();
        side.health = victim.getHealth();
        return new TntCartPlan(view)
                .damage(side, cart.getX(), cart.getY(), cart.getZ(), TntCartPlan.POWER_MEAN,
                        CombatMath.NORMAL);
    }

    // --- the style as a whole

    /** Against a melee bot the ranged one keeps its distance and only puts its sword up once it has to. */
    static Scenario rangedKeepsDistance(String a, String b, String c, Vec3 origin)
    {
        int x = (int) origin.x;
        int z = (int) origin.z;
        boolean[] armed = {false};
        int[] farTicks = {0};
        int[] closest = {Integer.MAX_VALUE};
        int[] switched = {-1};
        List<String> course = new ArrayList<>(List.of(
                SelfTest.forceload(x - 2, z - 2, x + 16, z + 16),
                SelfTest.fill(x - 2, -60, z - 2, x + 14, -58, z - 2, "minecraft:stone"),
                SelfTest.fill(x - 2, -60, z + 14, x + 14, -58, z + 14, "minecraft:stone"),
                SelfTest.fill(x - 2, -60, z - 2, x - 2, -58, z + 14, "minecraft:stone"),
                SelfTest.fill(x + 14, -60, z - 2, x + 14, -58, z + 14, "minecraft:stone"),
                "bot kit give " + a + " ranged",
                "bot option " + a + " combatstyle ranged",
                "bot option " + a + " difficulty expert",
                "bot option " + a + " combat true",
                "bot option " + a + " targetrange 32"));
        course.remove(course.size() - 1);
        course.addAll(List.of("player " + a + " equip mainhand minecraft:diamond_sword",
                "player " + a + " equip head minecraft:diamond_helmet",
                "player " + a + " equip chest minecraft:diamond_chestplate",
                "player " + a + " equip legs minecraft:diamond_leggings",
                "player " + a + " equip feet minecraft:diamond_boots",
                "player " + b + " equip mainhand minecraft:diamond_sword",
                "player " + b + " equip head minecraft:diamond_helmet",
                "player " + b + " equip chest minecraft:diamond_chestplate",
                "player " + b + " equip legs minecraft:diamond_leggings",
                "player " + b + " equip feet minecraft:diamond_boots",
                "bot option " + b + " combatstyle sword",
                "bot option " + b + " difficulty casual",
                "bot option " + b + " combat true",
                "bot option " + b + " targetrange 32"));
        List<Bot> bots = List.of(new Bot(a, new Vec3(x + 2.5D, SelfTest.SURFACE_Y, z + 2.5D)),
                new Bot(b, new Vec3(x + 9.5D, SelfTest.SURFACE_Y, z + 9.5D)));
        return new Scenario(1200, bots, course, server ->
        {
            ServerPlayer kiter = SelfTest.player(server, a);
            ServerPlayer chaser = SelfTest.player(server, b);
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                armed[0] = true;
                return SelfTest.pending(a + " is kiting and " + b + " is coming for it");
            }
            if (SelfTest.body(server, a) == null)
            {
                return SelfTest.pending("waiting for " + a + " to start fighting");
            }
            double gap = kiter.distanceTo(chaser);
            boolean shooting = kiter.getMainHandItem().is(Items.BOW) || kiter.getMainHandItem().is(Items.CROSSBOW);
            if (shooting)
            {
                closest[0] = (int) Math.min(closest[0], Math.floor(gap));
                if (gap > 6.0D)
                {
                    farTicks[0]++;
                }
            }
            else if (switched[0] < 0)
            {
                switched[0] = 1;
            }
            carpet.pvp.BotStats taken = SelfTest.stats(kiter);
            if (switched[0] < 0)
            {
                return SelfTest.pending(SelfTest.fmt("still shooting, %.1f blocks apart, closest %.1f",
                        gap, closest[0] == Integer.MAX_VALUE ? -1.0D : closest[0]));
            }
            boolean keptRange = farTicks[0] >= 20;
            boolean stayedOutOfMelee = closest[0] == Integer.MAX_VALUE || closest[0] >= 3;
            boolean foughtBack = taken.hits >= 1 && taken.damageDealt > 0.0D;
            return new Probe(keptRange && stayedOutOfMelee && foughtBack, SelfTest.fmt(
                    "held 6 blocks or more for %d ticks while shooting, closed no further than %.1f blocks, "
                            + "then took out its sword and landed %d hits for %.1f damage; it is on %.1f health "
                            + "after %d clicks, %d of which missed; it holds %s, its charge is %.2f and the "
                            + "gap is %.2f",
                    farTicks[0], closest[0] == Integer.MAX_VALUE ? -1.0D : closest[0], taken.hits,
                    taken.damageDealt, kiter.getHealth(), taken.clicks, taken.misses,
                    kiter.getMainHandItem().getItem(), kiter.getAttackStrengthScale(0.5F), gap));
        });
    }

    /** An expert ranged bot puts damage into an expert sword bot before either of them is in sword range. */
    static Scenario rangedDuel(String a, String b, String c, Vec3 origin)
    {
        boolean[] armed = {false};
        int[] damageTick = {-1};
        int[] switchTick = {-1};
        float[] healthAtHit = {20.0F};
        int[] tick = {0};
        List<String> course = List.of("bot option " + a + " combatstyle ranged",
                "bot option " + a + " difficulty expert",
                "bot option " + a + " combat true",
                "bot option " + a + " targetrange 40",
                "player " + a + " equip mainhand minecraft:bow",
                "give " + a + " minecraft:arrow 64",
                "give " + a + " minecraft:diamond_sword",
                "player " + a + " equip head minecraft:diamond_helmet",
                "player " + a + " equip chest minecraft:diamond_chestplate",
                "player " + a + " equip legs minecraft:diamond_leggings",
                "player " + a + " equip feet minecraft:diamond_boots",
                "player " + b + " equip mainhand minecraft:diamond_sword",
                "player " + b + " equip head minecraft:diamond_helmet",
                "player " + b + " equip chest minecraft:diamond_chestplate",
                "player " + b + " equip legs minecraft:diamond_leggings",
                "player " + b + " equip feet minecraft:diamond_boots",
                "bot option " + b + " combatstyle sword",
                "bot option " + b + " difficulty expert",
                "bot option " + b + " combat true",
                "bot option " + b + " targetrange 40",
                "bot duel " + a + " " + b);
        List<Bot> bots = List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 30.0D)));
        return new Scenario(1200, bots, course, server ->
        {
            tick[0]++;
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                armed[0] = true;
                return SelfTest.pending(a + " and " + b + " are thirty blocks apart on open ground");
            }
            ServerPlayer shooter = SelfTest.player(server, a);
            ServerPlayer victim = SelfTest.player(server, b);
            if (damageTick[0] < 0 && victim.getHealth() < 20.0F)
            {
                damageTick[0] = tick[0];
                healthAtHit[0] = victim.getHealth();
            }
            if (switchTick[0] < 0 && shooter.getMainHandItem().is(Items.DIAMOND_SWORD))
            {
                switchTick[0] = tick[0];
            }
            if (switchTick[0] < 0)
            {
                return SelfTest.pending(SelfTest.fmt("tick %d, %.1f blocks apart, no damage yet", tick[0],
                        shooter.distanceTo(victim)));
            }
            boolean first = damageTick[0] > 0 && damageTick[0] < switchTick[0];
            return new Probe(first, SelfTest.fmt(
                    "%s first hurt %s on tick %d, taking it to %.1f health, and only had to put the sword "
                            + "away on tick %d, %d ticks later; they are now %.1f blocks apart",
                    a, b, damageTick[0], healthAtHit[0], switchTick[0], switchTick[0] - damageTick[0],
                    shooter.distanceTo(victim)));
        });
    }

    /** The kit the ranged style is spawned with has everything it needs to fight from a distance. */
    static Scenario rangedKit(String a, String b, String c, Vec3 origin)
    {
        boolean[] armed = {false};
        return new Scenario(300, List.of(new Bot(a, origin)), List.of("bot kit give " + a + " ranged"), server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a))
                {
                    return SelfTest.pending("waiting for " + a + " to finish loading");
                }
                armed[0] = true;
                return SelfTest.pending("gave " + a + " the ranged kit");
            }
            int arrows = count(bot, Items.ARROW);
            boolean bow = hasItem(bot, Items.BOW);
            boolean crossbow = hasItem(bot, Items.CROSSBOW);
            boolean sword = hasSword(bot);
            boolean armour = bot.getArmorValue() > 0.0F;
            boolean ok = bow && crossbow && sword && armour && arrows >= 32;
            return new Probe(ok, SelfTest.fmt(
                    "the ranged kit gave %s a bow: %s, a crossbow: %s, %d arrows, a sword: %s, %d armour",
                    a, bow, crossbow, arrows, sword, bot.getArmorValue()));
        });
    }

    // --- helpers

    /**
     * Counts the hits a fighter takes. The hurt time is the game's own answer and says yes even when an effect
     * takes all of the damage away, which is what lets a scenario watch a whole fight without ending it.
     */
    private static final class Hits
    {
        private int count;
        private int hurt;
        private float health = 20.0F;

        int take(ServerPlayer victim)
        {
            if (victim.getHealth() > health)
            {
                // A respawn, which the resistance the bow scenarios use should stop from happening at all.
                health = victim.getHealth();
                hurt = 0;
                return 0;
            }
            health = victim.getHealth();
            if (victim.hurtTime == 0)
            {
                hurt = 0;
                return 0;
            }
            if (hurt == 0)
            {
                hurt = 1;
                count++;
            }
            return count;
        }
    }

    /** Commands that turn a spawned fake player into an expert ranged fighter at the given kit. */
    private static List<String> rangedCourse(String name)
    {
        return List.of("bot kit give " + name + " ranged",
                "bot option " + name + " combatstyle ranged",
                "bot option " + name + " difficulty expert",
                "bot option " + name + " targetrange 32",
                "bot option " + name + " combat true");
    }

    /**
     * How fast two fighters close on each other along the bot's view, blocks a second, which is the number the
     * game puts the spear's thrust behind. Read from the movement each of them has actually made, as the game
     * reads it, rather than from the velocity they are about to move with.
     */
    private static double closing(ServerPlayer bot, ServerPlayer target)
    {
        double[] look = ProjectileAim.direction(bot.getYRot(), bot.getXRot());
        Vec3 mine = bot.getKnownSpeed();
        Vec3 theirs = target.getKnownSpeed();
        return Math.max(0.0D, dot(mine.x, mine.y, mine.z, look) - dot(theirs.x, theirs.y, theirs.z, look)) * 20.0D;
    }

    private static double dot(double x, double y, double z, double[] direction)
    {
        return x * direction[0] + y * direction[1] + z * direction[2];
    }

    /** The angle between where a bot looks and where its target is, in the horizontal plane. */
    private static double angle(ServerPlayer bot, ServerPlayer target)
    {
        double wanted = Math.toDegrees(Math.atan2(target.getZ() - bot.getZ(), target.getX() - bot.getX())) - 90.0D;
        double difference = wanted - bot.getYRot();
        return Math.abs(difference - 360.0D * Math.round(difference / 360.0D));
    }

    /** The crossbow in the bot's inventory, or nothing. */
    private static ItemStack crossbowOf(ServerPlayer bot)
    {
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
        {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.CROSSBOW))
            {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean hasItem(ServerPlayer bot, Item item)
    {
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
        {
            if (inventory.getItem(slot).is(item))
            {
                return true;
            }
        }
        return false;
    }

    /** True while the bot carries anything a sword style would fight with. */
    private static boolean hasSword(ServerPlayer bot)
    {
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
        {
            if (inventory.getItem(slot).is(ItemTags.SWORDS))
            {
                return true;
            }
        }
        return false;
    }

    private static int count(ServerPlayer bot, Item item)
    {
        Inventory inventory = bot.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
        {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item))
            {
                total += stack.getCount();
            }
        }
        return total;
    }
}
