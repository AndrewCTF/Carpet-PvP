package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LeagueTest
{
    private static final int DUELS_PER_PAIR = 4;

    /** The seven techniques plus the yardstick they are all measured against, no planner: cheap enough to run twice. */
    static List<League.Combatant> scripted()
    {
        List<League.Combatant> roster = new ArrayList<>();
        roster.add(new League.Combatant("baseline", () -> new BaselineOpponent(new Random(1), 0)));
        roster.add(new League.Combatant("wtap", () -> new WTapOpponent(new Random(2), 0)));
        roster.add(new League.Combatant("stap", () -> new STapOpponent(new Random(3), 0)));
        roster.add(new League.Combatant("crit", () -> new CritOpponent(new Random(4), 0)));
        roster.add(new League.Combatant("strafe", () -> new StrafeOpponent(new Random(5), 0)));
        roster.add(new League.Combatant("jreset", () -> new JumpResetOpponent(new Random(6), 0)));
        roster.add(new League.Combatant("hselect", () -> new HitSelectOpponent(new Random(7), 0)));
        roster.add(new League.Combatant("combo", () -> new ComboOpponent(new Random(8), 0)));
        return roster;
    }

    @Test
    void theSameSeedsGiveTheSameTable()
    {
        League.Table first = League.run(scripted(), DUELS_PER_PAIR, 4000L);
        League.Table second = League.run(scripted(), DUELS_PER_PAIR, 4000L);
        assertEquals(first.names(), second.names());
        for (int i = 0; i < first.names().size(); i++)
        {
            assertArrayEquals(first.wins()[i], second.wins()[i], "wins of " + first.names().get(i));
            assertArrayEquals(first.played()[i], second.played()[i], "duels of " + first.names().get(i));
            for (int j = 0; j < first.names().size(); j++)
            {
                assertEquals(first.margin()[i][j], second.margin()[i][j],
                        first.names().get(i) + " against " + first.names().get(j));
            }
        }
        assertArrayEquals(first.elo(), second.elo());
    }

    @Test
    void aDifferentSeedBaseGivesADifferentTable()
    {
        League.Table first = League.run(scripted(), DUELS_PER_PAIR, 4000L);
        League.Table second = League.run(scripted(), DUELS_PER_PAIR, 900000L);
        int differences = 0;
        for (int i = 0; i < first.names().size(); i++)
        {
            for (int j = 0; j < first.names().size(); j++)
            {
                if (first.margin()[i][j] != second.margin()[i][j])
                {
                    differences++;
                }
            }
        }
        assertTrue(differences > 20, "only " + differences + " cells differ between two seed bases");
    }

    @Test
    void everyPairMeetsFromBothSidesAndTheScoresBalance()
    {
        int n = scripted().size();
        League.Table table = League.run(scripted(), DUELS_PER_PAIR, 4000L);
        for (int i = 0; i < n; i++)
        {
            for (int j = 0; j < n; j++)
            {
                if (i == j)
                {
                    assertEquals(0, table.played()[i][j], "no fighter duels itself");
                    continue;
                }
                assertEquals(2 * DUELS_PER_PAIR, table.played()[i][j], "both sides of the pair");
                assertEquals(table.played()[j][i], table.played()[i][j], "the same duels seen from the other side");
                assertTrue(table.wins()[i][j] + table.wins()[j][i] + table.draws(i, j) == table.played()[i][j],
                        "wins and draws add up");
                assertEquals(1.0, table.winRate(i, j) + table.winRate(j, i), 1e-12, "win rates add to one");
                assertEquals(-table.margin()[i][j], table.margin()[j][i], 1e-12, "margins are the other way round");
            }
        }
    }

    @Test
    void ratingsFollowTheResults()
    {
        // A fighter that wins everything must end up top of the table and everything else below it.
        List<League.Combatant> roster = List.of(
                new League.Combatant("padded", () -> new PlannerOpponent(DifficultyPresets.BEST, new Random(99))),
                new League.Combatant("baseline", () -> new BaselineOpponent(new Random(1), 0)));
        League.Table table = League.run(roster, DUELS_PER_PAIR, 4000L);
        assertTrue(table.wins()[0][1] > table.played()[0][1] * 0.75,
                "the tuned planner beat the yardstick " + table.wins()[0][1] + " of " + table.played()[0][1]);
        assertTrue(table.elo()[0] > table.elo()[1], "elo " + table.elo()[0] + " against " + table.elo()[1]);
        assertEquals(1000.0, (table.elo()[0] + table.elo()[1]) / 2, 1e-6, "ratings are centred");
        assertTrue(League.render(table).contains("padded"), "the table renders its rows");
    }
}