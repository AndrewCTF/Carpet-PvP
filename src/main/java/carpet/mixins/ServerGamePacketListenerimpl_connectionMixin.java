package carpet.mixins;

import carpet.network.CarpetClient;
import carpet.network.ServerNetworkHandler;
import carpet.network.payload.SwordBlockRequestPayload;
import carpet.pvp.PvpInitializer;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerimpl_connectionMixin
{
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void onCustomCarpetPayload(ServerboundCustomPayloadPacket serverboundCustomPayloadPacket, CallbackInfo ci)
    {
        if (serverboundCustomPayloadPacket.payload() instanceof CarpetClient.CarpetPayload cpp) {
            // We should force onto the main thread here
            // ServerNetworkHandler.handleData can possibly mutate data that isn't
            // thread safe, and also allows for client commands to be executed
            PacketUtils.ensureRunningOnSameThread(serverboundCustomPayloadPacket, (ServerGamePacketListener) this, player.level());
            ServerNetworkHandler.onClientData(player, cpp.data());
            ci.cancel();
        }
        if (serverboundCustomPayloadPacket.payload() instanceof SwordBlockRequestPayload request) {
            PacketUtils.ensureRunningOnSameThread(serverboundCustomPayloadPacket, (ServerGamePacketListener) this, player.level());
            PvpInitializer.startSwordBlock(player, request.hand());
            ci.cancel();
        }
    }
}