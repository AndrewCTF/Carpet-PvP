package carpet.pvp.ranged;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.sim.DuelSim;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The movement of a bot that would rather not be where it is: it walks out of reach when a target comes close,
 * walks in when it has drifted too far, and sidesteps while it holds the range it wanted.
 *
 * <p>All of it goes through the body as one of the eight movement directions the duel simulation encodes, so the
 * sprint lock, the jump delay and the slower pace of a player who is holding something up all still apply. The
 * one thing this decides for itself is when it cannot back away any further: a player pressed into a wall stops
 * trying to run, and so does this, which is what turns a kiting bot into a melee one.</p>
 */
public final class Kiting
{
    private final EntityPlayerMPFake bot;
    private int strafe = 1;

    public Kiting(EntityPlayerMPFake bot)
    {
        this.bot = bot;
    }

    /**
     * The movement for this tick.
     *
     * @param gap   how far the target is, in blocks
     * @param near  the range below which the bot gives ground
     * @param far   the range above which the bot closes in again
     * @param kiting whether the bot is allowed to sidestep at all, which a preset that may not strafe is not
     */
    public int move(double gap, double near, double far, boolean kiting, boolean retreating)
    {
        if (retreating || gap < near)
        {
            // Walking backwards keeps the view on the target, which is what a player who is being closed on does.
            return DuelSim.action(-1, strafe, false, false, false);
        }
        if (gap > far)
        {
            return DuelSim.action(1, 0, false, gap > far + 8.0D, false);
        }
        return kiting ? DuelSim.action(0, strafe, false, false, false) : DuelSim.NOOP;
    }

    /** Flips the sidestep, so a bot that is being circled does not keep walking into the same circle. */
    public void turn()
    {
        strafe = -strafe;
    }

    /**
     * True while the bot can still back away from the target, which it decides by looking for a body-sized gap
     * behind it. A bot that is pressed into a corner has none, and has to stop running.
     */
    public boolean canRetreat(double fromX, double fromZ)
    {
        double dx = bot.getX() - fromX;
        double dz = bot.getZ() - fromZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6)
        {
            return true;
        }
        double backX = bot.getX() - dx / length;
        double backZ = bot.getZ() - dz / length;
        if (bot.level() instanceof ServerLevel level)
        {
            BlockPos feet = BlockPos.containing(backX, bot.getY(), backZ);
            if (!free(level, feet))
            {
                return false;
            }
            // A bot with a wall to its side as well as behind it is in a corner even when the back is clear.
            double sideX = -dz / length;
            double sideZ = dx / length;
            return free(level, BlockPos.containing(backX + sideX, bot.getY(), backZ + sideZ))
                    || free(level, BlockPos.containing(backX - sideX, bot.getY(), backZ - sideZ));
        }
        return true;
    }

    private static boolean free(ServerLevel level, BlockPos feet)
    {
        BlockState state = level.getBlockState(feet);
        VoxelShape shape = state.getCollisionShape(level, feet);
        return shape.isEmpty() || state.isAir();
    }
}
