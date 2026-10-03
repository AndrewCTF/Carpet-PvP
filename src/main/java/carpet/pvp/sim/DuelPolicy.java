package carpet.pvp.sim;

/**
 * Chooses one tick's action for one fighter of a {@link DuelSim} duel from the state at the start of the tick.
 * A fresh instance is used for every duel; act must be called exactly once per tick for that fighter.
 */
public interface DuelPolicy
{
    /** Action for fighter who (0 = A, 1 = B). */
    int act(DuelSim sim, int who);

    /** Told which action the other fighter chose in the same state, before the tick is stepped. */
    default void observe(DuelSim sim, int who, int theirAction)
    {
    }
}