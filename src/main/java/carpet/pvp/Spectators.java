package carpet.pvp;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who is looking through whose eyes, and where they were put back to afterwards.
 *
 * <p>{@code /bot spectate <name>} hands the caller the camera of a bot; {@code /bot spectate stop}
 * gives them their own eyes back, their own game mode and the exact spot and rotation they left
 * from, in the dimension they left it in.</p>
 */
public final class Spectators
{
    /** What a spectating player has to be given back when it is over. */
    private record Spot(Vec3 pos, float yaw, float pitch, ResourceKey<Level> dimension, GameType gameMode)
    {
    }

    private static final Map<UUID, Spot> spots = new HashMap<>();

    private Spectators()
    {
    }

    /**
     * Puts a player in spectator mode looking through a bot's eyes.
     *
     * @return null on success, otherwise the reason it could not be done
     */
    public static String start(ServerPlayer player, Entity camera)
    {
        if (camera == player)
        {
            return "you cannot spectate yourself";
        }
        if (!(camera.level() instanceof ServerLevel))
        {
            return "there is nothing to look through";
        }
        if (spots.containsKey(player.getUUID()))
        {
            stop(player);
        }
        spots.put(player.getUUID(), new Spot(player.position(), player.getYRot(), player.getXRot(),
                player.level().dimension(), player.gameMode.getGameModeForPlayer()));
        player.setGameMode(GameType.SPECTATOR);
        player.setCamera(camera);
        return null;
    }

    /**
     * Gives a player back their own eyes, game mode and position.
     *
     * @return null on success, otherwise the reason it could not be done
     */
    public static String stop(ServerPlayer player)
    {
        Spot spot = spots.remove(player.getUUID());
        if (spot == null)
        {
            return "you are not spectating";
        }
        player.setCamera(null);
        player.setGameMode(spot.gameMode());
        ServerLevel level = spot.dimension() == null || !(player.level() instanceof ServerLevel current)
                ? null : current.getServer().getLevel(spot.dimension());
        if (level != null)
        {
            player.teleportTo(level, spot.pos().x, spot.pos().y, spot.pos().z, Set.<Relative>of(),
                    spot.yaw(), spot.pitch(), true);
        }
        return null;
    }

    /** Forgets a player who has left, so their spot is not kept for ever. */
    public static void forget(UUID player)
    {
        spots.remove(player);
    }

    /** Drops the players who are no longer on the server. */
    public static void forgetOffline(MinecraftServer server)
    {
        spots.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    public static void clear()
    {
        spots.clear();
    }
}