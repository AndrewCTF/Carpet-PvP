package carpet.pvp.nav;

/**
 * String pulling in the style of Lazy Theta* (Nash, Koenig and Tovey 2010): a path of block centres is shortened
 * to the waypoints a player actually needs, by dropping every waypoint the player can walk past in one straight
 * line.
 *
 * The result is a handful of straight lines instead of a staircase, which is what makes a bot's route look like a
 * player's. Anything that is not a plain walk - a jump, a fall, a parkour gap, a pillar, a block to mine - stays a
 * fixed anchor, and no line is ever pulled across one.
 *
 * The result is written into caller-owned arrays so that a bot can re-smooth its path every tick without
 * allocating.
 */
public final class PathSmoother
{
    /** The move code of a waypoint that is only ever walked to; every other code pins its waypoint. */
    public static final int WALK = 0;

    private PathSmoother()
    {
    }

    /**
     * Smooths {@code count} packed positions from {@code path} into {@code out}, keeping the move code of every
     * waypoint it keeps, and returns how many waypoints were written. {@code moves[i]} is the move used to reach
     * position {@code i}; {@link #WALK} and any other code may follow each other as long as no line crosses a
     * non-walk move.
     *
     * The arrays may be the same as the inputs, since every waypoint is read before the write that replaces it.
     */
    public static int smooth(Walkability view, long[] path, int[] moves, int count, long[] out, int[] outMoves)
    {
        if (count <= 0)
        {
            return 0;
        }
        if (out.length < count || outMoves.length < count)
        {
            throw new IllegalArgumentException("output arrays hold " + Math.min(out.length, outMoves.length)
                    + " of " + count + " waypoints");
        }

        int written = 1;
        out[0] = path[0];
        outMoves[0] = moves[0];

        int anchor = 0;
        while (anchor < count - 1)
        {
            int reach = anchor;
            for (int next = anchor + 1; next < count; next++)
            {
                // A move that is not a walk ends a run: its landing point is an anchor of its own.
                if (next > anchor + 1 && moves[next] != WALK)
                {
                    break;
                }
                if (!clear(view, path[anchor], path[next]))
                {
                    break;
                }
                reach = next;
            }
            // Nothing could be skipped, so keep the next waypoint as it is and carry on from there.
            int keep = reach > anchor ? reach : anchor + 1;
            out[written] = path[keep];
            outMoves[written] = moves[keep];
            written++;
            anchor = keep;
        }
        return written;
    }

    private static boolean clear(Walkability view, long from, long to)
    {
        return DirectSteer.canWalkLine(view, NavPos.unpackX(from), NavPos.unpackY(from), NavPos.unpackZ(from),
                NavPos.unpackX(to), NavPos.unpackY(to), NavPos.unpackZ(to));
    }
}