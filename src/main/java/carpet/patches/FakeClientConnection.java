package carpet.patches;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;

public class FakeClientConnection extends Connection
{
    public FakeClientConnection(PacketFlow p)
    {
        super(p);
        // A fake player has no client, but the server still treats its connection as open: that is
        // what lets it be hurt, teleport and be listed. An embedded channel costs nothing and also
        // keeps adventure-platform-fabric, which reads the channel, working.
        // This does NOT trigger other vanilla handlers for establishing a channel
        // also makes #isOpen return true, allowing enderpearls to teleport fake players
        // The field is opened by the access widener on Fabric and is public on Paper. It is not looked up
        // by name: a released jar for an obfuscated Minecraft has no field called "channel".
        this.channel = new EmbeddedChannel();
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
