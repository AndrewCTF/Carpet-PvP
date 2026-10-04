package carpet.pvp.selftest;

import carpet.pvp.ranged.LevelExplosionView;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.CrystalSearch;
import carpet.pvp.sim.TntCartPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The whole of the tnt minecart trap, end to end: the bot lays a rail and a cart next to a target it cannot get
 * away from, walks out of its own blast, sets the cart off with a flaming arrow, and the blast lands on the target
 * for about what {@link TntCartPlan} said it would.
 *
 * <p>The tolerance the damage is held to is the game's own: {@code MinecartTNT.explode} rolls the power of the
 * blast, so no two runs give the same number, and the question is not whether the target lost exactly the mean
 * but whether it lost what a roll of that blast can be worth. The scenario reads where the cart stood and where
 * the target stood at the tick before the blast, asks the model what its low, mean and high power would do from
 * there, and holds the health the target actually lost between the low and the high, with a point of slack for
 * the rounding of a blast. The damage is checked to have come from an explosion, so a bow hit that happened to
 * land on the same tick cannot pass for one.</p>
 *
 * <p>The bot's own share is measured against the same model: the run fails if the blast killed it or took more
 * off it than the largest roll of that blast is worth.</p>
 */
final class TntCartBlastScenarios
{
    /** {@code Player.hurtServer} scales an explosion by the difficulty, and takes all of it away on peaceful. */
    private static final String HURTING = "difficulty normal";
    private static final String QUIET = "difficulty peaceful";
    /** Health of slack either side of the power's own range, for the rounding of a blast. */
    private static final float SLACK = 1.0F;

    private TntCartBlastScenarios()
    {
    }

