package carpet.pvp.sim;

import java.util.Random;

/**
 * A fighter that plans with the rolling horizon but plays like a person of the preset's skill: it acts on the plan
 * it drew reactionDelay ticks ago, and it can take a swing too early.
 *
 * <p>A swing only comes off cooldown every gateTicks ticks, and a fighter that presses attack before that point
 * spends the cooldown on an undercharged hit, so that is what a miss is modelled as: with probability missChance a
 * swing is held back by up to a third of a cooldown and released early, landing well under full strength. Letting
 * the attack bit go for a tick instead would cost nothing at all, because the swing timer only moves when the swing
 * is actually thrown.
 */
public final class PresetFighter implements DuelPolicy
{
    private static final int APPROACH = DuelSim.action(1, 0, false, true, false);

    private final PlannerOpponent planner;
    private final Random random;
    private final double missChance;
    private final int[] pipe;
    private final int delay;
    private int write;
    private int fumble;

    public PresetFighter(DifficultyPreset preset, Random random)
    {
        this.planner = new PlannerOpponent(preset.params(), random);
        this.random = random;
        this.missChance = preset.missChance();
        this.delay = Math.max(0, preset.reactionDelay());
        this.pipe = new int[Math.max(1, this.delay + 1)];
        java.util.Arrays.fill(pipe, APPROACH);
    }

    @Override
    public int act(DuelSim sim, int who)
    {
        pipe[write] = planner.act(sim, who);
        int stale = write - delay;
        if (stale < 0)
        {
            stale += pipe.length;
        }
        if (++write == pipe.length)
        {
            write = 0;
        }
        int action = pipe[stale];
        if (!DuelSim.attack(action))
        {
            return action;
        }
        DuelSim.Fighter me = sim.fighter(who);
        if (fumble > 0)
        {
            fumble--;
            return hold(action);
        }
        if (random.nextDouble() < missChance)
        {
            fumble = 1 + random.nextInt(3 + me.gateTicks / 2);
            return hold(action);
        }
        return action;
    }

    private static int hold(int action)
    {
        return DuelSim.action(DuelSim.forward(action), DuelSim.strafe(action), DuelSim.jump(action),
                DuelSim.sprint(action), false);
    }

    @Override
    public void observe(DuelSim sim, int who, int theirAction)
    {
        planner.observe(sim, who, theirAction);
    }
}