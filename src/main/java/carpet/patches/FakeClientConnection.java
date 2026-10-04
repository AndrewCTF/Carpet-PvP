package carpet.patches;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;

import java.lang.reflect.Field;

public class FakeClientConnection extends Connection
{
    private static final Field CHANNEL = channelField();

    public FakeClientConnection(PacketFlow p)
    {
        super(p);
        // A fake player has no client, but the server still treats its connection as open: that is
        // what lets it be hurt, teleport and be listed. An embedded channel costs nothing and also
        // keeps adventure-platform-fabric, which reads the channel, working.
        // This does NOT trigger other vanilla handlers for establishing a channel
        // also makes #isOpen return true, allowing enderpearls to teleport fake players
        try
        {
            CHANNEL.set(this, new EmbeddedChannel());
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Could not give the fake connection a channel", e);
        }
    }

    /** Vanilla keeps the channel private and Paper's server makes it public, so it is written the one way that works on both. */
    private static Field channelField()
    {
        try
        {
            Field field = Connection.class.getDeclaredField("channel");
            field.setAccessible(true);
            return field;
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Vanilla's Connection no longer has a channel field", e);
        }
    }

    @Override
    public void setReadOnly()
    {
    }

    @Override
    public void handleDisconnection()
    {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener packetListener)
    {
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener)
    {
    }
}