    /**
     * A bot with a flame bow lays a trap beside a target hemmed into a corner, walks out of the blast and lights
     * it. The run passes when the cart went off, the target lost what the model says it should have, and the bot
     * is still standing.
     */
    static Scenario blastDamage(String a, String b, String c, Vec3 origin)
    {
        int x = (int) origin.x;
        int z = (int) origin.z;
        // The target stands in a corner of its own so it cannot get out from under the cart, and the bot starts
        // beside it with a clear line of sight: the bot has to keep seeing the target while it backs out of the
        // blast, which is what it spends most of the trap doing.
        Vec3 shooter = new Vec3(x + 12.5D, SelfTest.SURFACE_Y, z + 9.5D);
        Vec3 target = new Vec3(x + 9.5D, SelfTest.SURFACE_Y, z + 9.5D);
        boolean[] armed = {false};
        boolean[] sawCart = {false};
        float[] targetHealth = {20.0F};
        float[] botHealth = {20.0F};
        Vec3[] cartAt = {null};
        Vec3[] victimAt = {null};
        Vec3[] botAt = {null};
        int[] tick = {0};

        String[] note = {""};
        List<String> course = List.of(
                SelfTest.forceload(x, z, x + 24, z + 24),
                SelfTest.cmd(a + " equip mainhand minecraft:bow"),
                // A flaming bow is the only thing a player can set a tnt minecart off with.
                "enchant " + a + " flame 1",
                "give " + a + " minecraft:tnt_minecart",
                "give " + a + " minecraft:rail 8",
                "give " + a + " minecraft:arrow 32",
                SelfTest.cmd(a + " equip head minecraft:diamond_helmet"),
                SelfTest.cmd(a + " equip chest minecraft:diamond_chestplate"),
                SelfTest.cmd(a + " equip legs minecraft:diamond_leggings"),
                SelfTest.cmd(a + " equip feet minecraft:diamond_boots"),
                "bot option " + a + " combatstyle ranged",
                "bot option " + a + " difficulty expert",
                "bot option " + a + " targetrange 24",
                "bot option " + a + " ranged.keep 8",
                SelfTest.cmd(b + " equip mainhand minecraft:diamond_sword"),
                SelfTest.cmd(b + " equip head minecraft:diamond_helmet"),
                SelfTest.cmd(b + " equip chest minecraft:diamond_chestplate"),
                SelfTest.cmd(b + " equip legs minecraft:diamond_leggings"),
                SelfTest.cmd(b + " equip feet minecraft:diamond_boots"),
                // A blast does nothing at all on a peaceful server, whatever the bot thinks of itself, so the
                // rule has to be off before the bot starts fighting. The two fighters are duelled rather than
                // merely switched on, because two bots that share a faction will not look at each other.
                HURTING,
                // The two are duelled rather than merely switched on: /bot duel is what puts two bots in
                // different factions and turns their targeting on, and a bot that is not duelling anyone does
                // not go looking for a plain fake player at five blocks.
                "bot duel " + a + " " + b);
        return new Scenario(1500, List.of(new Bot(a, shooter), new Bot(b, target)), course, server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            ServerPlayer victim = SelfTest.player(server, b);
            tick[0]++;
            if (!armed[0])
            {
                // No wait for the fighters to finish loading: the bot lays its one cart and can be shooting at
                // it within a couple of ticks of starting, and a check that starts watching afterwards never
                // sees the trap at all.
                armed[0] = true;
                return SelfTest.pending("the server is on normal, so a blast can hurt");
            }
            MinecartTNT cart = nearest(bot, x, z);
            if (cart != null)
            {
                sawCart[0] = true;
                cartAt[0] = cart.position();
                victimAt[0] = victim.position();
                botAt[0] = bot.position();
                targetHealth[0] = victim.getHealth();
                botHealth[0] = bot.getHealth() + bot.getAbsorptionAmount();
                note[0] = SelfTest.fmt("a cart is standing %.1f blocks from %s, %.1f from %s, primed %s fuse %s; "
                                + "%d rails, %d carts and %d arrows left", distance(victimAt[0], cartAt[0]),
                        victim.getName().getString(), distance(botAt[0], cartAt[0]), bot.getName().getString(),
                        cart.isPrimed(), SelfTest.fmt("%d", cart.getFuse()), count(bot, Items.RAIL), count(bot, Items.TNT_MINECART),
                        count(bot, Items.ARROW));
                return SelfTest.pending(note[0]);
            }
            if (!sawCart[0])
            {
                note[0] = SelfTest.fmt("%s is looking for somewhere to lay a cart, %d rails and %d carts in hand, "
                                + "%d arrows, on difficulty %d; %s", a, count(bot, Items.RAIL),
                        count(bot, Items.TNT_MINECART), count(bot, Items.ARROW),
                        bot.level().getDifficulty().getId(), SelfTest.stats(bot).describe());
                return SelfTest.pending(tick[0] > 400 ? note[0] + ", and has not laid one after 400 ticks"
                        : note[0]);
            }
            // The cart is gone and there was one a tick ago: the shot set it off, and everything the blast did
            // landed this tick.
            float taken = targetHealth[0] - victim.getHealth();
            float botTaken = botHealth[0] - (bot.getHealth() + bot.getAbsorptionAmount());
            DamageSource source = victim.getLastDamageSource();
            boolean blasted = source != null && source.is(DamageTypeTags.IS_EXPLOSION);
            double low = blastOn(victimAt[0], cartAt[0], victim, TntCartPlan.POWER_LOW);
            double mean = blastOn(victimAt[0], cartAt[0], victim, TntCartPlan.POWER_MEAN);
            double high = blastOn(victimAt[0], cartAt[0], victim, TntCartPlan.POWER_HIGH);
            double botHigh = blastOn(botAt[0], cartAt[0], bot, TntCartPlan.POWER_HIGH);
            SelfTest.run(server, QUIET);
            boolean worth = blasted && taken > 0.0F && taken >= low - SLACK && taken <= high + SLACK;
            boolean survived = bot.isAlive() && botTaken <= botHigh + SLACK;
            return new Probe(worth && survived, SelfTest.fmt(
                    "%s laid a rail and a tnt minecart %.1f blocks from %s and set it off with a flaming arrow on "
                            + "tick %d: %s lost %.1f health to %s, where the model puts a cart at that place "
                            + "worth %.1f to %.1f with its mean %.1f, and the bot, standing %.1f away, took %.1f "
                            + "where the largest roll is worth %.1f and it is on %.1f health",
                    a, distance(victimAt[0], cartAt[0]), b, tick[0], b, taken,
                    source == null ? "nothing" : source.getMsgId(), low, high,
                    mean, distance(botAt[0], cartAt[0]), botTaken, botHigh, bot.getHealth())
                    + " | last seen " + note[0]);
        });
    }

    /** The cart nearest the bot, which is the one it laid: the bot fights where it stands, not where it started. */
    private static MinecartTNT nearest(ServerPlayer bot, int x, int z)
    {
        MinecartTNT cart = null;
        double nearest = Double.MAX_VALUE;
        for (MinecartTNT mine : bot.level().getEntitiesOfClass(MinecartTNT.class,
                new AABB(BlockPos.containing(bot.getX(), bot.getY(), bot.getZ())).inflate(24.0D)))
        {
            double away = mine.distanceTo(bot);
            if (away < nearest)
            {
                nearest = away;
                cart = mine;
            }
        }
        return cart;
    }

    private static double distance(Vec3 a, Vec3 b)
    {
        return a.distanceTo(b);
    }

    /** What the model says a blast of the given power would do to a fighter where it stood. */
    private static double blastOn(Vec3 where, Vec3 cart, ServerPlayer fighter, float power)
    {
        CrystalSearch.Side side = new CrystalSearch.Side();
        side.x = where.x;
        side.y = where.y;
        side.z = where.z;
        side.health = fighter.getHealth();
        side.armor = fighter.getArmorValue();
        side.toughness = (float) fighter.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
        side.epf = carpet.pvp.ranged.TntCart.protectionAgainstBlasts(fighter);
        return new TntCartPlan(new LevelExplosionView(
                fighter.level() instanceof ServerLevel level ? level : null))
                .damage(side, cart.x, cart.y, cart.z, power, CombatMath.NORMAL);
    }

    private static int count(ServerPlayer bot, Item item)
    {
        int total = 0;
        for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++)
        {
            if (bot.getInventory().getItem(slot).is(item))
            {
                total += bot.getInventory().getItem(slot).getCount();
            }
        }
        return total;
    }
}