package carpet.logic.bot;

import carpet.patches.EntityPlayerMPFake;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
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
        GameProfile profile = server.services().profileResolver().fetchByName(name)
                .orElseGet(() -> new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name));
        if (server.getPlayerList().getBans().isBanned(new NameAndId(profile.id(), profile.name())))
        {
            return "Player " + name + " is banned on this server";
        }
        if (!EntityPlayerMPFake.createFake(name, server, pos, yaw, pitch, dimension, GameType.SURVIVAL, false))
        {
            return "Player " + name + " doesn't exist and cannot spawn in online mode";
        }
        return null;
    }

    public boolean kill(String name)
    {
        if (server.getPlayerList().getPlayerByName(name) instanceof EntityPlayerMPFake fake)
        {
            fake.kill((ServerLevel) fake.level());
            return true;
        }
        return false;
    }

    /**
     * @return the controller for the fake player with this name, or null when there is none
     */
    public BotController getController(String name)
    {
        return server.getPlayerList().getPlayerByName(name) instanceof EntityPlayerMPFake fake ? new BotController(fake) : null;
    }

    public static JsonObject describe(ServerPlayer bot)
    {
        JsonObject json = new JsonObject();
        json.addProperty("name", bot.getGameProfile().name());
        json.addProperty("x", bot.getX());
        json.addProperty("y", bot.getY());
        json.addProperty("z", bot.getZ());
        json.addProperty("yaw", bot.getYRot());
        json.addProperty("pitch", bot.getXRot());
        json.addProperty("health", bot.getHealth());
        json.addProperty("maxHealth", bot.getMaxHealth());
        json.addProperty("foodLevel", bot.getFoodData().getFoodLevel());
        json.addProperty("gamemode", bot.gameMode.getGameModeForPlayer().getName());
        json.addProperty("dimension", bot.level().dimension().identifier().toString());
        json.addProperty("sprinting", bot.isSprinting());
        json.addProperty("sneaking", bot.isCrouching());
        for (EquipmentSlot slot : EquipmentSlot.values())
        {
            ItemStack stack = bot.getItemBySlot(slot);
            json.addProperty(slot.getName(), stack.isEmpty() ? "empty" : stack.getItem().toString());
        }
        return json;
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
