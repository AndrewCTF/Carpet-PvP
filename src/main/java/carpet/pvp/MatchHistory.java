package carpet.pvp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The fights that have finished on this server, newest first, kept in memory for the web editor's match tab.
 *
 * <p>The bot code keeps no bookkeeping of its own yet: nothing in it decides when a fight is over, so nothing
 * records here either. {@link #record} is the one call such bookkeeping has to make, and
 * {@link #revision()} lets a broadcaster tell whether anything changed since it last sent the list.</p>
 */
public final class MatchHistory
{
    public static final int MAX_MATCHES = 50;

    /**
     * @param attackerDamage damage the attacker dealt, in half hearts
     * @param defenderDamage damage the defender dealt, in half hearts
     * @param ticks how long the fight took, in ticks
     */
    public record Match(String attacker, String defender, String winner, long ticks, double attackerDamage, double defenderDamage)
    {
    }

    private static final Deque<Match> matches = new ArrayDeque<>();
    private static int revision;

    private MatchHistory() {}

    public static void record(Match match)
    {
        matches.addFirst(match);
        while (matches.size() > MAX_MATCHES)
        {
            matches.removeLast();
        }
        revision++;
    }

    /**
     * @return the last {@value #MAX_MATCHES} fights, newest first
     */
    public static List<Match> matches()
    {
        return List.copyOf(matches);
    }

    /**
     * @return how often a match has been recorded, which is what a broadcaster remembers to spot a new one
     */
    public static int revision()
    {
        return revision;
    }

    public static void clear()
    {
        matches.clear();
        revision++;
    }
}