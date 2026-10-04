package carpet.pvp.selftest;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A bot told to walk forward walks straight, and stops when the ticks it was given are up.
 *
 * <p>The entity tracker sends every player the motion of the bots it is watching, and a fake player has no
 * client to tell the two apart, so a listener that took every motion packet as its own knockback had its
 * velocity overwritten by its neighbours' several times a tick. Eleven bots walking in a line ended up
 * sharing one velocity, and a target with a crowd behind it was walked sideways by them: in {@code nav_crowd}
 * on Paper half the crowd never got through the gate and chased the target off to one side.</p>
 */
final class NavMotionScenarios
{
    /** How far off its line the target may wander, in blocks. */
    private static final double OFF_LINE = 0.75D;
    /** Ticks without moving on that count as the walk being over. */
    private static final int SETTLE = 30;
    /** How far a bot walks in 400 ticks of holding forward, with room for the frame it takes. */
    private static final double WALKED_MIN = 60.0D;
    private static final double WALKED_MAX = 130.0D;

    private NavMotionScenarios() {}

    static SelfTest.Scenario straightLine(String a, String b, String c, Vec3 origin)
    {
        double startX = origin.x;
        double startZ = origin.z;
        double[] offLine = {0.0D};
        double[] lastZ = {startZ};
        int[] still = {0};
        return new SelfTest.Scenario(500, List.of(new SelfTest.Bot(b, new Vec3(startX, SelfTest.SURFACE_Y, startZ))),
                List.of(SelfTest.cmd(b + " move forward for 400")), server ->
                {
                    ServerPlayer target = SelfTest.player(server, b);
                    offLine[0] = Math.max(offLine[0], Math.abs(target.getX() - startX));
                    still[0] = target.getZ() == lastZ[0] ? still[0] + 1 : 0;
                    lastZ[0] = target.getZ();
                    double walked = target.getZ() - startZ;
                    boolean over = still[0] >= SETTLE;
                    return new SelfTest.Probe(over && walked >= WALKED_MIN && walked <= WALKED_MAX && offLine[0] <= OFF_LINE,
                            SelfTest.fmt("%s walked %.1f blocks north%s, never more than %.2f off x %.1f",
                                    b, walked, over ? " and then stood still" : " so far",
                                    offLine[0], startX));
                });
    }
}