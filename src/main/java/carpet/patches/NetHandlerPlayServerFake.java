package carpet.patches;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import java.util.Set;

public class NetHandlerPlayServerFake extends ServerGamePacketListenerImpl
{
    public NetHandlerPlayServerFake(final MinecraftServer minecraftServer, final Connection connection, final ServerPlayer serverPlayer, final CommonListenerCookie i)
    {
        super(minecraftServer, connection, serverPlayer, i);
    }

    @Override
    public void send(final Packet<?> packetIn)
    {
        // A real client applies the motion a hit sends it. A fake player has none, so the velocity the
        // attacker knocked it back with is kept and applied by its own tick.
        if (packetIn instanceof ClientboundSetEntityMotionPacket motion
                && player instanceof EntityPlayerMPFake fake)
        {
            //~ if >=26.1 'getMovement()' -> 'movement()'
            Vec3 velocity = motion.movement();
            if (fake.isKnockback(velocity))
            {
                fake.setPendingKnockback(velocity);
            }
        }
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
        if (((net.minecraft.server.level.ServerLevel) player.level()).getPlayerByUUID(player.getUUID()) != null) {
            resetPosition();
            ((net.minecraft.server.level.ServerLevel) player.level()).getChunkSource().move(player);
        }
    }

}



