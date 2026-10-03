package carpet.logic.bot;

import carpet.patches.EntityPlayerMPFake;
import carpet.utils.Messenger;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Spawns, removes and looks up the fake players that bot programs drive.
 */
public class BotManager
{
    private static final Pattern BOT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final MinecraftServer server;

    public BotManager(MinecraftServer server)
    {
        this.server = server;
    }

    /**
     * @return null when the spawn was started, otherwise the reason it was refused
     */
    public String spawn(String name, Vec3 pos, float yaw, float pitch, ResourceKey<Level> dimension)
    {
        if (name == null || !BOT_NAME.matcher(name).matches())
        {
            return "Bot names are 1-16 letters, digits or underscores";
        }
        if (EntityPlayerMPFake.isSpawningPlayer(name))
        {
            return "Player " + name + " is currently logging on";
        }
        if (server.getPlayerList().getPlayerByName(name) != null)
        {
            return "Player " + name + " is already logged on";
        }
        if (server.getLevel(dimension) == null || !Level.isInSpawnableBounds(BlockPos.containing(pos)))
        {
            return "Player " + name + " cannot be placed outside of the world";
        }
        // createFake resolves the profile without blocking this thread and applies the ban and whitelist checks.
        if (!EntityPlayerMPFake.createFake(name, server, pos, yaw, pitch, dimension, GameType.SURVIVAL, false))
        {
            return "Player " + name + " doesn't exist and cannot spawn in online mode";
        }
        return null;
    }

    /**
     * Takes a bot out of the world. Killing one would not: a fake player that dies respawns.
     */
    public boolean remove(String name)
    {
        EntityPlayerMPFake bot = getBot(name);
        if (bot == null)
        {
            return false;
        }
        bot.fakePlayerDisconnect(Messenger.s(""));
        return true;
    }

    /**
     * @return the fake player with this name, or null when there is none or the name could not be one
     */
    public EntityPlayerMPFake getBot(String name)
    {
        if (name == null || !BOT_NAME.matcher(name).matches())
        {
            return null;
        }
        return server.getPlayerList().getPlayerByName(name) instanceof EntityPlayerMPFake fake ? fake : null;
    }

    /**
     * @return the controller for the fake player with this name, or null when there is none
     */
    public BotController getController(String name)
    {
        EntityPlayerMPFake fake = getBot(name);
        return fake == null ? null : new BotController(fake);
    }

    public List<ServerPlayer> getBots()
    {
        List<ServerPlayer> bots = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (player instanceof EntityPlayerMPFake)
            {
                bots.add(player);
            }
        }
        return bots;
    }
}
