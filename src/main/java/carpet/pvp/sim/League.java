package carpet.pvp.sim;

import java.util.List;
import java.util.function.Supplier;

/**
 * Round robin over a set of combatants: every pair meets duelsPerPair times with each fighter as A, so no matchup
 * depends on which side a fighter started from. Every duel is seeded from the pair and the duel number alone, so
 * the whole table is reproducible from seedBase.
 */
public final class League
{
    private static final double START_RATING = 1000.0;
    private static final double K_FACTOR = 32.0;
    private static final int ELO_ROUNDS = 40;

    /** A contestant: a display name and a factory for the fresh policy every duel needs. */
    public record Combatant(String name, Supplier<DuelPolicy> factory)
    {
    }

    /** Win counts, duel counts, health margins and ratings, indexed by combatant position. */
    public record Table(List<String> names, int[][] wins, int[][] played, double[][] margin, double[] elo)
    {
        public int draws(int i, int j)
        {
            return played[i][j] - wins[i][j] - wins[j][i];
        }

        /** Share of the duels between i and j that i won, half credit for a draw. */
        public double winRate(int i, int j)
        {
            return (wins[i][j] + 0.5 * draws(i, j)) / played[i][j];
        }

        /** Average health left over for i over all of its duels. */
        public double overallMargin(int i)
        {
            int duels = 0;
            double total = 0.0;
            for (int j = 0; j < names.size(); j++)
            {
                duels += played[i][j];
                total += margin[i][j] * played[i][j];
            }
            return total / Math.max(1, duels);
        }
    }

    private League()
    {
    }

    public static Table run(List<Combatant> combatants, int duelsPerPair, long seedBase)
    {
        return run(combatants, duelsPerPair, seedBase, Duel.DEFAULT_MAX_TICKS);
    }

    public static Table run(List<Combatant> combatants, int duelsPerPair, long seedBase, int maxTicks)
    {
        int n = combatants.size();
        Combatant[] roster = combatants.toArray(new Combatant[0]);
        int pairs = n * (n - 1) / 2;
        int[] low = new int[pairs];
        int[] high = new int[pairs];
        for (int i = 0, p = 0; i < n; i++)
        {
            for (int j = i + 1; j < n; j++)
            {
                low[p] = i;
                high[p] = j;
                p++;
            }
        }
        int tasks = pairs * 2 * duelsPerPair;
        float[] margins = new float[tasks];
        Parallel.forEach(tasks, t ->
        {
            int pair = t / (duelsPerPair * 2);
            boolean asA = (t / duelsPerPair) % 2 == 0;
            int duel = t % duelsPerPair;
            int a = asA ? low[pair] : high[pair];
            int b = asA ? high[pair] : low[pair];
            long seed = seedBase + 7919L * low[pair] + 104729L * high[pair] + 31L * duel;
            DuelPolicy pa = roster[a].factory().get();
            DuelPolicy pb = roster[b].factory().get();
            // Duel.run scores A minus B, so the two sides of a pair only line up once the run where the high
            // fighter started is flipped back to the low fighter's point of view.
            margins[t] = asA ? Duel.run(pa, pb, seed, maxTicks) : -Duel.run(pa, pb, seed, maxTicks);
        });

        int[][] wins = new int[n][n];
        int[][] played = new int[n][n];
        double[][] margin = new double[n][n];
        for (int pair = 0; pair < pairs; pair++)
        {
            int lo = low[pair];
            int hi = high[pair];
            for (int asA = 0; asA < 2; asA++)
            {
                for (int duel = 0; duel < duelsPerPair; duel++)
                {
                    int t = (pair * 2 + asA) * duelsPerPair + duel;
                    float result = margins[t];
                    played[lo][hi]++;
                    played[hi][lo]++;
                    if (result > 0.0f)
                    {
                        wins[lo][hi]++;
                        margin[lo][hi] += result;
                        margin[hi][lo] -= result;
                    }
                    else if (result < 0.0f)
                    {
                        wins[hi][lo]++;
                        margin[hi][lo] -= result;
                        margin[lo][hi] += result;
                    }
                }
            }
        }
        for (int i = 0; i < n; i++)
        {
            for (int j = 0; j < n; j++)
            {
                if (played[i][j] > 0)
                {
                    margin[i][j] /= played[i][j];
                }
            }
        }
        double[] elo = ratings(n, wins, played, duelsPerPair);
        return new Table(combatants.stream().map(Combatant::name).toList(), wins, played, margin, elo);
    }

    /** Iterated Elo over the aggregated pair results; each pair is swept once per round in a fixed order. */
    private static double[] ratings(int n, int[][] wins, int[][] played, int duelsPerPair)
    {
        double[] elo = new double[n];
        java.util.Arrays.fill(elo, START_RATING);
        for (int round = 0; round < ELO_ROUNDS; round++)
        {
            for (int i = 0; i < n; i++)
            {
                for (int j = i + 1; j < n; j++)
                {
                    double playedCount = played[i][j];
                    if (playedCount == 0)
                    {
                        continue;
                    }
                    double draws = playedCount - wins[i][j] - wins[j][i];
                    double score = (wins[i][j] + 0.5 * draws) / playedCount;
                    double expected = 1.0 / (1.0 + Math.pow(10.0, (elo[j] - elo[i]) / 400.0));
                    elo[i] += K_FACTOR * (score - expected);
                    elo[j] += K_FACTOR * (1.0 - score - (1.0 - expected));
                }
            }
        }
        return elo;
    }

    /** The win rate matrix with the health margins and ratings underneath. */
    public static String render(Table table)
    {
        int n = table.names().size();
        StringBuilder out = new StringBuilder();
        int width = 9;
        for (String name : table.names())
        {
            width = Math.max(width, name.length() + 2);
        }
        out.append(String.format("%-" + width + "s", "wins"));
        for (String name : table.names())
        {
            out.append(String.format("%8s", abbreviate(name)));
        }
        out.append("    elo\n");
        for (int i = 0; i < n; i++)
        {
            out.append(String.format("%-" + width + "s", table.names().get(i)));
            for (int j = 0; j < n; j++)
            {
                out.append(i == j ? String.format("%8s", "-")
                        : String.format("%8s", String.format("%.2f", table.winRate(i, j))));
            }
            out.append(String.format("  %6.0f%n", table.elo()[i]));
        }
        out.append(String.format("%-" + width + "s", "margin"));
        for (String name : table.names())
        {
            out.append(String.format("%8s", abbreviate(name)));
        }
        out.append("    mean\n");
        for (int i = 0; i < n; i++)
        {
            out.append(String.format("%-" + width + "s", table.names().get(i)));
            for (int j = 0; j < n; j++)
            {
                out.append(i == j ? String.format("%8s", "-")
                        : String.format("%8s", String.format("%+.2f", table.margin()[i][j])));
            }
            out.append(String.format("  %+6.2f%n", table.overallMargin(i)));
        }
        return out.toString();
    }

    private static String abbreviate(String name)
    {
        return name.length() <= 8 ? name : name.substring(0, 8);
    }
}