package carpet.pvp;

import carpet.CarpetSettings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class PvpInitializer implements ModInitializer
{
    @Override
    public void onInitialize()
    {
        // rule punishWrongToolHits: hitting a block that needs a tool without one hurts the hitter
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
        {
            if (!CarpetSettings.punishWrongToolHits || level.isClientSide() || player.isSpectator() || player.isCreative())
                return InteractionResult.PASS;
            BlockState state = level.getBlockState(pos);
            if (!state.requiresCorrectToolForDrops())
                return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (!held.isEmpty() && held.isCorrectToolForDrops(state))
                return InteractionResult.PASS;
            if (level instanceof ServerLevel serverLevel && player instanceof LivingEntity living)
                living.hurtServer(serverLevel, serverLevel.damageSources().generic(), 1.0F);
            return InteractionResult.SUCCESS;
        });
    }
}
