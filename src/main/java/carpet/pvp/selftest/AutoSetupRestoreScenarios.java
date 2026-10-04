package carpet.pvp.selftest;

import carpet.pvp.autosetup.AutoSetupManager;
import carpet.pvp.autosetup.AutoSetupSession;
import carpet.pvp.autosetup.AutoSetupSettings;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.List;

/**
 * A session of a player who is not there cannot hand their things back, so its file is kept and what
 * is in it is given back when they come back: at their next login, or before a new session of theirs
 * starts and writes over the file holding it.
 *
 * <p>Both players here are fake players run as the sender of the command, which is all a real one is
 * as far as the command can tell. Neither is one of the scenario's own bots: a scenario cannot watch
 * a player that has logged out.</p>
 */
final class AutoSetupRestoreScenarios
{
    /** The setting the scenarios turn off and on again around a session. */
    private static final String NAVIGATION = "fakePlayerNavigation";

    private AutoSetupRestoreScenarios() {}

    static Scenario loginRecovers(String a, String b, String c, Vec3 origin)
    {
        String logging = a + "Login";
        String restarting = a + "Restart";
        int[] phase = {0};
        Held<List<ItemStack>> own = new Held<>(List.of());
        Held<List<ItemStack>> otherOwn = new Held<>(List.of());
        Held<Vec3> ownSpot = new Held<>(Vec3.ZERO);
        Held<Vec3> otherSpot = new Held<>(Vec3.ZERO);
        return new Scenario(600, List.of(), List.of(SelfTest.cmd(logging + " spawn at " + SelfTest.coords(origin)),
                SelfTest.cmd(restarting + " spawn at " + SelfTest.coords(origin.add(8.0D, 0.0D, 0.0D)))), server ->
        {
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, logging, restarting))
                {
                    return pending("waiting for both players to finish loading");
                }
                AutoSetupSettings.set(NAVIGATION, "false");
                give(server, logging);
                give(server, restarting);
                own.value = SelfTest.slots(SelfTest.player(server, logging));
                otherOwn.value = SelfTest.slots(SelfTest.player(server, restarting));
                ownSpot.value = SelfTest.player(server, logging).position();
                otherSpot.value = SelfTest.player(server, restarting).position();
                phase[0] = 1;
                return pending("two players are here with things of their own to lose");
            }
            if (phase[0] == 1)
            {
                // Both start a session and log out of it, which is what leaves a file waiting.
                if (SelfTest.result(server, as(logging, "auto-setup sword beginner")) < 1)
                {
                    return new Probe(false, "/auto-setup did not start a session for " + logging);
                }
                if (SelfTest.result(server, as(restarting, "auto-setup sword beginner")) < 1)
                {
                    return new Probe(false, "/auto-setup did not start a session for " + restarting);
                }
                phase[0] = 2;
                return pending("both players are fighting");
            }
            if (phase[0] == 2)
            {
                if (AutoSetupManager.session(SelfTest.player(server, logging)) == null
                        || AutoSetupManager.session(SelfTest.player(server, restarting)) == null)
                {
                    return pending("waiting for both sessions to start");
                }
                SelfTest.run(server, SelfTest.cmd(logging + " disconnect"));
                SelfTest.run(server, SelfTest.cmd(restarting + " disconnect"));
                phase[0] = 3;
                return pending("both players logged out of their sessions");
            }
            if (phase[0] == 3)
            {
                if (AutoSetupManager.session(logging) != null || AutoSetupManager.session(restarting) != null)
                {
                    return pending("waiting for both sessions to end");
                }
                if (SelfTest.player(server, logging) != null || SelfTest.player(server, restarting) != null)
                {
                    return pending("waiting for both players to be off the server");
                }
                for (String player : List.of(logging, restarting))
                {
                    if (!Files.isRegularFile(AutoSetupManager.fileFor(server, player)))
                    {
                        return new Probe(false, "the file of " + player + " was not kept");
                    }
                    if (!AutoSetupManager.isUnrecovered(player))
                    {
                        return new Probe(false, "what " + player + " is owed is not waiting for their login");
                    }
                }
                phase[0] = 4;
                return pending("both files are kept and waiting for their players");
            }
            if (phase[0] == 4)
            {
                // The first player logs in, which is all it takes to be given back what was kept.
                if (SelfTest.result(server, SelfTest.cmd(logging + " spawn at " + SelfTest.coords(origin))) < 1)
                {
                    return pending(logging + " could not log in again");
                }
                ServerPlayer back = SelfTest.player(server, logging);
                if (back == null) return pending("waiting for " + logging + " to be on the server again");
                AutoSetupManager.onPlayerJoined(server, back);
                phase[0] = 5;
                return pending(logging + " logged in again");
            }
            if (phase[0] == 5)
            {
                String problem = SelfTest.sameInventory(own.value, SelfTest.slots(SelfTest.player(server, logging)));
                if (problem != null) return new Probe(false, "after logging in again " + logging + " has " + problem);
                if (AutoSetupManager.isUnrecovered(logging))
                {
                    return new Probe(false, logging + " is still owed what they had before the session");
                }
                if (Files.exists(AutoSetupManager.fileFor(server, logging)))
                {
                    return new Probe(false, "the file of " + logging + " was left behind");
                }
                // The second player comes back and asks for a session without logging in through the
                // door the other one used: the file has to be given back before the new one is written.
                if (SelfTest.result(server, SelfTest.cmd(restarting + " spawn at "
                        + SelfTest.coords(origin.add(8.0D, 0.0D, 0.0D)))) < 1)
                {
                    return pending(restarting + " could not log in again");
                }
                if (SelfTest.player(server, restarting) == null)
                {
                    return pending("waiting for " + restarting + " to be on the server again");
                }
                if (SelfTest.result(server, as(restarting, "auto-setup sword beginner")) < 1)
                {
                    return new Probe(false, "/auto-setup did not start a new session for " + restarting);
                }
                phase[0] = 6;
                return pending(restarting + " asked for a new session over the file that was kept");
            }
            if (phase[0] == 6)
            {
                AutoSetupSession session = AutoSetupManager.session(SelfTest.player(server, restarting));
                if (session == null) return pending("waiting for the new session of " + restarting + " to start");
                if (!SelfTest.holds(SelfTest.player(server, restarting).getMainHandItem(), "diamond_sword"))
                {
                    return pending("waiting for " + restarting + " to be given the kit of the new session");
                }
                if (SelfTest.result(server, as(restarting, "auto-setup stop")) < 1)
                {
                    return pending("stopping the new session of " + restarting);
                }
                phase[0] = 7;
                return pending("the new session is stopping");
            }
            if (phase[0] == 7)
            {
                if (AutoSetupManager.session(restarting) != null) return pending("waiting for the session to end");
                // The things of before the first session: had the file been written over without being
                // given back, the kit of the sessions would be what this player ends up with.
                String problem = SelfTest.sameInventory(otherOwn.value, SelfTest.slots(SelfTest.player(server, restarting)));
                if (problem != null)
                {
                    return new Probe(false, "after a new session over a file that was kept " + restarting + " has " + problem);
                }
                if (Files.exists(AutoSetupManager.fileFor(server, restarting)))
                {
                    return new Probe(false, "the file of " + restarting + " was left behind");
                }
                if (SelfTest.player(server, logging).position().distanceTo(ownSpot.value) > 0.01D)
                {
                    return new Probe(false, logging + " was not put back where they stood");
                }
                if (SelfTest.player(server, restarting).position().distanceTo(otherSpot.value) > 0.01D)
                {
                    return new Probe(false, restarting + " was not put back where they stood");
                }
                AutoSetupSettings.set(NAVIGATION, "true");
                return new Probe(true, SelfTest.fmt("%s was given their %d slots back at their next login, and "
                                + "%s got theirs back before a new session was written over the file holding them",
                        logging, own.value.size(), restarting));
            }
            return pending("the scenario is over");
        });
    }

    /** Something worth telling apart from an empty inventory, in two stacks. */
    private static void give(MinecraftServer server, String player)
    {
        SelfTest.run(server, "give " + player + " minecraft:golden_apple 3");
        SelfTest.run(server, "give " + player + " minecraft:emerald 5");
        SelfTest.run(server, "gamemode creative " + player);
    }

    /** One value a scenario carries from one tick of its check to the next. */
    private static final class Held<T>
    {
        private T value;

        Held(T value)
        {
            this.value = value;
        }
    }

    /** A run of the command as the given player, which is what a click of the menu does. */
    private static String as(String player, String command)
    {
        return "execute as " + player + " run " + command;
    }

    private static Probe pending(String detail)
    {
        return new Probe(false, detail);
    }
}
