package carpet.pvp;

import carpet.network.payload.SwordBlockPayload;
import carpet.client.SwordBlockVisuals;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.AbstractClientPlayer;
import carpet.network.payload.SwordBlockRequestPayload;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public class PvpClientInitializer implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register S2C custom payload codec on client (guard against double-registration)
        try {
            PayloadTypeRegistry.playS2C().register(SwordBlockPayload.TYPE, SwordBlockPayload.STREAM_CODEC);
        } catch (IllegalArgumentException ignored) {}

        // Register C2S request payload codec on client (guard against double-registration)
        try {
            PayloadTypeRegistry.playC2S().register(SwordBlockRequestPayload.TYPE, SwordBlockRequestPayload.STREAM_CODEC);
        } catch (IllegalArgumentException ignored) {}

        // Handle incoming block-hit payloads
        ClientPlayNetworking.registerGlobalReceiver(SwordBlockPayload.TYPE, (payload, context) -> {
            var client = context.client();
            client.execute(() -> {
                var level = client.level;
                if (level == null) return;
                var e = level.getEntity(payload.entityId());
                if (e instanceof AbstractClientPlayer p) {
                    SwordBlockVisuals.activate(p, payload.ticks());
                }
            });
        });

    // Client tick: send C2S request on right-click press with a sword (edge trigger)
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;
            var options = client.options;

            if (options.keyUse.consumeClick()) {
                // prefer main hand, then offhand
                InteractionHand hand = InteractionHand.MAIN_HAND;
                ItemStack stack = client.player.getItemInHand(hand);
                if (stack.isEmpty() || !stack.is(ItemTags.SWORDS)) {
                    hand = InteractionHand.OFF_HAND;
                    stack = client.player.getItemInHand(hand);
                }
                if (!stack.isEmpty() && stack.is(ItemTags.SWORDS)) {
                    ClientPlayNetworking.send(new SwordBlockRequestPayload(hand));
                }
            }
        });
    }
}
