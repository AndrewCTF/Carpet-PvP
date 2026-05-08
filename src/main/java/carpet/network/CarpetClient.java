package carpet.network;

import carpet.CarpetSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class CarpetClient
{
    public static final String HI = "69";
    public static final String HELLO = "420";
    public static String serverCarpetVersion;

    public static final Identifier CARPET_CHANNEL = Identifier.fromNamespaceAndPath("carpet", "hello");

    // Stub implementations for server-side compilation
    public static void setCarpet()
    {
        // Client-only functionality - no-op on server
    }

    public static void onClientCommand(Tag data)
    {
        // Client-only functionality
    }

    public static class CarpetPayload implements CustomPacketPayload
    {
        private final CompoundTag data;

        public CarpetPayload(CompoundTag data)
        {
            this.data = data;
        }

        public void write(FriendlyByteBuf buf)
        {
            buf.writeNbt(data);
        }

        public static CarpetPayload read(FriendlyByteBuf buf)
        {
            return new CarpetPayload(buf.readNbt());
        }

        @Override
        public Type type()
        {
            return TYPE;
        }

        public static final Type TYPE = new Type(Identifier.fromNamespaceAndPath("carpet", "hello"));
    }
}