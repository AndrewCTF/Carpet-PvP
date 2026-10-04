package carpet.pvp;

import carpet.pvp.drill.Drills;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Once-a-tick bookkeeping of everything the practices keep: which fights have gone quiet, how the
 * drills and the match of the server are going, and the factions and traces of the world folder.
 *
 * <p>Called from the end of the server tick, which is after every bot has moved and every hit has
 * landed, so what it reads is what the tick actually did. Nothing here moves anything itself.</p>
 */
public final class PvpSystems
{
    private static final Logger LOG = LoggerFactory.getLogger(PvpSystems.class);

    private PvpSystems()
    {
    }

    /** One tick of the practices of a server. */
    public static void tick(MinecraftServer server)
    {
        if (Drills.runningCount() > 0) Drills.tick(server);
        if (MatchManager.active()) MatchManager.tick(server);
        CombatTraces.endIdle(server, server.overworld().getGameTime());
        Spectators.forgetOffline(server);
    }

    /** Called when the server is up: the factions of the world folder come back, nothing else does. */
    public static void onServerStarted(MinecraftServer server)
    {
        FactionManager.restore(FactionStore.load(server));
        CombatTraces.clear();
        Spectators.clear();
        if (MatchManager.active()) MatchManager.stop(server);
    }

    /** Called when the server goes down: what is still open is written out and nothing is left behind. */
    public static void onServerStopped(MinecraftServer server)
    {
        int traces = CombatTraces.flush(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            Spectators.forget(player.getUUID());
        }
        Spectators.clear();
        FactionStore.Snapshot factions = FactionManager.snapshot();
        try
        {
            FactionStore.save(server, factions);
            LOG.info("Saved {} bot faction(s) and {} finished fight trace(s) of this world",
                    factions.factions().size(), traces);
        }
        catch (IOException e)
        {
            LOG.warn("Could not save the bot factions of this world: {}", e.getMessage());
        }
    }
}