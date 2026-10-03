package carpet.pvp;

import carpet.pvp.MatchHistory.Match;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MatchHistoryTest
{
    @AfterEach
    void empty()
    {
        MatchHistory.clear();
    }

    @Test
    void theNewestFightsAreKeptAndComeFirst()
    {
        MatchHistory.record(fight("Bot1", "Bot2", "Bot1", 20, 40.0D, 15.0D));
        MatchHistory.record(fight("Bot3", "Bot4", "Bot4", 60, 10.0D, 25.0D));

        assertEquals(List.of(fight("Bot3", "Bot4", "Bot4", 60, 10.0D, 25.0D), fight("Bot1", "Bot2", "Bot1", 20, 40.0D, 15.0D)),
                MatchHistory.matches());
    }

    @Test
    void onlyTheLastFightsAreKept()
    {
        int recorded = MatchHistory.MAX_MATCHES + 10;
        for (int i = 0; i < recorded; i++)
        {
            MatchHistory.record(fight("Bot" + i, "Bot" + (i + 1), "Bot" + i, i, i, i));
        }

        List<Match> matches = MatchHistory.matches();
        assertEquals(MatchHistory.MAX_MATCHES, matches.size());
        assertEquals("Bot" + (recorded - 1), matches.get(0).attacker());
        // The ten oldest ones fell off the list.
        assertEquals("Bot10", matches.get(matches.size() - 1).attacker());
    }

    @Test
    void everyRecordedFightIsSomethingABroadcasterCanNotice()
    {
        int before = MatchHistory.revision();
        MatchHistory.record(fight("Bot1", "Bot2", "Bot1", 20, 40.0D, 15.0D));
        int after = MatchHistory.revision();
        assertNotEquals(before, after);

        MatchHistory.clear();
        assertNotEquals(after, MatchHistory.revision());
        assertEquals(List.of(), MatchHistory.matches());
    }

    private static Match fight(String attacker, String defender, String winner, long ticks, double attackerDamage, double defenderDamage)
    {
        return new Match(attacker, defender, winner, ticks, attackerDamage, defenderDamage);
    }
}