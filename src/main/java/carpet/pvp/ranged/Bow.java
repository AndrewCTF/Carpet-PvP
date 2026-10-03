package carpet.pvp.ranged;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotBudget;
import carpet.pvp.BotStats;
import carpet.pvp.Perception;
import carpet.pvp.sim.ProjectileAim;
import carpet.pvp.sim.ProjectileSim;

/**
 * Drawing a bow, aiming it with the ballistics solver and letting go at the right moment.
 *
 * <p>The aim is not a point in the world: {@link ProjectileAim} inverts the game's own per tick update of an
 * arrow to find the one yaw and pitch that arrives where the target will be after the flight, and it picks the
 * draw that reaches that far with the least power. The bot aims at that yaw and pitch through its own look
 * controller, so the view turns at human speed and lands on whole mouse steps like any other aim, and it lets
 * go on the tick the view is actually on the point the solver chose. Nothing here fires an arrow directly: the
 * arrow leaves because the bot let go of the bow, which is what a player does.</p>
 *
 * <p>Every solve simulates a flight, so it draws on the shared {@link BotBudget} and reuses the aim it had
 * when the tick's share is gone.</p>
 */
public final class Bow
{
    /** Whole ticks of flight the solver is allowed to look at, which is the bow's useful range. */
    public static final int FLIGHT_LIMIT = 60;
    /** What one solve costs against the per tick budget: two arcs of sixty flights. */
    public static final int SOLVE_COST = FLIGHT_LIMIT * 2;
    /** Degrees off the aim point within which the bot lets go, about the width of a target at close range. */
    public static final double RELEASE_TOLERANCE = 0.8;
    /** Ticks past the draw the solver asked for that the bot waits anyway, so a moving aim cannot stall it. */
    public static final int PATIENCE = 30;

    private final EntityPlayerMPFake bot;
    private final BotBody body;
    private final BotStats stats;

    private ProjectileAim.Aim aim = ProjectileAim.OUT_OF_RANGE;

    public Bow(EntityPlayerMPFake bot, BotBody body)
    {
        this.bot = bot;
        this.body = body;
        this.stats = body.stats();
    }

    /**
     * The aim for a crossbow shot, which is not a bow shot with a different draw: the arrow always leaves at
     * {@link ProjectileSim#CROSSBOW_ARROW_SPEED} with the full spread, so aiming it like a full draw bow would
     * put every arrow a block past the target.
     */
    public ProjectileAim.Aim crossbowAt(Perception.Snapshot seen)
    {
        BotBudget budget = BotBudget.instance();
        int share = budget.join();
        if (share < SOLVE_COST)
        {
            stats.starvedTicks++;
            return aim;
        }
        ProjectileAim.Aim[] arcs = ProjectileAim.solve(ProjectileSim.Kind.ARROW,
                ProjectileSim.CROSSBOW_ARROW_SPEED, 1.0, 0.0, shooter(), targetOf(seen), FLIGHT_LIMIT);
        budget.spend(SOLVE_COST);
        stats.plannerCalls++;
        stats.simulatedTicks += SOLVE_COST;
        ProjectileAim.Aim solved = arcs[0].solved ? arcs[0] : arcs[1];
        if (solved.solved)
        {
            aim = solved;
        }
        return aim;
    }

    /** True while the view is close enough to the aim point that a shot taken now would go through it. */
    public boolean aimedAt(BotBody.Aim point)
    {
        return onPoint(point, 0);
    }

    /**
     * The aim for a shot at the seen position of the target, with the draw the solver picks for the distance or
     * the one that was asked for. {@link ProjectileAim#OUT_OF_RANGE} when the target is out of bow range or the
     * bot has no share of the budget to solve with.
     *
     * @param drawTicks the draw to hold, or -1 to let the solver take the shortest one that reaches
     */
    public ProjectileAim.Aim aimAt(Perception.Snapshot seen, int drawTicks)
    {
        return aimAt(targetOf(seen), drawTicks);
    }

    /**
     * The same for a target that is not the bot's own target, which is what setting off a tnt minecart needs:
     * the thing to shoot is the cart, not whoever is standing next to it.
     */
    public ProjectileAim.Aim aimAt(ProjectileSim.Target target, int drawTicks)
    {
        BotBudget budget = BotBudget.instance();
        int share = budget.join();
        if (share < SOLVE_COST)
        {
            stats.starvedTicks++;
            return aim;
        }
        ProjectileAim.Shooter shooter = shooter();
        shooter.drawTicks = drawTicks;
        ProjectileAim.Aim[] arcs = ProjectileAim.solveBow(shooter, target, FLIGHT_LIMIT);
        budget.spend(SOLVE_COST);
        stats.plannerCalls++;
        stats.simulatedTicks += SOLVE_COST;
        // The direct arc is the one a player shoots; the lobbed one only exists for a target behind cover.
        ProjectileAim.Aim solved = arcs[0].solved ? arcs[0] : arcs[1];
        if (solved.solved)
        {
            aim = solved;
        }
        return aim;
    }

