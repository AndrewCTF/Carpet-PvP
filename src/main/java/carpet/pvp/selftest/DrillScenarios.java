package carpet.pvp.selftest;

import carpet.pvp.drill.DrillRun;
import carpet.pvp.drill.Drills;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** The drills, run by a fake player standing in for one. */
final class DrillScenarios
{
    private DrillScenarios()
    {
    }

    /**
     * The aim drill: a bot walks sideways at a fixed distance and never hits back, and the swings the
     * player makes at it are counted into hits per swing. The drill hands the inventory back at the end.
     */
    static Scenario aimScores(String a, String b, String c, Vec3 origin)
    {
        String[] step = {"setup"};
        int[] accepted = {0};
        boolean[] stopped = {false};
        return new Scenario(900, List.of(new Bot(a, origin)), List.of(), SelfTest.NOTHING, server ->
        {
            ServerPlayer player = SelfTest.player(server, a);
            if ("setup".equals(step[0]))
            {
                if (SelfTest.warmingUp(server, a))
                {
                    return SelfTest.pending("waiting for " + a + " to finish loading");
                }
                SelfTest.run(server, SelfTest.cmd(a + " equip mainhand minecraft:diamond_sword"));
                accepted[0] = MatchScenarios.asPlayer(server, a, "bot drill aim");
                if (accepted[0] != 1)
                {
                    step[0] = "refused";
                    return SelfTest.pending("the drill was refused");
                }
                SelfTest.run(server, SelfTest.cmd(a + " attack continuous"));
                step[0] = "run";
                return SelfTest.pending("the aim drill has started");
            }
            if (Drills.running(player))
            {
                ServerPlayer bot = server.getPlayerList().getPlayerByName(Drills.runningBotName(player));
                if (bot == null)
                {
                    return SelfTest.pending("waiting for the drill bot to join");
                }
                // The player of this scenario keeps its eyes on the bot the way a player would, so the
                // score is about where the swings go rather than about a fixed heading.
                look(player, bot);
                return SelfTest.pending("aiming: " + Drills.score(player));
            }
            if (!stopped[0])
            {
                stopped[0] = true;
                SelfTest.run(server, SelfTest.cmd(a + " stop"));
                return SelfTest.pending("the aim drill has ended");
            }
            String summary = Drills.lastSummary(player);
            int hits = leadingNumber(summary);
            int swords = DrillRun.count(player, Items.DIAMOND_SWORD);
            boolean ok = summary != null && hits >= 1 && swords == 1;
            return new Probe(ok, SelfTest.fmt(
                    "the drill ended with \"%s\", that is %d hit(s), and the player has their %d diamond sword back",
                    summary, hits, swords));
        });
    }

    /**
     * A drill whose needs the player has not got says why and starts nothing, so the inventory of the
     * player is never touched.
     */
    static Scenario skipsWithoutWhatItNeeds(String a, String b, String c, Vec3 origin)
    {
        String[] step = {"setup"};
        int[] stunslam = {-1};
        int[] retotem = {-1};
        int[] unknown = {-1};
        int[] axes = {-1};
        return new Scenario(400, List.of(new Bot(a, origin)), List.of(), SelfTest.NOTHING, server ->
        {
            if ("setup".equals(step[0]))
            {
                if (SelfTest.warmingUp(server, a))
                {
                    return SelfTest.pending("waiting for " + a + " to finish loading");
                }
                // Empty handed and without an axe, a mace or a totem, so every drill that needs one refuses.
                stunslam[0] = MatchScenarios.asPlayer(server, a, "bot drill stunslam");
                retotem[0] = MatchScenarios.asPlayer(server, a, "bot drill retotem");
                unknown[0] = MatchScenarios.asPlayer(server, a, "bot drill nosuchdrill");
                axes[0] = DrillRun.count(SelfTest.player(server, a), Items.IRON_AXE);
                step[0] = "check";
                return SelfTest.pending("the drills have been asked for");
            }
            boolean none = !Drills.running(SelfTest.player(server, a));
            boolean ok = stunslam[0] == 0 && retotem[0] == 0 && unknown[0] == 0 && none && axes[0] == 0;
            return new Probe(ok, SelfTest.fmt(
                    "empty handed, /bot drill stunslam gave %d, /bot drill retotem gave %d and an unknown drill gave %d; no drill is running (%s) and the player still holds %d axe",
                    stunslam[0], retotem[0], unknown[0], none, axes[0]));
        });
    }

    /**
     * The stun-slam drill is about a shield, and the sword kit its bot is given carries none: the drill
     * hands the bot its own, and the bot has to be standing behind it.
     */
    static Scenario stunslamShield(String a, String b, String c, Vec3 origin)
    {
        String[] step = {"setup"};
        return new Scenario(400, List.of(new Bot(a, origin)), List.of(), SelfTest.NOTHING, server ->
        {
            ServerPlayer player = SelfTest.player(server, a);
            if ("setup".equals(step[0]))
            {
                if (SelfTest.warmingUp(server, a))
                {
                    return SelfTest.pending("waiting for " + a + " to finish loading");
                }
                SelfTest.run(server, "give " + a + " minecraft:diamond_axe");
                SelfTest.run(server, "give " + a + " minecraft:mace");
                step[0] = "start";
                return SelfTest.pending("the player has an axe and a mace");
            }
            if ("start".equals(step[0]))
            {
                if (MatchScenarios.asPlayer(server, a, "bot drill stunslam") != 1)
                {
                    return new Probe(false, "/bot drill stunslam was refused to a player carrying an axe and a mace");
                }
                step[0] = "run";
                return SelfTest.pending("the stun-slam drill has started");
            }
            if (!Drills.running(player))
            {
                return new Probe(false, "the drill ended before its bot raised a shield");
            }
            ServerPlayer bot = server.getPlayerList().getPlayerByName(Drills.runningBotName(player));
            if (bot == null || !bot.isBlocking())
            {
                return SelfTest.pending("waiting for the drill bot to raise its shield");
            }
            boolean shield = bot.getOffhandItem().is(Items.SHIELD);
            boolean sword = bot.getMainHandItem().is(Items.DIAMOND_SWORD);
            Drills.stop(server, player);
            return new Probe(shield && sword && !Drills.running(player), SelfTest.fmt(
                    "the drill bot stands behind a raised shield (shield in the off hand: %s) with the sword kit's sword in hand (%s), and the drill stops when asked",
                    shield, sword));
        });
    }

    /** Turns the view of the player onto what it is looking at. */
    private static void look(ServerPlayer player, ServerPlayer bot)
    {
        double dx = bot.getX() - player.getX();
        double dy = bot.getY() + bot.getBbHeight() * 0.5D - player.getEyeY();
        double dz = bot.getZ() - player.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(1.0E-6D, Math.hypot(dx, dz))));
        player.forceSetRotation(yaw, false, pitch, false);
    }

    /** The number a drill summary starts with, or -1 when there is no summary. */
    private static int leadingNumber(String summary)
    {
        if (summary == null) return -1;
        int end = summary.indexOf(' ');
        if (end < 1) return -1;
        try
        {
            return Integer.parseInt(summary.substring(0, end));
        }
        catch (NumberFormatException notANumber)
        {
            return -1;
        }
    }
}