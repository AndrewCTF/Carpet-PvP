package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DifficultyPresetsTest
{
    /** Seeds the ladder was not chosen on. */
    private static final long HELD_OUT_SEEDS = 5_004_000L;
    private static final int DUELS_PER_PAIR = 16;
    /** The weakest adjacent step measured on three held-out seed bases was 0.64, so 0.60 is the stated bar. */
    private static final double STEP_THRESHOLD = 0.60;

    private static League.Combatant fighter(DifficultyPreset preset)
    {
        return new League.Combatant(preset.name(), () -> new PresetFighter(preset, new Random(99)));
    }

    private static League.Table ladder()
    {
        List<League.Combatant> roster = new ArrayList<>();
        for (DifficultyPreset preset : DifficultyPresets.ladder())
        {
            roster.add(fighter(preset));
        }
        return League.run(roster, DUELS_PER_PAIR, HELD_OUT_SEEDS);
    }

    @Test
    void everyLevelBeatsTheOneBelowItOnHeldOutSeeds()
    {
        League.Table table = ladder();
        List<DifficultyPreset> presets = DifficultyPresets.ladder();
        assertEquals(5, presets.size(), "five levels");
        for (int i = 1; i < presets.size(); i++)
        {
            int stronger = i;
            int weaker = i - 1;
            double winRate = table.winRate(stronger, weaker);
            double margin = table.margin()[stronger][weaker];
            System.out.printf("ladder %s beats %s %d of %d, average health %+.2f%n",
                    presets.get(stronger).name(), presets.get(weaker).name(),
                    table.wins()[stronger][weaker], table.played()[stronger][weaker], margin);
            assertTrue(winRate >= STEP_THRESHOLD, String.format("%s against %s: win rate %.3f, want %.2f",
                    presets.get(stronger).name(), presets.get(weaker).name(), winRate, STEP_THRESHOLD));
            assertTrue(margin > 0.0, presets.get(stronger).name() + " is behind " + presets.get(weaker).name());
        }
    }

    @Test
    void theLadderIsOrderedByConstructionToo()
    {
        List<DifficultyPreset> presets = DifficultyPresets.ladder();
        int previousBudget = 0;
        for (DifficultyPreset preset : presets)
        {
            assertTrue(preset.reactionDelay() >= 0, preset.name());
            assertTrue(preset.missChance() >= 0.0 && preset.missChance() <= 1.0, preset.name());
            assertTrue(preset.params().budgetTicks() >= previousBudget, "the search budget only goes up the ladder");
            previousBudget = preset.params().budgetTicks();
        }
        for (int i = 1; i < presets.size(); i++)
        {
            assertTrue(presets.get(i).reactionDelay() <= presets.get(i - 1).reactionDelay(),
                    presets.get(i).name() + " reacts no faster than " + presets.get(i - 1).name());
            assertTrue(presets.get(i).missChance() <= presets.get(i - 1).missChance(),
                    presets.get(i).name() + " misses no more often than " + presets.get(i - 1).name());
        }
        assertEquals(0, presets.get(presets.size() - 1).reactionDelay(), "the top level has no reaction lag");
    }

    /** A planner that cannot afford a single rollout always queues the same walk-and-swing action. */
    private static final PlannerParams BARE = new PlannerParams(10, 4, 5, 0.15, 1.0, 1.0, 0.05, 2.6);
    private static final int APPROACH = DuelSim.action(1, 0, false, true, false);
    private static final int SWING = DuelSim.action(1, 0, false, true, true);

    private static int[] actionsOver(DifficultyPreset preset, int ticks)
    {
        DuelSim sim = Duel.newDuel(3L);
        PresetFighter fighter = new PresetFighter(preset, new Random(7));
        int[] out = new int[ticks];
        for (int t = 0; t < ticks; t++)
        {
            out[t] = fighter.act(sim, 0);
            sim.step(out[t], DuelSim.NOOP);
        }
        return out;
    }

    @Test
    void reactionDelayHoldsBackTheFirstSwings()
    {
        int[] held = actionsOver(new DifficultyPreset("delayed", BARE, 3, 0.0), 6);
        for (int t = 0; t < 3; t++)
        {
            assertEquals(APPROACH, held[t], "tick " + t + " is still the opening action");
        }
        for (int t = 3; t < 6; t++)
        {
            assertEquals(SWING, held[t], "tick " + t + " is the plan from three ticks ago");
        }
        int[] immediate = actionsOver(new DifficultyPreset("immediate", BARE, 0, 0.0), 3);
        for (int t = 0; t < 3; t++)
        {
            assertEquals(SWING, immediate[t], "tick " + t + " with no delay is the plan of this tick");
        }
    }

    @Test
    void missChanceTakesSwingsOff()
    {
        int[] neverMissing = actionsOver(new DifficultyPreset("sharp", BARE, 0, 0.0), 20);
        int[] alwaysMissing = actionsOver(new DifficultyPreset("flaky", BARE, 0, 1.0), 20);
        int sharpSwings = 0;
        int flakySwings = 0;
        for (int t = 0; t < 20; t++)
        {
            sharpSwings += DuelSim.attack(neverMissing[t]) ? 1 : 0;
            flakySwings += DuelSim.attack(alwaysMissing[t]) ? 1 : 0;
        }
        assertEquals(20, sharpSwings, "nothing is missed at a miss chance of zero");
        assertTrue(flakySwings <= 2, "swings still get out at a miss chance of one: " + flakySwings);
        assertTrue(flakySwings < sharpSwings, "missing costs swings");
    }

    @Test
    void aFighterThatMissesEverythingLandsNothing()
    {
        PresetFighter blind = new PresetFighter(new DifficultyPreset("blind", DifficultyPresets.BEST, 0, 1.0),
                new Random(5));
        BaselineOpponent target = new BaselineOpponent(new Random(6), 0);
        DuelSim sim = Duel.newDuel(4000L);
        for (int t = 0; t < Duel.DEFAULT_MAX_TICKS && !sim.over(); t++)
        {
            sim.step(blind.act(sim, 0), target.act(sim, 1));
        }
        assertEquals(DuelSim.MAX_HEALTH, sim.b.health, "not one swing came out");
        assertTrue(sim.a.health < DuelSim.MAX_HEALTH, "and the fighter paid for it");
    }

    @Test
    void theTopLevelIsTheTunedPlanner()
    {
        assertEquals(DifficultyPresets.BEST, DifficultyPresets.MASTER.params(),
                "the top level plays with the settings the search settled on");
        assertTrue(DifficultyPresets.BEGINNER.params().budgetTicks()
                        < DifficultyPresets.MASTER.params().budgetTicks(),
                "the bottom level gets a fraction of the search budget");
        assertTrue(DifficultyPresets.MASTER.missChance() < DifficultyPresets.BEGINNER.missChance(),
                "the top level misses less often than the bottom one");
    }
}