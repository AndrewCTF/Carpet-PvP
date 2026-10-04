package carpet.paper;

import carpet.pvp.BotSettings;
import net.minecraft.server.MinecraftServer;

/**
 * What the plugin has to set up before the self-test's first scenario. Carpet turns navigation on
 * with its {@code fakePlayerNavigation} rule and leaves it on for the whole run; the plugin reads
 * navigation out of {@code config.yml}, so the run turns it on for itself and does the two things
 * that come from the run rather than from the server: no mobs, and nothing but players on the flat
 * world.
 *
 * <p>The run then asks for {@value #TICKS_A_SECOND} ticks a second rather than the twenty a server
 * keeps, because a scenario is mostly waiting and a node has a core per core to spend. Not the
 * {@code /tick sprint} the Fabric runs use: sprinting is unbounded, and at 368 ticks a second Paper's
 * chunk system stops handing out the ground a scenario is standing on, so {@code nav_smooth} waits for
 * chunks that never come, the bow scenarios lose the aim they had lined up and {@code crossbow_cycle}
 * fires 46 shots into empty air. A fixed rate is slow enough that the chunk system keeps up and the
 * bot behaviour is the same as it is at twenty; the measured cost of that is about four times the
 * twenty-minute node, down to five.</p>
 */
final class PaperSelfTest
{
    /** The tick rate the self-test runs at, and why it is this one rather than more. */
    private static final int TICKS_A_SECOND = 80;

    private PaperSelfTest() {}

    static void setup(MinecraftServer server)
    {
        BotSettings.fakePlayerNavigation = true;
        run(server, "gamerule spawn_mobs false");
        run(server, "kill @e[type=!player]");
        run(server, "tick rate " + TICKS_A_SECOND);
    }

    private static void run(MinecraftServer server, String command)
    {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }
}