package carpet.pvp.selftest;

import java.util.ArrayList;
import java.util.List;

import carpet.helpers.EntityPlayerActionPack;
import carpet.pvp.bot.BotCommands;
import carpet.pvp.BotSettings;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec3;

/**
 * The commands the two platforms give a bot, checked where they can differ.
 *
 * <p>Everything here runs the command the host's own fake-player command spells and reads the action pack
 * it left behind, so a Paper node and a Fabric node answer the same question: does {@code nav patrol}
 * take a fourth waypoint, does {@code turn} take a rotation, does {@code glide} carry the whole subtree,
 * and does a source that may not run {@code /bot} get refused rather than served.</p>
 */
final class BotCommandParityScenarios
{
    /** How far the turn is asked for, in degrees. */
    private static final float TURN = 90.0F;
    private static final double TURN_TOLERANCE = 0.5D;
    /** The glide speed the command is asked for. */
    private static final double GLIDE_SPEED = 0.75D;

    private BotCommandParityScenarios() {}

    /** {@code nav patrol} with every waypoint Fabric takes, and the loop flag each spelling sets. */
    static SelfTest.Scenario patrolWaypoints(String a, String b, String c, Vec3 origin)
    {
        Vec3 first = origin.add(4.0D, 0.0D, 0.0D);
        Vec3 second = origin.add(0.0D, 0.0D, 4.0D);
        Vec3 third = origin.add(-4.0D, 0.0D, 0.0D);
        Vec3 fourth = origin.add(0.0D, 0.0D, -4.0D);
        boolean[] asked = {false};
        int[] seen = {0};
        boolean[] loop = {false};
        return new SelfTest.Scenario(400, List.of(new SelfTest.Bot(a, origin)), List.of(), server ->
        {
            EntityPlayerActionPack pack = SelfTest.pack(server, a);
            if (!asked[0])
            {
                if (SelfTest.warmingUp(server, a)) return SelfTest.pending(a + " is still loading");
                seen[0] = SelfTest.result(server, SelfTest.cmd(a + " nav patrol " + SelfTest.coords(first)
                        + " " + SelfTest.coords(second) + " " + SelfTest.coords(third) + " " + SelfTest.coords(fourth)));
                loop[0] = pack.isNavPatrolLoop();
                asked[0] = true;
            }
            int waypoints = pack.getNavPatrolWaypoints().size();
            return new SelfTest.Probe(seen[0] == 1 && waypoints == 4 && loop[0], SelfTest.fmt(
                    "nav patrol with four waypoints returned %d and left %d waypoints on %s, looping: %s",
                    seen[0], waypoints, a, loop[0]));
        });
    }

    /** {@code nav patrol once}, which must not loop, and the two-waypoint form that does. */
    static SelfTest.Scenario patrolModes(String a, String b, String c, Vec3 origin)
    {
        Vec3 first = origin.add(4.0D, 0.0D, 0.0D);
        Vec3 second = origin.add(0.0D, 0.0D, 4.0D);
        int[] stage = {0};
        boolean[] twoLoops = {false};
        boolean[] onceLoops = {true};
        return new SelfTest.Scenario(400, List.of(new SelfTest.Bot(a, origin)), List.of(), server ->
        {
            EntityPlayerActionPack pack = SelfTest.pack(server, a);
            if (SelfTest.warmingUp(server, a)) return SelfTest.pending(a + " is still loading");
            if (stage[0] == 0)
            {
                SelfTest.result(server, SelfTest.cmd(a + " nav patrol " + SelfTest.coords(first)
                        + " " + SelfTest.coords(second)));
                twoLoops[0] = pack.isNavPatrolLoop();
                stage[0] = 1;
                return SelfTest.pending("asked for two waypoints");
            }
            if (stage[0] == 1)
            {
                SelfTest.result(server, SelfTest.cmd(a + " nav patrol " + SelfTest.coords(first)
                        + " " + SelfTest.coords(second) + " once"));
                onceLoops[0] = pack.isNavPatrolLoop();
                stage[0] = 2;
            }
            return new SelfTest.Probe(twoLoops[0] && !onceLoops[0], SelfTest.fmt(
                    "two waypoints loop: %s, the once form loops: %s", twoLoops[0], onceLoops[0]));
        });
    }

