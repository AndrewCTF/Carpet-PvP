package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RollingHorizonTest
{
    private static final int HORIZON = 10;
    private static final int POPULATION = 10;
    private static final int BUDGET = 3000;
    private static final int DUELS = 40;
    private static final int MAX_TICKS = 600;

    private static DuelSim newDuel(Random rng)
    {
        DuelSim sim = new DuelSim();
        sim.a.setLoadout(8.0, 1.6, 0.0f, 20.0f, 8.0f, 0.0f, 0.0);
        sim.b.setLoadout(8.0, 1.6, 0.0f, 20.0f, 8.0f, 0.0f, 0.0);
        sim.placeFacing(4.0 + rng.nextDouble() * 4.0);
        return sim;
    }

    /** Walks at the opponent sprinting (re-sprinting after a sprint hit) and attacks whenever charged and in reach. */
    private static int baseline(DuelSim sim, int who)
    {
        DuelSim.Fighter me = sim.fighter(who);
        DuelSim.Fighter you = sim.fighter(1 - who);
        boolean attack = me.ticksSinceSwing >= me.gateTicks && DuelSim.inReach(me, you);
        return DuelSim.action(1, 0, false, !me.sprintLocked, attack);
    }

    /** Result {win, trace hash, planner hp bits, baseline hp bits, ticks planned, calls}; a win is a kill with the planner alive, or more health at the time limit. */
    private static long[] duel(long seed, boolean plannerIsA)
    {
        Random rng = new Random(seed);
        DuelSim sim = newDuel(rng);
        RollingHorizon planner = new RollingHorizon(HORIZON, POPULATION, new Random(seed * 31 + 7));
        OpponentModel model = new OpponentModel();
        int me = plannerIsA ? 0 : 1;
        long trace = 17;
        long plannedTicks = 0;
        int calls = 0;
        for (int t = 0; t < MAX_TICKS && !sim.over(); t++)
        {
            int mine = planner.plan(sim, me, model, BUDGET);
            plannedTicks += planner.ticksUsed();
            calls++;
            int theirs = baseline(sim, 1 - me);
            model.observe(sim, 1 - me, theirs);
            trace = trace * 31 + mine;
            if (plannerIsA)
            {
                sim.step(mine, theirs);
            }
            else
            {
                sim.step(theirs, mine);
            }
        }
        float mineHp = sim.fighter(me).health;
        float theirHp = sim.fighter(1 - me).health;
        boolean timeout = mineHp > 0 && theirHp > 0;
        long win = (timeout ? mineHp > theirHp : mineHp > 0) ? 1 : 0;
        return new long[] {win, trace, Float.floatToIntBits(mineHp), Float.floatToIntBits(theirHp), plannedTicks, calls};
    }

    @Test
    void budgetIsRespectedExactly()
    {
        Random rng = new Random(5);
        DuelSim sim = newDuel(rng);
        OpponentModel model = new OpponentModel();
        RollingHorizon planner = new RollingHorizon(HORIZON, POPULATION, new Random(1));
        for (int budget : new int[] {0, 5, 11, 12, 13, 100, 600, 1001})
        {
            long before = planner.scratch().steps;
            planner.plan(sim, 0, model, budget);
            long spent = planner.scratch().steps - before;
            assertEquals(spent, planner.ticksUsed(), "counted steps equal reported ticks");
            assertTrue(spent <= budget, "spent " + spent + " of " + budget);
            assertEquals((budget / HORIZON) * HORIZON, spent, "every whole rollout that fits is run");
            sim.step(DuelSim.NOOP, DuelSim.NOOP);
        }
    }

    @Test
    void sameSeedSameResult()
    {
        long[] first = duel(123, true);
        long[] second = duel(123, true);
        assertEquals(first[1], second[1]);
        assertEquals(first[2], second[2]);
        assertEquals(first[3], second[3]);
    }

    @Test
    void plannerBeatsChargeAndSprintBaseline()
    {
        int wins = 0;
        long ticks = 0;
        long calls = 0;
        long start = System.nanoTime();
        for (int i = 0; i < DUELS; i++)
        {
            long[] r = duel(7000 + i, i % 2 == 0);
            wins += (int) r[0];
            ticks += r[4];
            calls += r[5];
        }
        double ms = (System.nanoTime() - start) / 1.0e6;
        System.out.printf("planner win rate %d/%d, %.1f simulated ticks per call, %.3f ms per call%n",
                wins, DUELS, (double) ticks / calls, ms / calls);
        assertTrue(wins >= DUELS * 0.7, "win rate " + wins + "/" + DUELS);
    }
}
