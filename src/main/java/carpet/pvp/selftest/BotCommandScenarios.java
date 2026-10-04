package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code /bot} subcommands the other scenarios do not run: stopping a fighter, reading what its
 * body has done and reading the trace of its last fight. Both platforms build their own command tree
 * over these bodies, so a run of them says that the tree reaches them on this host.
 */
final class BotCommandScenarios
{
    private BotCommandScenarios() {}

    /**
     * An expert sword bot fights the player in front of it, and is then stopped and asked about: what
     * it did, what its fight was made of, and whether stopping it really stopped it.
     */
    static Scenario fightingCommands(String a, String b, String c, Vec3 origin)
    {
        Vec3 ahead = origin.add(0.0D, 0.0D, 2.5D);
        List<String> commands = List.of(
                "bot kit give " + a + " sword",
                "bot option " + a + " combatstyle sword",
                "bot option " + a + " difficulty expert",
                "bot option " + a + " combat true");
        return new Scenario(600, List.of(new Bot(a, origin), new Bot(b, ahead, 180.0D)), commands, server ->
        {
            BotBody body = SelfTest.body(server, a);
            if (body == null || body.stats().hits < 1)
            {
                return SelfTest.pending(SelfTest.fmt("waiting for %s to land a hit, it has %s",
                        a, body == null ? "no body yet" : body.stats().describe()));
            }
            int stats = SelfTest.result(server, "bot stats " + a);
            int trace = SelfTest.result(server, "bot trace " + a);
            int stop = SelfTest.result(server, "bot stop " + a);
            EntityPlayerMPFake fighter = (EntityPlayerMPFake) SelfTest.player(server, a);
            boolean stopped = !fighter.getPvpConfig().combat && !SelfTest.pack(server, a).isNavEnabled();
            return new Probe(stats == 1 && trace == 1 && stop == 1 && stopped, SelfTest.fmt(
                    "after %d hits bot stats gave %d, bot trace gave %d and bot stop gave %d, and the fighter is %s",
                    body.stats().hits, stats, trace, stop, stopped ? "stopped" : "still going"));
        });
    }
}