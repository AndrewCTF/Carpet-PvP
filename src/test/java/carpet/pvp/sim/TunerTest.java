package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TunerTest
{
    private static final long TUNING_SEEDS = 4000L;
    private static final long HELD_OUT_SEEDS = 5_004_000L;
    /** The search's own mechanics are tested on a quarter of the shipped planner budget to keep the suite fast. */
    private static final PlannerParams START = new PlannerParams(10, 10, 3000, 0.15, 1.0, 1.0, 0.05, 2.6);

    private static List<League.Combatant> opponents()
    {
        return LeagueTest.scripted();
    }

    private static Tuner tuner(int duelsPerOpponent, long seedBase)
    {
        return new Tuner(opponents(), duelsPerOpponent, seedBase, Duel.DEFAULT_MAX_TICKS, 99);
    }

    @Test
    void theSameSeedGivesTheSameSearch()
    {
        Tuner.Result first = tuner(2, TUNING_SEEDS).tune(START, 4, 3, null);
        Tuner.Result second = tuner(2, TUNING_SEEDS).tune(START, 4, 3, null);
        assertEquals(first.best(), second.best(), "same parameters out");
        assertEquals(first.bestScore(), second.bestScore(), 0.0, "same score out");
        assertEquals(first.evaluations(), second.evaluations());
        assertEquals(1 + 4 * 3, first.evaluations(), "the start plus one child per generation");
    }

    @Test
    void theSearchStaysInsideTheAllowedRanges()
    {
        Tuner.Result result = tuner(2, TUNING_SEEDS).tune(START, 8, 4, null);
        PlannerParams best = result.best();
        assertEquals(best, best.clamp(), "nothing outside the ranges the search is allowed to explore");
        assertTrue(best.horizon() >= 4 && best.horizon() <= 32, "horizon " + best.horizon());
        assertTrue(best.population() >= 4 && best.population() <= 48, "population " + best.population());
        assertTrue(best.mutationRate() > 0.0 && best.mutationRate() < 0.9, "mutation rate " + best.mutationRate());
        assertEquals(START.budgetTicks(), best.budgetTicks(), "the budget is held fixed");
        assertTrue(result.bestScore() >= result.startScore(), "a (1+lambda) search never gives up ground");
    }

    @Test
    void theSearchMovesWhenItIsGivenRoom()
    {
        // Three generations of twelve candidates over 64 duels is enough to see the search leave its start.
        Tuner.Result result = tuner(4, TUNING_SEEDS).tune(START, 12, 3, null);
        assertTrue(result.bestScore() > result.startScore(),
                "tuning score went from " + result.startScore() + " to " + result.bestScore());
        assertNotEquals(result.start(), result.best());
    }

    @Test
    void theTunedSettingsBeatTheDefaultsOnHeldOutSeeds()
    {
        double defaults = tuner(8, HELD_OUT_SEEDS).score(PlannerParams.DEFAULTS);
        double tuned = tuner(8, HELD_OUT_SEEDS).score(DifficultyPresets.BEST);
        assertTrue(tuned > defaults + 0.02,
                String.format("league score on held-out seeds: defaults %+.4f, tuned %+.4f", defaults, tuned));
    }

    @Test
    void theScoreIsJustTheHealthLeftOver()
    {
        // A fighter that never attacks leaves its opponent at full health, so its score is exactly minus one.
        List<League.Combatant> idle = List.of(
                new League.Combatant("idle", () -> new DuelPolicy()
                {
                    @Override
                    public int act(DuelSim sim, int who)
                    {
                        return DuelSim.NOOP;
                    }
                }),
                new League.Combatant("baseline", () -> new BaselineOpponent(new Random(1), 0)));
        assertTrue(League.run(idle, 2, TUNING_SEEDS).margin()[0][1] < -DuelSim.MAX_HEALTH,
                "the idle fighter is killed without touching its opponent");
        List<League.Combatant> swapped = List.of(idle.get(1), idle.get(0));
        assertTrue(League.run(swapped, 2, TUNING_SEEDS).margin()[0][1] > DuelSim.MAX_HEALTH);
    }
}