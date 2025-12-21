package carpet.network.payload;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SwordBlockPayload(int entityId, int ticks) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("carpet", "sword_block");
    public static final Type<SwordBlockPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, SwordBlockPayload> STREAM_CODEC = CustomPacketPayload.codec(
            SwordBlockPayload::write,
            SwordBlockPayload::new
    );

    public SwordBlockPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt());
    }

    public void write(FriendlyByteBuf out) {
        out.writeVarInt(entityId);
        out.writeVarInt(ticks);
    }

    @Override
    public Type<SwordBlockPayload> type() {
        return TYPE;
    }
}
