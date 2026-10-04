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
    /** Speed a wind charge leaves the hand at, the power WindChargeItem.use shoots it with. */
    public static final double CHARGE_SPEED = 1.5;
    /** Longest a thrown charge is followed on its way down. */
    private static final int BURST_TICKS = 12;

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
     * How far above the ground a fighter's feet are at the moment a wind charge it throws straight down this
     * tick bursts, or -1 when the fighter is on its feet before the charge gets there.
     *
     * <p>The charge leaves from eye height with the thrower's own sinking speed added to it and keeps that
     * speed, since a wind charge has no gravity and no drag. It does not move on the tick it is thrown: the game
     * ticks a new entity from the next tick on, and after the fighter that threw it. So a fighter that touches
     * down on the same tick the charge reaches the ground has landed first, and the burst then goes off under
     * its feet as if it had been thrown from the ground.</p>
     */
    public static double burstGap(double height, double vy)
    {
        double charge = height + DuelSim.EYE_HEIGHT;
        double chargeSpeed = CHARGE_SPEED - Math.min(vy, 0.0);
        double feet = height + vy;
        double speed = (vy - DuelSim.GRAVITY) * DuelSim.VERTICAL_DRAG;
        for (int tick = 0; tick < BURST_TICKS; tick++)
        {
            if (feet <= 0.0)
            {
                return -1.0;
            }
            feet += speed;
            speed = (speed - DuelSim.GRAVITY) * DuelSim.VERTICAL_DRAG;
            charge -= chargeSpeed;
            if (charge <= 0.0)
            {
                return feet <= 0.0 ? -1.0 : feet;
            }
        }
        return feet;
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
