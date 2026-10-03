package carpet.pvp;

import java.util.List;

/**
 * What {@code /bot stats} says about the fights a bot has been in, in one line each and newest first, read
 * straight out of {@link MatchHistory} so that what a player is told and what the web panel shows come from
 * the same records.
 */
public final class BotMatchReport
{
    /** Fights one bot's own history is reported out of. */
    public static final int SHOWN = 3;

    private BotMatchReport() {}

    /**
     * @return the last {@value #SHOWN} fights {@code name} was in, newest first
     */
    public static String describe(String name)
    {
        List<MatchHistory.Match> theirs = MatchHistory.matches().stream()
                .filter(match -> match.attacker().equals(name) || match.defender().equals(name))
                .limit(SHOWN)
                .toList();
        if (theirs.isEmpty())
        {
            return "no fights recorded";
        }
        StringBuilder out = new StringBuilder();
        for (MatchHistory.Match match : theirs)
        {
            if (out.length() > 0)
            {
                out.append("; ");
            }
            out.append(match.winner()).append(" won after ").append(match.ticks()).append(" ticks, ")
                    .append(match.attackerDamage()).append(" against ").append(match.defenderDamage());
        }
        return out.toString();
    }
}