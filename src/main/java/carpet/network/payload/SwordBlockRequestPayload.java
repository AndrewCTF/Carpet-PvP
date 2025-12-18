package carpet.network.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

public record SwordBlockRequestPayload(InteractionHand hand) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("carpet", "sword_block_request");
    public static final Type<SwordBlockRequestPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, SwordBlockRequestPayload> STREAM_CODEC = CustomPacketPayload.codec(
            SwordBlockRequestPayload::write,
            SwordBlockRequestPayload::new
    );

    public SwordBlockRequestPayload(FriendlyByteBuf buf) {
        this(buf.readEnum(InteractionHand.class));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeEnum(this.hand);
    }

    @Override
    public Type<SwordBlockRequestPayload> type() {
        return TYPE;
    }
}
