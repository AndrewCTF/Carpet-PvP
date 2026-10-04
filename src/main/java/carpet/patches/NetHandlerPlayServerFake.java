package carpet.patches;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import java.util.Set;

public class NetHandlerPlayServerFake extends ServerGamePacketListenerImpl
{
    /** The teleport the last packet the listener sent asked to have confirmed, or null while there is none. */
    private ClientboundPlayerPositionPacket awaiting;

    public NetHandlerPlayServerFake(final MinecraftServer minecraftServer, final Connection connection, final ServerPlayer serverPlayer, final CommonListenerCookie i)
    {
        super(minecraftServer, connection, serverPlayer, i);
    }

    @Override
    public void send(final Packet<?> packetIn)
    {
        // A client answers a teleport with a confirmation, and the listener keeps a flag until it gets one:
        // handleUseItemOn drops every block use while it is set, so a fake player that never answers a
        // teleport can never use a block at all. Answering it here is what a client would have done, and it
        // has to wait until the teleport has been carried out, or the listener would find the player further
        // from where it thinks the player is than it really is.
        if (packetIn instanceof ClientboundPlayerPositionPacket position)
        {
            awaiting = position;
        }
        // A real client applies the motion a hit sends it, to the entity the packet names. A fake
        // player has no client, so the velocity the attacker knocked it back with is kept and applied
        // by its own tick. The entity tracker also sends this listener the motion of every bot it is
        // watching, so only the packet that names this player is its own: applied blindly, ten bots
        // chasing one target all hand each other their velocity, and the target is walked sideways by
        // the crowd behind it.
        //? if >=26.1 {
        if (packetIn instanceof ClientboundSetEntityMotionPacket motion
                && motion.id() == player.getId()
                && player instanceof EntityPlayerMPFake fake)
        {
            Vec3 velocity = motion.movement();
            if (fake.isKnockback(velocity))
            {
                fake.setPendingKnockback(velocity);
            }
        }
        //?} else {
        /*if (packetIn instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == player.getId()
                && player instanceof EntityPlayerMPFake fake)
        {
            Vec3 velocity = motion.getMovement();
            if (fake.isKnockback(velocity))
            {
                fake.setPendingKnockback(velocity);
            }
        }
        *///?}
    }

    @Override
    public void disconnect(Component message)
    {
        if (message.getContents() instanceof TranslatableContents text && (text.getKey().equals("multiplayer.disconnect.idling") || text.getKey().equals("multiplayer.disconnect.duplicate_login")))
        {
            ((EntityPlayerMPFake) player).kill(message);
        }
    }

    @Override
    public void teleport(PositionMoveRotation positionMoveRotation, Set<Relative> set)
    {
        super.teleport(positionMoveRotation, set);
        if (player.level() instanceof ServerLevel level && level.getPlayerByUUID(player.getUUID()) != null) {
            resetPosition();
            level.getChunkSource().move(player);
        }
        confirm();
    }

    /**
     * Answers the teleport the listener has just sent, the way the client this fake player stands in for
     * would. The position the server asked for is the one the player is already at, because the teleport
     * that set it has been carried out by now.
     */
    private void confirm()
    {
        ClientboundPlayerPositionPacket position = awaiting;
        awaiting = null;
        if (position == null) {
            return;
        }
        //? if >=26.3 {
        handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(position.id(), player.getX(),
                player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        //?} else {
/*        handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(position.id()));
*///?}
    }

}