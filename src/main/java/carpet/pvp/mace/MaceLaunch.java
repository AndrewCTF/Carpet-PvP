package carpet.pvp.mace;

import carpet.pvp.sim.DuelSim;

/**
 * How high a wind charge thrown at the fighter's own feet lifts it, and how long it takes to come back
 * down, as the duel model works it out.
 *
 * <p>The launch is a wind charge that bursts above the ground face it lands on, which is what
 * {@link DuelSim#windCharge} models. The game bursts a little higher over the face than the model's
 * quarter of a block, because the charge is half a box tall and bursts where its centre is when its
 * bottom touches, which is why a real launch comes up under what the model says; {@link #HEIGHT_SLACK}
 * is what that costs. Nothing here needs a world, so the self test can hold the game against the same
 * numbers.</p>
 */
public final class MaceLaunch
{
    /** A quarter of a block above the ground face a charge thrown at the feet bursts over. */
    public static final double BURST_HEIGHT = 0.25;
    /** Ticks the model runs a launch forward before it calls the arc over. */
    private static final int APEX_TICKS = 40;
    /** Longest drop the ballistics are run for. */
    private static final int DROP_TICKS = 60;

    /** The height above the ground each burst offset lifts a fighter to, the model's own numbers. */
    public static final double[] APEX = {apexOf(0.0), apexOf(0.5), apexOf(1.0)};
    /** How much of that the game falls short of, in blocks, measured by the mace_launch_height scenario. */
    public static final double HEIGHT_SLACK = 0.9;
    /** Ticks a launch is up and down again, which is how long a smash has to be waited for. */
    public static final int ARC_TICKS = 26;

    private MaceLaunch()
    {
    }

    /**
     * The apex of the arc a wind charge bursting the given offset off the fighter's own feet lifts it
     * to, as the duel model gives it.
     */
    public static double apexOf(double burstBack)
    {
        DuelSim sim = new DuelSim();
        sim.placeFacing(1000.0);
        if (!sim.windCharge(0, sim.a.sinYaw * burstBack, BURST_HEIGHT, -sim.a.cosYaw * burstBack))
        {
            return 0.0;
        }
        double apex = sim.a.y;
        for (int tick = 0; tick < APEX_TICKS; tick++)
        {
            sim.step(DuelSim.NOOP, DuelSim.NOOP);
            apex = Math.max(apex, sim.a.y);
        }
        return apex;
    }

    /**
     * How many ticks a fighter at the given height, sinking at the given speed, needs to come down to
     * the given level, and how far it falls on the way: {@code {ticks, fall distance}}.
     */
    public static double[] drop(double height, double vy, double targetY)
    {
        double y = height;
        double speed = vy;
        double fallen = 0.0;
        for (int tick = 0; tick < DROP_TICKS; tick++)
        {
            if (y + speed <= targetY)
            {
                return new double[] {tick + 1.0, fallen + (y - targetY)};
            }
            y += speed;
            if (speed < 0.0)
            {
                fallen -= speed;
            }
            speed = (speed - DuelSim.GRAVITY) * DuelSim.VERTICAL_DRAG;
        }
        return new double[] {DROP_TICKS, fallen};
    }
}
