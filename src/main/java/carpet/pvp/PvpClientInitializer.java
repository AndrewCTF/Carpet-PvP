package carpet.pvp;

import carpet.client.SwordBlockVisuals;
import carpet.network.ClientNetworkHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;

public final class PvpClientInitializer implements ClientModInitializer
{
    // The game consumes the use key itself inside its own tick, so the press is tracked by hand here.
    private static boolean useDown;

    @Override
    public void onInitializeClient()
    {
        ClientTickEvents.END_CLIENT_TICK.register(client ->
        {
            SwordBlockVisuals.tick();
            LocalPlayer player = client.player;
            boolean down = player != null && client.options.keyUse.isDown();
            boolean pressed = down && !useDown;
            useDown = down;
            if (!pressed) return;
            InteractionHand hand = swordHand(player);
            if (hand != null) ClientNetworkHandler.swordBlockRequest(hand);
        });
    }

    /** The hand the player just pressed use with, main hand first, or null when no sword is held. */
    private static InteractionHand swordHand(LocalPlayer player)
    {
        if (player.getMainHandItem().is(ItemTags.SWORDS)) return InteractionHand.MAIN_HAND;
        if (player.getOffhandItem().is(ItemTags.SWORDS)) return InteractionHand.OFF_HAND;
        return null;
    }
}