    /** {@code turn <yaw> <pitch>}, the spelling Fabric takes, beside the four named turns. */
    static SelfTest.Scenario turnRotation(String a, String b, String c, Vec3 origin)
    {
        boolean[] asked = {false};
        float[] was = {0.0F};
        float[] turned = {0.0F};
        return new SelfTest.Scenario(400, List.of(new SelfTest.Bot(a, origin, 30.0D)), List.of(), server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            if (!asked[0])
            {
                if (SelfTest.warmingUp(server, a)) return SelfTest.pending(a + " is still loading");
                was[0] = bot.getYRot();
                int done = SelfTest.result(server, SelfTest.cmd(a + " turn " + TURN + " 0"));
                turned[0] = bot.getYRot();
                asked[0] = true;
                if (done != 1) return new SelfTest.Probe(false, SelfTest.fmt("turn %s %s 0 returned %d", a, TURN, done));
            }
            float turnedBy = turned[0] - was[0];
            return new SelfTest.Probe(Math.abs(turnedBy - TURN) <= TURN_TOLERANCE, SelfTest.fmt(
                    "%s turned %.1f degrees (from %.1f to %.1f), where %.1f was asked for",
                    a, turnedBy, was[0], turned[0], TURN));
        });
    }

    /** {@code glide}: the subtree Paper had no shape for at all. */
    static SelfTest.Scenario glide(String a, String b, String c, Vec3 origin)
    {
        List<String> asked = new ArrayList<>();
        boolean[] done = {false};
        boolean[] restored = {false};
        boolean[] glideWasOn = {false};
        boolean[] frozen = {false};
        boolean[] arrived = {false};
        boolean[] speeded = {false};
        return new SelfTest.Scenario(400, List.of(new SelfTest.Bot(a, origin), new SelfTest.Bot(b, origin.add(0.0D, 0.0D, 8.0D))),
                List.of(), server ->
        {
                    EntityPlayerActionPack pack = SelfTest.pack(server, a);
                    if (!done[0])
                    {
                        if (SelfTest.warmingUp(server, a, b)) return SelfTest.pending(a + " is still loading");
                        glideWasOn[0] = BotSettings.fakePlayerElytraGlide;
                        // The rule on Fabric and the setting on Paper, both named the same way here.
                        SelfTest.result(server, "carpet fakePlayerElytraGlide true");
                        BotSettings.fakePlayerElytraGlide = true;
                        for (String leaf : List.of("glide start", "glide freeze true", "glide arrival circle",
                                "glide speed " + GLIDE_SPEED, "glide status"))
                        {
                            asked.add(SelfTest.cmd(a + " " + leaf) + " returned "
                                    + SelfTest.result(server, SelfTest.cmd(a + " " + leaf)));
                        }
                        frozen[0] = pack.isGlideFrozen();
                        arrived[0] = pack.getGlideArrivalAction() == EntityPlayerActionPack.GlideArrivalAction.CIRCLE;
                        speeded[0] = Math.abs(pack.getGlideSpeed() - GLIDE_SPEED) < 1.0E-6D;
                        done[0] = true;
                    }
                    if (pack.isGlideEnabled() && !restored[0])
                    {
                        SelfTest.result(server, SelfTest.cmd(a + " glide stop"));
                        restored[0] = !pack.isGlideEnabled();
                    }
                    BotSettings.fakePlayerElytraGlide = glideWasOn[0];
                    boolean allRan = asked.stream().allMatch(line -> line.endsWith("returned 1"));
                    return new SelfTest.Probe(allRan && frozen[0] && arrived[0] && speeded[0] && restored[0]
                                    && BotSettings.fakePlayerElytraGlide == glideWasOn[0],
                            SelfTest.fmt("%s; frozen: %s, arrival circle: %s, speed %.3f: %s, glide off again: %s",
                                    String.join(", ", asked), frozen[0], arrived[0], pack.getGlideSpeed(),
                                    speeded[0], restored[0]));
                });
    }

    /**
     * The {@code /bot} permission. What has to match Fabric is what a sender who may not run the
     * commands is told, and that sentence lives in one shared place: {@link BotCommands#canUse}, which
     * asks {@link BotCommands#mayCommandBots} and says "You don't have permission to use /bot commands"
     * when it is told no. The console is asked first against the gate the host really installed — the
     * {@code commandBot} rule on Fabric, the plugin's permission here — and the refusal is then asked
     * of the shared check with that gate standing in, since neither host can put a real player in front
     * of it: a self-test has no real player, and the gate is deliberately letting bots through.
     */
    static SelfTest.Scenario permission(String a, String b, String c, Vec3 origin)
    {
        boolean[] asked = {false};
        boolean[] consoleAllowed = {false};
        boolean[] refused = {true};
        boolean[] refusedAsPlayer = {true};
        return new SelfTest.Scenario(200, List.of(new SelfTest.Bot(a, origin)), List.of(), server ->
        {
            if (!asked[0])
            {
                if (SelfTest.warmingUp(server, a)) return SelfTest.pending(a + " is still loading");
                Predicate<CommandSourceStack> gate = BotCommands.mayCommandBots;
                consoleAllowed[0] = BotCommands.canUse(server.createCommandSourceStack());
                CommandSourceStack denied = deniedFor(server, SelfTest.player(server, a));
                refusedAsPlayer[0] = !BotCommands.canUse(denied);
                BotCommands.mayCommandBots = source -> false;
                refused[0] = !BotCommands.canUse(denied);
                BotCommands.mayCommandBots = gate;
                asked[0] = true;
            }
            return new SelfTest.Probe(consoleAllowed[0] && refused[0], SelfTest.fmt(
                    "the console was %s by the gate this host installed, and a source the gate refuses was %s;"
                            + " with the gate standing in for a plain player it was %s",
                    consoleAllowed[0] ? "let through" : "refused",
                    refused[0] ? "refused" : "let through",
                    refusedAsPlayer[0] ? "refused" : "let through"));
        });
    }

    /** The console's own source with nothing granted to it, which is what a plain player has. */
    private static CommandSourceStack deniedFor(MinecraftServer server, ServerPlayer player)
    {
        return server.createCommandSourceStack()
                .withEntity(player)
                .withPermission(PermissionSet.NO_PERMISSIONS);
    }
}