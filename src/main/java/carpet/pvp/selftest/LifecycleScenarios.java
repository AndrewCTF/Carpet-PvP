package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** What happens to a fake player over its life: death and respawn. */
final class LifecycleScenarios
{
    private LifecycleScenarios() {}

    /**
     * A fake player that dies stays on the server and is put back where it was spawned with full health. The
     * respawn runs as a delayed task that schedules another one, which used to stop the server.
     */
    static Scenario deathRespawn(String a, String b, String c, Vec3 origin)
    {
        Vec3 away = origin.add(0.0D, 0.0D, 6.0D);
        int[] killedAt = {-1};
        int[] tick = {0};
        return new Scenario(400, List.of(new Bot(a, origin)), List.of(), server ->
        {
            tick[0]++;
            ServerPlayer bot = SelfTest.player(server, a);
            if (killedAt[0] < 0)
            {
                if (!SelfTest.hittable(bot)) return SelfTest.pending(SelfTest.hitWaitReason(bot));
                SelfTest.run(server, "tp " + a + " " + SelfTest.coords(away));
                if (SelfTest.result(server, "damage " + a + " 1000") < 1) return SelfTest.pending(a + " could not be damaged yet");
                killedAt[0] = tick[0];
                return SelfTest.pending(a + " was killed 6 blocks from its spawn point");
            }
            if (tick[0] - killedAt[0] < 5) return SelfTest.pending("waiting for the respawn");
            double off = bot.position().distanceTo(origin);
            boolean ok = bot.isAlive() && bot.getHealth() == bot.getMaxHealth() && off < 1.0D;
            return new Probe(ok, SelfTest.fmt("five ticks after dying %s has %.1f health and is %.2f blocks from its spawn point",
                    a, bot.getHealth(), off));
        });
    }
}
