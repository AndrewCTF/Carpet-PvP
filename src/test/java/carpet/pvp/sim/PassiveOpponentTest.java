package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.style.Techniques;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The planner against an opponent that gives nothing back, which is the fight a bot is pointed at first: the
 * target stands still, or walks away, or walks a circle, and never fights. Every difficulty has to close the
 * distance and land a hit on its own, because a search that only finds something to do against an opponent
 * that answers back parks a few blocks out and looks at it.
 */
class PassiveOpponentTest
{
    /** Blocks the fighter starts off at, which is where the sword style hands the distance over to the planner. */
    private static final double START = 4.6;
    /** Ticks a fighter has to need before its first hit lands. */
    private static final int LIMIT = 80;

    /** What the opponent does, as the action it takes each tick in the duel. */
    private interface Opponent
    {
        int act(DuelSim sim);
    }

    /** Stands still and never swings, the target that used to be left alone. */
    private static final Opponent STILL = sim -> DuelSim.NOOP;
    /** Walks away from the fighter at a third of a sprint. */
    private static final Opponent WALKING_AWAY = sim -> DuelSim.action(1, 0, false, true, false);
    /** Circles the fighter without ever coming closer than it started. */
    private static final Opponent CIRCLING = sim -> {
        DuelSim.Fighter me = sim.fighter(1);
        DuelSim.Fighter you = sim.fighter(0);
        double dx = me.x - you.x;
        double dz = me.z - you.z;
        double len = Math.hypot(dx, dz);
        int strafe = dx * you.cosYaw - dz * you.sinYaw >= 0.0 ? 1 : -1;
        return DuelSim.action(len < START ? 1 : 0, strafe, false, true, false);
    };

    /**
     * The techniques a bot of each difficulty is allowed, easiest first, as {@link
     * carpet.pvp.BotPvpConfig} hands them out: jump crit, strafe, W-tap, shield. The preset list and this one
     * are both the measured ladder, so index i is the same level in each.
     */
    private static final boolean[][] TECHNIQUES = {
            {false, false, false, true},
            {true, false, false, true},
            {true, true, false, true},
            {true, true, true, true},
            {true, true, true, true}};

    @Test
    void everyPresetClosesOnATargetThatStandsStill()
    {
        for (int level = 0; level < DifficultyPresets.ladder().size(); level++)
        {
            assertClosesAndHits(level, STILL, "a target that stands still");
        }
    }

    @Test
    void everyPresetClosesOnATargetThatWalksAway()
    {
        for (int level = 0; level < DifficultyPresets.ladder().size(); level++)
        {
            assertClosesAndHits(level, WALKING_AWAY, "a target that walks away");
        }
    }

    @Test
    void everyPresetClosesOnATargetThatCircles()
    {
        for (int level = 0; level < DifficultyPresets.ladder().size(); level++)
        {
            assertClosesAndHits(level, CIRCLING, "a target that circles");
        }
    }

    private static void assertClosesAndHits(int level, Opponent opponent, String what)
    {
        List<DifficultyPreset> ladder = DifficultyPresets.ladder();
        DifficultyPreset preset = ladder.get(level);
        String name = level < LADDER.length ? LADDER[level] : preset.name();
        PlannerParams params = preset.params();
        Random random = new Random(20260224L + level);
        boolean[] allowed = TECHNIQUES[level];
        RollingHorizon planner = new RollingHorizon(params, random)
                .setActionFilter(new Techniques(allowed[0], allowed[1], allowed[2], allowed[3]));
        OpponentModel model = new OpponentModel();
        DuelSim sim = new DuelSim();
        for (int who = 0; who < 2; who++)
        {
            sim.fighter(who).setLoadout(7.0, 1.6, 0.0f, 20.0f, 8.0f, 0.0f, 0.0);
        }
        sim.placeFacing(START);
        double start = sim.horizontalDistance();
        int firstHit = -1;
        float dealt = 0.0F;
        for (int tick = 0; tick < LIMIT && !sim.over() && firstHit < 0; tick++)
        {
            int mine = planner.plan(sim, 0, model, params.budgetTicks());
            int theirs = opponent.act(sim);
            model.observe(sim, 1, theirs);
            sim.step(mine, theirs);
            dealt = DuelSim.MAX_HEALTH - sim.b.health;
            if (dealt > 0.0F)
            {
                firstHit = tick;
            }
        }
        assertTrue(firstHit >= 0, String.format("%s against %s: %.2f blocks of the %.2f it started at"
                        + " closed by tick %d, and it had landed nothing",
                name, what, start - sim.horizontalDistance(), start, LIMIT));
        assertTrue(sim.horizontalDistance() < start,
                String.format("%s against %s walked away rather than closing", name, what));
    }

    /** The names of the five levels, for the message of a failure. */
    private static final String[] LADDER = {"beginner", "casual", "average", "skilled", "expert"};
}