package carpet.paper;

import carpet.pvp.BotSettings;
import net.minecraft.server.MinecraftServer;

/**
 * What the plugin has to set up before the self-test's first scenario. Carpet turns navigation on
 * with its {@code fakePlayerNavigation} rule and leaves it on for the whole run; the plugin reads
 * navigation out of {@code config.yml}, so the run turns it on for itself and does the two things
 * that come from the run rather than from the server: no mobs, and nothing but players on the flat
 * world. What the run does not do is sprint, as the Fabric runs do: a sprinting server gets through
 * a scenario's 900 ticks before the chunk system has handed out the ground a few chunks away, and
 * scenarios that need that ground then fail on a course they never got.
 */
final class PaperSelfTest
{
    private PaperSelfTest() {}

    static void setup(MinecraftServer server)
    {
        BotSettings.fakePlayerNavigation = true;
        run(server, "gamerule spawn_mobs false");
        run(server, "kill @e[type=!player]");
    }

    private static void run(MinecraftServer server, String command)
    {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }
}