    /**
     * Holds the bow up towards the aim and lets go once the draw is long enough and the view has arrived, which
     * is the direction the arrow then leaves along. Returns true on the tick the arrow was fired.
     *
     * @param wanted the draw the solver asked for, in ticks
     */
    public boolean hold(BotBody.Aim point, int wanted)
    {
        int held = held();
        body.holdItem();
        if (held < wanted || !onPoint(point, held - wanted))
        {
            return false;
        }
        body.releaseItem();
        return true;
    }

    /** Lets go of the bow, so the next draw starts from nothing. */
    public void lower()
    {
        body.releaseItem();
    }

    /**
     * One bow shot at a target that is far away and not coming closer, for a style that fights with something
     * else and only wants the shot when it has one to spare. This is the one place a bow shot is set up, so
     * every style can take one without carrying its own copy.
     *
     * @param wanted the draw to hold, or -1 to let the solver take the shortest one that reaches
     * @return true on the tick the arrow was fired
     */
    public boolean opportunistic(Perception.Snapshot seen, int wanted)
    {
        return shootAt(targetOf(seen), wanted);
    }

    /**
     * Draws at a target and lets go when the draw and the view are ready. Returns true on the tick the arrow
     * was fired.
     *
     * @param target what to shoot at, as the solver wants it
     * @param wanted the draw to hold, or -1 to let the solver take the shortest one that reaches
     */
    public boolean shootAt(ProjectileSim.Target target, int wanted)
    {
        ProjectileAim.Aim solved = aimAt(target, wanted);
        if (!solved.solved)
        {
            lower();
            return false;
        }
        return hold(pointOf(solved, horizontal(target)), solved.drawTicks);
    }

    /** Ticks the current draw has been held for, which is the draw the game will fire at. */
    public int held()
    {
        return bot.isUsingItem() ? bot.getTicksUsingItem() : 0;
    }

    /** The aim point the view goes onto for a solved shot, which is where the solver says the arrow goes. */
    public static BotBody.Aim pointOf(ProjectileAim.Aim aim, double distance)
    {
        float radius = (float) Math.toDegrees(Math.atan2(0.45, Math.max(distance, 0.5)));
        return new BotBody.Aim((float) aim.yaw, (float) aim.pitch, radius);
    }

    /** How far the bot is from a target box on the ground, in blocks. */
    public double horizontal(ProjectileSim.Target target)
    {
        double dx = target.x - bot.getX();
        double dz = target.z - bot.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Where the bot is, as the shooter the solver wants: its own feet, its own motion and its own ground. */
    public ProjectileAim.Shooter shooter()
    {
        ProjectileAim.Shooter shooter = new ProjectileAim.Shooter();
        shooter.x = bot.getX();
        shooter.y = bot.getY();
        shooter.z = bot.getZ();
        shooter.vx = bot.getDeltaMovement().x;
        shooter.vy = bot.getDeltaMovement().y;
        shooter.vz = bot.getDeltaMovement().z;
        shooter.onGround = bot.onGround();
        return shooter;
    }

    /** A perceived fighter as the solver's target box: where it was seen, moving as it was seen moving. */
    public static ProjectileSim.Target targetOf(Perception.Snapshot seen)
    {
        ProjectileSim.Target target = new ProjectileSim.Target();
        target.x = seen.x;
        target.y = seen.y;
        target.z = seen.z;
        target.vx = seen.vx;
        target.vy = seen.vy;
        target.vz = seen.vz;
        target.halfWidth = 0.3;
        target.height = Math.max(seen.height, 1.8);
        return target;
    }

    /** True while the view is close enough to the aim point that an arrow released now would go through it. */
    private boolean onPoint(BotBody.Aim point, int waited)
    {
        if (point == null)
        {
            return false;
        }
        double dy = point.yaw() - body.look().yaw();
        dy -= 360.0 * Math.rint(dy / 360.0);
        double dp = point.pitch() - body.look().pitch();
        return Math.hypot(dy, dp) <= RELEASE_TOLERANCE || waited >= PATIENCE;
    }
}
