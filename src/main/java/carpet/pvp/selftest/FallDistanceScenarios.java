package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import carpet.pvp.sim.DuelSim;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A fake player must build its fall distance exactly as a real player does, because everything a mace is
 * priced off is a number of fallen blocks. The game counts the downward part of each tick's own movement in
 * {@code Entity.move}, through {@code checkFallDamage}, and zeroes the counter on the landing tick, so the
 * counter is the height dropped. This scenario drops a fake player a known height with no velocity and
 * checks two things: the counter it read on the way down is the height a player falls, and the fall damage
 * it took is the one {@code LivingEntity.calculateFallDamage} gives for that counter.
 */
final class FallDistanceScenarios
{
    /** Height the fighter is dropped from, which is long enough for a landing above three blocks of it. */
    private static final double DROP = 8.0;
    /** Attributes.SAFE_FALL_DISTANCE, the part of a fall a player does not pay for. */
    private static final double SAFE_FALL = 3.0;
    /** Blocks the counter and the model may differ by, which is the last tick's own step. */
    private static final double FALL_TOLERANCE = 0.35;
    /** Ticks the fighter is given to come down. */
    private static final int FALL_TIMEOUT = 120;

    private FallDistanceScenarios() {}

    static Scenario fallDistance(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        int[] ticks = {0};
        double[] top = {0.0};
        double[] highest = {0.0};
        return new Scenario(400, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 8.0D))), List.of(), server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            if (bot == null) return SelfTest.pending(a + " has not joined yet");
            if (SelfTest.warmingUp(server, a, b))
            {
                return new Probe(false, SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
            }
            if (phase[0] == 0)
            {
                phase[0] = 3;
                return SelfTest.pending(a + " is settling after loading");
            }
            if (phase[0] == 3)
            {
                SelfTest.run(server, "clear " + a);
                bot.setHealth(bot.getMaxHealth());
                top[0] = bot.getY() + DROP;
                highest[0] = 0.0;
                // A teleport moves the fighter and clears its velocity but not its fall distance, so both are
                // put right here and the drop starts from rest, exactly as a jump off a ledge would.
                SelfTest.run(server, "tp " + a + " " + SelfTest.coords(new Vec3(origin.x, top[0], origin.z)));
                SelfTest.run(server, "data merge entity " + a + " {FallDistance:0.0d,Motion:[0.0d,0.0d,0.0d]}");
                phase[0] = 4;
                return new Probe(false, SelfTest.fmt("%s is %.1f blocks up and let go", a, DROP));
            }
            if (phase[0] == 4)
            {
                if (!bot.onGround())
                {
                    highest[0] = Math.max(highest[0], bot.fallDistance);
                    ticks[0] = 0;
                    if (++ticks[0] > FALL_TIMEOUT)
                    {
                        return SelfTest.pending(a + " never came down");
                    }
                    return SelfTest.pending(SelfTest.fmt("%s has %.2f blocks of fall and is %.2f up",
                            a, bot.fallDistance, bot.getY() - (top[0] - DROP)));
                }
                phase[0] = 5;
            }
            if (phase[0] == 5)
            {
                // The damage lands on the tick the fighter touches down, so it is read a few ticks later.
                if (--ticks[0] > 0)
                {
                    return SelfTest.pending(SelfTest.fmt("%s is down on %.1f health", a, bot.getHealth()));
                }
            }
            double read = highest[0];
            double dropped = top[0] - bot.getY();
            double model = modelFall();
            float taken = bot.getMaxHealth() - bot.getHealth();
            // The counter reaches the height dropped on the landing tick, which is the number
            // calculateFallDamage prices, and LivingEntity.checkFallDamage zeroes it right after.
            int expected = (int) Math.floor(Math.max(0.0, dropped - SAFE_FALL));
            boolean countRight = Math.abs(read - model) <= FALL_TOLERANCE;
            boolean damageRight = Math.abs(taken - expected) <= 0.0F;
            boolean ok = countRight && damageRight;
            return new Probe(ok, SelfTest.fmt(
                    "%s fell %.2f blocks, read %.2f of fall distance on the way down where a player reads %.2f,"
                            + " and took %.0f of fall damage where a player takes %d",
                    a, dropped, read, model, taken, expected));
        });
    }

    /**
     * The fall distance the duel simulator gives for the same drop: it counts the downward part of each
     * tick's own movement and stops on the landing tick, which is what {@code Entity.move} does.
     */
    private static double modelFall()
    {
        DuelSim sim = new DuelSim();
        sim.placeFacing(1000.0);
        sim.a.y = DROP;
        sim.a.onGround = false;
        sim.a.vy = 0.0;
        double last = 0.0;
        for (int tick = 0; tick < FALL_TIMEOUT && !sim.a.onGround; tick++)
        {
            last = sim.a.fallDistance;
            sim.step(DuelSim.NOOP, DuelSim.NOOP);
        }
        return last;
    }
}
