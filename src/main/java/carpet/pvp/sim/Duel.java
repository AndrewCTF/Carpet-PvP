package carpet.pvp.sim;

/**
 * Runs one duel between two policies on a seed. Both policies are stepped from the same pre-tick state and the
 * returned margin is A's health minus B's, so a kill wins by the full health of the loser.
 */
public final class Duel
{
    public static final int DEFAULT_MAX_TICKS = 600;
    /** Sword on diamond armour, the loadout every duel here runs. */
    public static final double BASE_DAMAGE = 8.0;
    public static final double ATTACK_SPEED = 1.6;
    public static final float ARMOR = 20.0f;
    public static final float TOUGHNESS = 8.0f;

    private Duel()
    {
    }

    /**
 * A duel start: a random distance and sideways offset, and a random swing timer per fighter. The timers matter:
 * two fighters whose swing clocks run in step always reach and hit each other on the same tick, kill each other on
 * the same tick and end every duel in an identical trade, which tells nothing about either of them.
 */
public static DuelSim newDuel(long seed)
    {
        java.util.Random rng = new java.util.Random(seed);
        DuelSim sim = new DuelSim();
        loadout(sim.a);
        loadout(sim.b);
        sim.placeFacing(3.5 + rng.nextDouble() * 4.0, rng.nextDouble() * 1.2 - 0.6);
        sim.a.ticksSinceSwing = rng.nextInt(20);
        sim.b.ticksSinceSwing = rng.nextInt(20);
        return sim;
    }

    private static void loadout(DuelSim.Fighter f)
    {
        f.setLoadout(BASE_DAMAGE, ATTACK_SPEED, 0.0f, ARMOR, TOUGHNESS, 0.0f, 0.0);
    }

    /** Health of A minus health of B when the tick limit is reached, or when one of them dies. */
    public static float run(DuelPolicy a, DuelPolicy b, long seed, int maxTicks)
    {
        DuelSim sim = newDuel(seed);
        for (int t = 0; t < maxTicks && !sim.over(); t++)
        {
            int actA = a.act(sim, 0);
            int actB = b.act(sim, 1);
            a.observe(sim, 0, actB);
            b.observe(sim, 1, actA);
            sim.step(actA, actB);
        }
        return sim.a.health - sim.b.health;
    }
}