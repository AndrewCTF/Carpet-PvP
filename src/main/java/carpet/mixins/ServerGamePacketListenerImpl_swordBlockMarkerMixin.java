package carpet.mixins;

import carpet.pvp.PvpInitializer;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImpl_swordBlockMarkerMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleUseItem", at = @At("HEAD"))
    private void markSwordBlock(ServerboundUseItemPacket packet, CallbackInfo ci) {
        //~ if >=26.3 '.getHand()' -> '.hand()'
        PvpInitializer.startSwordBlock(player, packet.hand());
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void markSwordBlockOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        //~ if >=26.3 '.getHand()' -> '.hand()'
        PvpInitializer.startSwordBlock(player, packet.hand());
    }
}