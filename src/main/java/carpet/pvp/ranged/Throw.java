package carpet.pvp.ranged;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotBudget;
import carpet.pvp.BotStats;
import carpet.pvp.Perception;
import carpet.pvp.sim.ProjectileAim;
import carpet.pvp.sim.ProjectileSim;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Throwing a trident, and the Riptide it carries.
 *
 * <p>A trident is thrown the same way a bow is shot, with the hand throw the ballistics solver already models,
 * and the charge is short: {@code TridentItem.THROW_THRESHOLD_TIME} ticks, after which the throw is worth as
 * much as it will ever be. A trident with Riptide on it is the other way round: in rain or in water the
 * release launches the thrower along its own view instead of throwing anything, so the bot points at the target,
 * lets go and gets carried into it.</p>
 *
 * <p>As with the bow, the solve simulates flights, so it draws on the shared budget.</p>
 */
public final class Throw
{
    /** Whole ticks of flight the solver is allowed to look at for a throw. */
    public static final int FLIGHT_LIMIT = 40;
    /** Both arcs out of range, which is what a solve that could not be afforded hands back. */
    public static final ProjectileAim.Aim[] NO_AIM = {
            ProjectileAim.OUT_OF_RANGE, ProjectileAim.OUT_OF_RANGE
    };
    /** What one solve costs against the per tick budget: two arcs of forty flights. */
    public static final int SOLVE_COST = FLIGHT_LIMIT * 2;

    private final EntityPlayerMPFake bot;
    private final BotBody body;
    private final BotStats stats;


    public Throw(EntityPlayerMPFake bot, BotBody body)
    {
        this.bot = bot;
        this.body = body;
        this.stats = body.stats();
    }

    /** Ticks the current charge has been held for. */
    public int held()
    {
        return bot.isUsingItem() ? bot.getTicksUsingItem() : 0;
    }

    /** True while this trident would launch the thrower rather than leave the hand, which is Riptide in water. */
    public static boolean riptide(EntityPlayerMPFake bot, ItemStack trident)
    {
        return EnchantmentHelper.getTridentSpinAttackStrength(trident, bot) > 0.0F && bot.isInWaterOrRain();
    }

    /**
     * The two arcs a throw at the seen target can take, or out of range when the hand speed cannot reach.
     * {@link ProjectileAim#OUT_OF_RANGE} on both when the bot has no share of the budget to solve with.
     */
    public ProjectileAim.Aim[] solve(Perception.Snapshot seen)
    {
        BotBudget budget = BotBudget.instance();
        int share = budget.join();
        if (share < SOLVE_COST)
        {
            stats.starvedTicks++;
            return NO_AIM;
        }
        ProjectileAim.Shooter shooter = new ProjectileAim.Shooter();
        shooter.x = bot.getX();
        shooter.y = bot.getY();
        shooter.z = bot.getZ();
        shooter.vx = bot.getDeltaMovement().x;
        shooter.vy = bot.getDeltaMovement().y;
        shooter.vz = bot.getDeltaMovement().z;
        shooter.onGround = bot.onGround();
        ProjectileAim.Aim[] arcs = ProjectileAim.solveHandThrow(ProjectileSim.Kind.TRIDENT, shooter,
                Bow.targetOf(seen), FLIGHT_LIMIT);
        budget.spend(SOLVE_COST);
        stats.plannerCalls++;
        stats.simulatedTicks += SOLVE_COST;
        return arcs;
    }

    /**
     * Charges the trident and lets it go once it has been held long enough and the view is on the point.
     *
     * @param point where the view is being sent, or null when the body is aiming at the target itself
     * @param wanted the charge the throw needs, in ticks
     * @return true on the tick the trident left the hand or the thrower was launched
     */
    public boolean cast(BotBody.Aim point, int wanted, boolean fire)
    {
        if (!fire)
        {
            body.releaseItem();
            return false;
        }
        int held = held();
        body.holdItem();
        if (held < wanted)
        {
            return false;
        }
        if (point != null && !onPoint(point) && held < wanted + Bow.PATIENCE)
        {
            return false;
        }
        body.releaseItem();
        return true;
    }

    private boolean onPoint(BotBody.Aim point)
    {
        double dy = point.yaw() - body.look().yaw();
        dy -= 360.0 * Math.rint(dy / 360.0);
        double dp = point.pitch() - body.look().pitch();
        return Math.hypot(dy, dp) <= Bow.RELEASE_TOLERANCE;
    }
}
