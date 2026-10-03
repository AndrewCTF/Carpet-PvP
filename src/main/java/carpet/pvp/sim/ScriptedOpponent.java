package carpet.pvp.sim;

import java.util.Random;

/**
 * Base for the scripted sword-PvP techniques: supplies each policy with a Random and a reaction delay in ticks,
 * so the same policy can be played faster or sloppier. decide sees the state as it was reactionDelay ticks ago,
 * which at zero delay is the current state.
 */
public abstract class ScriptedOpponent implements DuelPolicy
{
    protected final Random random;
    private final View[] history;
    private final int delay;
    private int write;
    private int tick;

    protected ScriptedOpponent(Random random, int reactionDelay)
    {
        this.random = random;
        this.delay = Math.max(0, reactionDelay);
        this.history = new View[Math.max(1, this.delay + 1)];
        for (int i = 0; i < history.length; i++)
        {
            history[i] = new View();
        }
    }

    @Override
    public final int act(DuelSim sim, int who)
    {
        history[write].sample(sim, who);
        int stale = write - delay;
        if (stale < 0)
        {
            stale += history.length;
        }
        if (++write == history.length)
        {
            write = 0;
        }
        tick++;
        return decide(history[stale]);
    }

    /** Ticks this policy has been asked to act, that is the duel length so far. */
    protected final int tick()
    {
        return tick;
    }

    protected abstract int decide(View view);

    /** Everything a scripted policy is allowed to look at, sampled once per tick. */
    public static final class View
    {
        /** Horizontal distance between the fighters. */
        public double distance;
        /** The opponent is inside this fighter's reach. */
        public boolean inReach;
        /** This fighter's swing is charged past the attack gate. */
        public boolean charged;
        public int swingAge;
        public int theirSwingAge;
        /** Smallest swingAge at which this fighter's swing would pass the attack gate. */
        public int gateTicks;
        public int theirGateTicks;
        /** This fighter lost sprint to a sprint hit and must release it to regain it. */
        public boolean sprintLocked;
        public boolean onGround;
        public double vy;
        public boolean theyOnGround;
        public float theirHealth;

        void sample(DuelSim sim, int who)
        {
            DuelSim.Fighter me = sim.fighter(who);
            DuelSim.Fighter other = sim.fighter(1 - who);
            distance = sim.horizontalDistance();
            inReach = DuelSim.inReach(me, other);
            swingAge = me.ticksSinceSwing;
            charged = swingAge >= me.gateTicks;
            theirSwingAge = other.ticksSinceSwing;
            gateTicks = me.gateTicks;
            theirGateTicks = other.gateTicks;
            sprintLocked = me.sprintLocked;
            onGround = me.onGround;
            vy = me.vy;
            theyOnGround = other.onGround;
            theirHealth = other.health;
        }
    }
}