package carpet.mixins;

import carpet.CarpetSettings;
import carpet.fakes.PlayerSwordBlockInterface;
import carpet.network.payload.SwordBlockPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
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
        if (!CarpetSettings.swordBlockHitting) return;
        //~ if >=26.3 '.getHand()' -> '.hand()'
        InteractionHand hand = packet.hand();
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.isEmpty() && stack.is(ItemTags.SWORDS)) {
            int ticks = CarpetSettings.swordBlockWindowTicks;
            ((PlayerSwordBlockInterface) player).carpet$setSwordBlockTicks(ticks);
            
            // Force the player to start using the sword to trigger the blocking animation
            if (!player.isUsingItem()) {
                player.startUsingItem(hand);
            }
            
            // broadcast simple visual cue to nearby clients
            ClientboundCustomPayloadPacket msg = new ClientboundCustomPayloadPacket(new SwordBlockPayload(player.getId(), ticks));
            for (ServerPlayer p : player.level().players()) {
                if (p.distanceToSqr(player) < 64*64) {
                    p.connection.send(msg);
                }
            }
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void markSwordBlockOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!CarpetSettings.swordBlockHitting) return;
        //~ if >=26.3 '.getHand()' -> '.hand()'
        InteractionHand hand = packet.hand();
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.isEmpty() && stack.is(ItemTags.SWORDS)) {
            int ticks = CarpetSettings.swordBlockWindowTicks;
            ((PlayerSwordBlockInterface) player).carpet$setSwordBlockTicks(ticks);
            
            // Force the player to start using the sword to trigger the blocking animation
            if (!player.isUsingItem()) {
                player.startUsingItem(hand);
            }
            
            ClientboundCustomPayloadPacket msg = new ClientboundCustomPayloadPacket(new SwordBlockPayload(player.getId(), ticks));
            for (ServerPlayer p : player.level().players()) {
                if (p.distanceToSqr(player) < 64*64) {
                    p.connection.send(msg);
                }
            }
        }
    }
}
