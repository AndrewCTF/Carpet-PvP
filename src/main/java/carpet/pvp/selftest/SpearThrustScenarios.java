package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import carpet.pvp.sim.ProjectileAim;
import carpet.pvp.sim.SpearMath;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The thrust of a charged spear, and what it costs the target.
 *
 * <p>A spear is not aimed with a click: holding the use key charges it and the game sweeps the reach of the
 * weapon along the view on every tick of that charge. The damage it does is the attacker's plain base damage
 * plus the speed at which the two fighters close on each other along that view, so this scenario measures both
 * halves of the chain: that the bot really does carry the closing speed of a run into the thrust, and that the
 * health the target loses is the one {@link SpearMath#thrustDamage} gives for the speed the game used.</p>
 *
 * <p>The target wears no armour, so the number that lands on it is the model's own number with nothing taken
 * off it, and it is healed back up between thrusts so a long charge cannot end the fight half way through.</p>
 */
final class SpearThrustScenarios
{
    /** How close the target has to be for the bot to come at it with the spear rather than keep its distance. */
    private static final String KEEP = "4.5";
    /** How much health of one thrust may differ from what the model gives before the run is a failure. */
    private static final double TOLERANCE = 1.0D;
    /** Thrusts the bot has to land before the run is called a pass. */
    private static final int WANTED_THRUSTS = 2;
    /** Health the target is put back to between thrusts, by an instant heal. */
    private static final float HEALED = 20.0F;

    private SpearThrustScenarios() {}

    /**
     * A bot with a netherite spear against a target that stands still: the bot has to close the gap at the
     * speed of a run, get the charge up and land a thrust whose damage is what the model predicts.
     */
    static Scenario thrustDamage(String a, String b, String c, Vec3 origin)
    {
        Vec3 target = origin.add(0.0D, 0.0D, 9.0D);
        boolean[] armed = {false};
        float[] lastHealth = {HEALED};
        double[] closing = {0.0D};
        double[] lastClosing = {0.0D};
        double[] fastest = {0.0D};
        double[] predicted = {0.0D};
        double[] taken = {0.0D};
        int[] charges = {0};
        int[] thrusts = {0};
        int[] wrong = {0};
        List<String> course = List.of(
                "give " + a + " minecraft:netherite_spear",
                SelfTest.cmd(a + " equip mainhand minecraft:netherite_spear"),
                "bot option " + a + " combatstyle ranged",
                "bot option " + a + " difficulty expert",
                "bot option " + a + " targetrange 24",
                "bot option " + a + " ranged.keep " + KEEP,
                "bot option " + a + " ranged.tntcart false",
                "bot option " + a + " combat true");
        return new Scenario(900, List.of(new Bot(a, origin), new Bot(b, target)), course, server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            ServerPlayer victim = SelfTest.player(server, b);
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                armed[0] = true;
                return SelfTest.pending(a + " is running " + b + " down with a netherite spear");
            }
            // The game reads the closing speed off the motion each fighter had at the start of the tick it
            // thrusts on, so the prediction for a thrust is the speed of the tick before the one it lands on.
            lastClosing[0] = closing[0];
            closing[0] = closing(bot, victim);
            fastest[0] = Math.max(fastest[0], closing[0]);
            predicted[0] = modelOf(bot, lastClosing[0]);
            if (bot.isUsingItem())
            {
                charges[0]++;
            }
            float health = victim.getHealth();
            if (health < lastHealth[0])
            {
                thrusts[0]++;
                taken[0] = lastHealth[0] - health;
                if (Math.abs(taken[0] - predicted[0]) > TOLERANCE)
                {
                    wrong[0]++;
                }
            }
            lastHealth[0] = victim.getHealth();
            if (health < HEALED * 0.5F)
            {
                SelfTest.run(server, "effect give " + b + " minecraft:instant_health 1 3 true");
            }
            if (thrusts[0] < WANTED_THRUSTS)
            {
                return SelfTest.pending(SelfTest.fmt(
                        "%d thrusts, %.1f blocks apart, closing %.1f blocks a second at best, %d ticks of charge, "
                                + "the model gave %.1f",
                        thrusts[0], bot.distanceTo(victim), fastest[0], charges[0], predicted[0]));
            }
            return new Probe(wrong[0] == 0, SelfTest.fmt(
                    "%s ran at %s and thrust its netherite spear %d times at up to %.1f blocks a second of "
                            + "closing speed: the last thrust took %.1f health where the model gave %.1f, and %d "
                            + "of the %d thrusts were more than %.1f health off it",
                    a, b, thrusts[0], fastest[0], taken[0], predicted[0], wrong[0], thrusts[0], TOLERANCE));
        });
    }

    /** The health a thrust would cost, as {@link SpearMath} gives it for the closing speed the game would read. */
    private static float modelOf(ServerPlayer bot, double closingSpeed)
    {
        ItemStack spear = bot.getMainHandItem();
        KineticWeapon weapon = spear.get(DataComponents.KINETIC_WEAPON);
        if (weapon == null)
        {
            return 0.0F;
        }
        double base = bot.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        return SpearMath.thrustDamage(base, Math.max(0.0D, closingSpeed), weapon.damageMultiplier());
    }

    /** How fast the two close on each other along the bot's view, blocks a second, the way the game reads it. */
    private static double closing(ServerPlayer bot, ServerPlayer target)
    {
        double[] look = ProjectileAim.direction(bot.getYRot(), bot.getXRot());
        Vec3 mine = bot.getKnownSpeed();
        Vec3 theirs = target.getKnownSpeed();
        return Math.max(0.0D, dot(mine, look) - dot(theirs, look)) * SpearMath.TICKS_A_SECOND;
    }

    private static double dot(Vec3 motion, double[] direction)
    {
        return motion.x * direction[0] + motion.y * direction[1] + motion.z * direction[2];
    }
}