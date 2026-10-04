package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import carpet.pvp.sim.DuelSim;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * What the ranged style does with its feet: it closes the gap when it has decided to close it, at the speed of a
 * run, and it does it with its hand full of something.
 *
 * <p>The second half is the point of the scenario. A player who holds a draw, a crossbow load or a shield moves
 * at a fifth of their speed, which is what the client does with the movement keys of whoever is holding
 * something up, and a spear is the one item whose component says otherwise: {@code USE_EFFECTS} lets a charge be
 * run at full speed and sprinted with. A bot that throttles a charge the way it throttles a bow draw cannot
 * close while it charges, and the closing speed is the whole of a spear thrust.</p>
 */
final class RangedCloseScenarios
{
    /** How far apart the two start, in blocks. */
    private static final double START = 18.0D;
    /** How much of that the bot has to take off. */
    private static final double CLOSED = 6.0D;
    /** How fast it has to be closing at its best, in blocks a second, against a sprint's worth. */
    private static final double FAST = 4.0D;
    /** The fastest anything closes on the flat, so a knockback is not read as a run. */
    private static final double MAX_CLOSING = 12.0D;
    /** What a sprint is worth on the flat, in blocks a second: the acceleration over the friction of a tick. */
    private static final double SPRINT_SPEED = DuelSim.SPRINT_SPEED * DuelSim.INPUT_SCALE
            / (1.0D - DuelSim.BLOCK_FRICTION * DuelSim.AIR_DRAG) * 20.0D;

    private RangedCloseScenarios() {}

    /**
     * A ranged bot with a spear against a target standing still: the keep distance it is given puts it inside
     * the spear's approach, so every tick asks it to run in, and the gap has to close at the speed of a sprint.
     */
    static Scenario closesGround(String a, String b, String c, Vec3 origin)
    {
        Vec3 target = origin.add(0.0D, 0.0D, START);
        boolean[] armed = {false};
        double[] opened = {START};
        double[] lastGap = {START};
        double[] fastest = {0.0D};
        double[] closed = {START};
        List<String> course = List.of(
                "give " + a + " minecraft:netherite_spear",
                "player " + a + " equip mainhand minecraft:netherite_spear",
                "bot option " + a + " combatstyle ranged",
                "bot option " + a + " difficulty expert",
                "bot option " + a + " targetrange 32",
                "bot option " + a + " ranged.keep 4.5",
                "bot option " + a + " ranged.tntcart false",
                "bot option " + a + " combat true");
        return new Scenario(600, List.of(new Bot(a, origin), new Bot(b, target)), course, server ->
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
                // The bot has been fighting since the commands went in, so the gap it starts measuring from is
                // read here rather than the one the bots were spawned at.
                opened[0] = bot.distanceTo(victim);
                lastGap[0] = opened[0];
                return SelfTest.pending(a + " is " + SelfTest.fmt("%.1f", opened[0]) + " blocks from " + b
                        + " and is running at it with a charged spear");
            }
            double gap = bot.distanceTo(victim);
            // How fast the gap went down this tick, which is the number the technique is about: a walk is a
            // little over four blocks a second and a sprint a little over five and a half.
            fastest[0] = Math.max(fastest[0], Math.min(MAX_CLOSING, (lastGap[0] - gap) * 20.0D));
            closed[0] = Math.min(closed[0], gap);
            lastGap[0] = gap;
            if (closed[0] > CLOSED)
            {
                return SelfTest.pending(SelfTest.fmt("still %.1f blocks apart, closing at no more than %.1f "
                        + "blocks a second", closed[0], fastest[0]));
            }
            return new Probe(fastest[0] >= FAST, SelfTest.fmt(
                    "%s took %.1f of the %.1f blocks between them off at its best %.1f blocks a second, which "
                            + "is %.0f%% of the %.1f a sprint is worth on the flat, while it had a netherite spear "
                            + "charged in its hand and got to %.1f blocks",
                    a, START - closed[0], START, fastest[0],
                    100.0D * fastest[0] / SPRINT_SPEED, SPRINT_SPEED, closed[0]));
        });
    }
}