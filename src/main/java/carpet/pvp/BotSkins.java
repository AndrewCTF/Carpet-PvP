package carpet.pvp;

import carpet.patches.EntityPlayerMPFake;
import carpet.patches.FakeClientConnection;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import carpet.pvp.style.StyleIndex;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Putting the skin of a real account on a bot.
 *
 * <p>Which skin a client draws is not something the server sends: it looks the profile up itself with
 * the session service, so a bot wears the skin of whatever account the profile the server handed it
 * belongs to. The profile is therefore the whole of it, and a live fake player carries one that cannot
 * be swapped, so a bot that is to wear somebody else's skin is placed again with their profile: the
 * name stays the name it had, the profile carries the other account's id, and where it stood, what it
 * held and how it was fighting are put back. The lookup runs in the same background pool the fake
 * player spawn uses and comes back on the server thread.</p>
 */
public final class BotSkins
{
    /** Everything a bot is put back with after it has been given the other profile. */
    private record Setup(String name, GameProfile profile, Vec3 pos, float yaw, float pitch,
            ResourceKey<Level> dimension, GameType gameMode, BotPvpConfig.CombatStyle style, boolean combat,
            String faction)
    {
    }

    private BotSkins()
    {
    }

    /**
     * Puts the skin of {@code playername} on a bot, by resolving that account off the server thread and
     * placing the bot again with the profile once it has come back.
     *
     * @return null when the request went ahead, otherwise the reason it did not
     */
    public static String wear(MinecraftServer server, ServerPlayer bot, String playername)
    {
        if (!(bot instanceof EntityPlayerMPFake fake))
        {
            return bot.getName().getString() + " is not a bot of this server";
        }
        if (!server.usesAuthentication())
        {
            return "a skin belongs to an account, and this server is offline: run it with online-mode on "
                    + "to give a bot somebody's skin";
        }
        if (fake.getName().getString().equalsIgnoreCase(playername))
        {
            return bot.getName().getString() + " already is " + playername;
        }
        CompletableFuture.supplyAsync(() -> server.services().profileResolver().fetchByName(playername),
                Util.nonCriticalIoPool()).whenCompleteAsync((found, thrown) -> {
            Optional<GameProfile> profile = thrown != null ? Optional.empty() : found;
            if (profile.isEmpty())
            {
                report(server, bot.getName().getString(), "no account called " + playername + " could be found");
                return;
            }
            // Two players cannot share an account id, so a bot cannot take over somebody who is here.
            ServerPlayer holder = server.getPlayerList().getPlayer(profile.get().id());
            if (holder != null && !holder.getUUID().equals(bot.getUUID()))
            {
                report(server, bot.getName().getString(),
                        "cannot wear the skin of " + playername + ": " + holder.getName().getString()
                                + " is that account and is on this server");
                return;
            }
            replace(server, fake, profile.get());
        }, server);
        return null;
    }

    /** Places a bot again with the profile of another account, keeping its name and its set-up. */
    private static void replace(MinecraftServer server, EntityPlayerMPFake bot, GameProfile account)
    {
        if (server.getPlayerList().getPlayer(bot.getUUID()) != bot) return;
        BotPvpConfig cfg = bot.getPvpConfig();
        GameProfile profile = new GameProfile(account.id(), bot.getGameProfile().name());
        Setup setup = new Setup(profile.name(), profile, bot.position(), bot.getYRot(), bot.getXRot(),
                bot.level().dimension(), bot.gameMode.getGameModeForPlayer(), cfg.combatStyle, cfg.combat,
                cfg.faction);
        // The fake player leaves on the next tick; the new one goes in right behind it.
        bot.fakePlayerDisconnect(Component.literal("Changing skins"));
        server.schedule(new TickTask(server.getTickCount(), () -> place(server, setup)));
    }

    private static void place(MinecraftServer server, Setup setup)
    {
        ServerLevel level = server.getLevel(setup.dimension());
        if (level == null)
        {
            report(server, setup.name(), "the world it was in is not here any more");
            return;
        }
        EntityPlayerMPFake bot = EntityPlayerMPFake.respawnFake(server, level, setup.profile(),
                ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(new FakeClientConnection(PacketFlow.SERVERBOUND), bot,
                new CommonListenerCookie(setup.profile(), 0, bot.clientInformation(), false));
        bot.teleportTo(level, setup.pos().x, setup.pos().y, setup.pos().z, Set.<Relative>of(),
                setup.yaw(), setup.pitch(), true);
        bot.setHealth(20.0F);
        bot.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
        bot.gameMode.changeGameModeForPlayer(setup.gameMode());
        bot.spawnPos = setup.pos();
        bot.spawnYaw = setup.yaw();
        server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(bot,
                (byte) (bot.yHeadRot * 256 / 360)), setup.dimension());
        server.getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(bot), setup.dimension());

        BotPvpConfig cfg = bot.getPvpConfig();
        cfg.combatStyle = setup.style();
        cfg.combat = setup.combat();
        cfg.autoTarget = setup.combat();
        cfg.targetBots = setup.combat();
        cfg.faction = setup.faction();
        Kit given = kit(server, setup.style());
        if (given != null)
        {
            KitInventory.apply(bot, given, server.registryAccess());
        }
        if (setup.faction() != null)
        {
            FactionManager.create(setup.faction());
            FactionManager.join(setup.faction(), bot.getUUID());
        }
        report(server, setup.name(), "wears the skin of " + setup.profile().name()
                + " (" + setup.profile().id() + ")");
    }

    /** The built-in kit a bot of this style is given again, or null when the style has none. */
    private static Kit kit(MinecraftServer server, BotPvpConfig.CombatStyle style)
    {
        String name = StyleIndex.kit(style);
        return name == null ? null : KitStore.of(server).get(name).orElse(null);
    }

    private static void report(MinecraftServer server, String bot, String message)
    {
        server.getPlayerList().broadcastSystemMessage(Component.literal(bot + " " + message), false);
    }
}
