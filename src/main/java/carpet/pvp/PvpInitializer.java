package carpet.pvp;

import carpet.network.payload.SwordBlockPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import carpet.fakes.PlayerSwordBlockInterface;
import carpet.CarpetSettings;
import carpet.network.payload.SwordBlockRequestPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;

public class PvpInitializer implements ModInitializer {
    @Override
    public void onInitialize() {
        // Register C2S payload codec on the server
        PayloadTypeRegistry.playC2S().register(SwordBlockRequestPayload.TYPE, SwordBlockRequestPayload.STREAM_CODEC);

        // Handle client requests to open block window (right-click air)
        ServerPlayNetworking.registerGlobalReceiver(SwordBlockRequestPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> {
                if (!CarpetSettings.swordBlockHitting) return;
                if (player == null) return;
                if (player.getItemInHand(payload.hand()).is(ItemTags.SWORDS)) {
                    int ticks = CarpetSettings.swordBlockWindowTicks;
                    ((PlayerSwordBlockInterface) player).carpet$setSwordBlockTicks(ticks);
                    ClientboundCustomPayloadPacket msg = new ClientboundCustomPayloadPacket(new SwordBlockPayload(player.getId(), ticks));
                    for (ServerPlayer p : player.level().players()) {
                        if (p.distanceToSqr(player) < 64*64) {
                            p.connection.send(msg);
                        }
                    }
                }
            });
        });

        // Register a block attack callback (left-click on blocks)
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            var state = level.getBlockState(pos);
            // Only consider blocks that require a correct tool for drops; respect settings
            if (!CarpetSettings.punishWrongToolHits) return InteractionResult.PASS;
            if (state.requiresCorrectToolForDrops() && !player.isSpectator()) {
                ItemStack main = player.getMainHandItem();
                boolean lacksTool = main.isEmpty() || !main.isCorrectToolForDrops(state);
                if (lacksTool && !player.isCreative()) {
                    if (!level.isClientSide) {
                        // apply 1 damage using the server damage sources without using deprecated API
                        if (player instanceof net.minecraft.world.entity.LivingEntity le) {
                            le.hurt(((ServerLevel) level).damageSources().generic(), 1.0F);
                        }
                    }
                    return InteractionResult.SUCCESS; // consume
                }
            }
            return InteractionResult.PASS; // let vanilla and other handlers run
        });
    }
}
