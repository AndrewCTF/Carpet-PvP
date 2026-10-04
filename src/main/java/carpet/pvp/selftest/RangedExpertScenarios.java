package carpet.pvp.selftest;

import carpet.pvp.BotStats;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Two expert ranged bots, both spawned with the stock kit, fighting each other: the statistics have to show
 * every technique the style offers being used.
 *
 * <p>The counters are the ones the style books as it acts ({@code BotStats}'s shots block): a bow shot that
 * hit, a crossbow shot, a trident throw, a spear thrust on 26.x and a tnt minecart that went off. A bot that
 * used the whole kit of a style and none of its techniques would pass a scenario that only looked at damage,
 * which is what this one is for.</p>
 */
final class RangedExpertScenarios
{
    /** Ticks the duel is given to play out before the counters are read. */
    private static final int DUEL_TICKS = 1400;

    private RangedExpertScenarios()
    {
    }

    /**
     * The whole technique list in one fight. The stock kit carries a weapon for every distance and the style
     * picks the weapon by the distance it believes the two are apart, so the opponent is walked in and out:
     * two bots that kite each other settle at one distance and only ever use one technique, which would make
     * this a test of the kiting rather than of the kit.
     */
    static Scenario expertUsesTechniques(String a, String b, String c, Vec3 origin)
    {
        boolean[] armed = {false};
        boolean[] walking = {true};
        int[] tick = {0};
        List<String> course = List.of(
                "difficulty normal",
                "bot kit give " + a + " ranged",
                "bot option " + a + " combatstyle ranged",
                "bot option " + a + " difficulty expert",
                "bot option " + a + " targetrange 32",
                "bot option " + a + " ranged.keep 9",
                "bot option " + a + " combat true",
                "bot duel " + a + " " + b);
        return new Scenario(2400, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 14.0D))),
                course, server ->
        {
            ServerPlayer first = SelfTest.player(server, a);
            ServerPlayer second = SelfTest.player(server, b);
            tick[0]++;
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                armed[0] = true;
                return SelfTest.pending(a + " is fighting " + b + " with the stock kit");
            }
            if (tick[0] % 30 == 0)
            {
                // The opponent walks in and out on a thirty tick beat, which sweeps the gap across the bands
                // the style has a weapon for.
                SelfTest.run(server, SelfTest.cmd(b + " move " + (walking[0] ? "back" : "forward")
                        + " for 30"));
                walking[0] = !walking[0];
            }
            BotStats left = SelfTest.stats(first);
            BotStats right = SelfTest.stats(second);
            if (left == null || right == null)
            {
                return SelfTest.pending("the fighters have no statistics yet");
            }
            int arrows = left.arrowsHit + right.arrowsHit;
            int crossbows = left.crossbowShots + right.crossbowShots;
            int tridents = left.tridentsThrown + right.tridentsThrown;
            int thrusts = left.spearThrusts + right.spearThrusts;
            int lit = left.cartsLit + right.cartsLit;
            int laid = left.cartsLaid + right.cartsLaid;
            boolean arrowsUsed = left.arrowsShot + right.arrowsShot > 0 && arrows > 0;
            boolean crossbowUsed = crossbows > 0;
            boolean tridentUsed = tridents > 0;
            boolean thrustUsed = thrusts > 0;
            boolean cartUsed = lit > 0;
            // A bot that killed itself with its own trap would show up as a health loss with no fight behind it.
            boolean alive = first.isAlive() && second.isAlive();
            boolean done = tick[0] >= DUEL_TICKS;
            if (!done && !(cartUsed && arrowsUsed && crossbowUsed && tridentUsed && thrustUsed))
            {
                return SelfTest.pending(SelfTest.fmt(
                        "tick %d: %d arrows (%d hit), %d crossbow shots, %d tridents, %d spear thrusts, "
                                + "%d carts laid and %d lit; %s at %.1f health, %s at %.1f", tick[0],
                        left.arrowsShot + right.arrowsShot, arrows, crossbows, tridents, thrusts, laid, lit,
                        a, first.getHealth(), b, second.getHealth())
                        + " gap " + SelfTest.fmt("%.1f", first.distanceTo(second)));
            }
            SelfTest.run(server, "difficulty peaceful");
            boolean ok = alive && arrowsUsed && crossbowUsed && tridentUsed && thrustUsed && cartUsed;
            return new Probe(ok, SelfTest.fmt(
                    "%s fought %s for %d ticks with the stock kit: %d arrows of which %d hit, "
                            + "%d crossbow shots, %d tridents thrown, %d spear thrusts, %d carts laid and %d of "
                            + "them set off; %s is on %.1f health and %s on %.1f, and %s", a, b, tick[0],
                    left.arrowsShot + right.arrowsShot, arrows, crossbows, tridents, thrusts, laid, lit,
                    a, first.getHealth(), b, second.getHealth(),
                    alive ? "neither bot killed itself" : "one of them did not survive its own trap"));
        });
    }
}
