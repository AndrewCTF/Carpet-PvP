package carpet.pvp;

import carpet.CarpetServer;
import carpet.CarpetSettings;
import carpet.fakes.PlayerSwordBlockInterface;
import carpet.logic.CarpetLogic;
import carpet.network.ServerNetworkHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class PvpInitializer implements ModInitializer
{
    @Override
    public void onInitialize()
    {
        CarpetServer.manageExtension(CarpetLogic.INSTANCE);
        AttackBlockCallback.EVENT.register(PvpInitializer::punishWrongToolHits);
    }

    /** Breaking a block that needs a tool with the wrong one in hand costs a heart, like hitting a player does. */
    private static InteractionResult punishWrongToolHits(Player player, Level level, InteractionHand hand, BlockPos pos, Direction side)
    {
        if (!CarpetSettings.punishWrongToolHits) return InteractionResult.PASS;
        BlockState state = level.getBlockState(pos);
        if (!state.requiresCorrectToolForDrops() || player.isSpectator()) return InteractionResult.PASS;
        ItemStack held = player.getMainHandItem();
        boolean lacksTool = held.isEmpty() || !held.isCorrectToolForDrops(state);
        if (!lacksTool || player.isCreative()) return InteractionResult.PASS;
        if (!level.isClientSide() && player.level() instanceof ServerLevel serverLevel)
        {
            player.hurtServer(serverLevel, serverLevel.damageSources().generic(), 1.0F);
        }
        return InteractionResult.SUCCESS;
    }

    /**
 * Opens the block window of a player that started using a sword. Called for the vanilla use item packets, for
 * the client's own sword block request and by the fake player's use action; the clients watching the player
 * are told about it right away. Calling it again while the window is already open only tops it up.
 */
    public static void startSwordBlock(Player player, InteractionHand hand)
    {
        if (!CarpetSettings.swordBlockHitting) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!player.getItemInHand(hand).is(ItemTags.SWORDS)) return;
        PlayerSwordBlockInterface block = (PlayerSwordBlockInterface) player;
        boolean wasOpen = block.carpet$getSwordBlockTicks() > 0;
        block.carpet$setSwordBlockTicks(CarpetSettings.swordBlockWindowTicks);
        if (!player.isUsingItem()) player.startUsingItem(hand);
        // A pose the clients have already been told about does not need saying a second time.
        if (!wasOpen) ServerNetworkHandler.sendSwordBlock(level, player, CarpetSettings.swordBlockWindowTicks);
    }

    /** The block window is over, so the clients watching this player stop showing the blocking pose. */
    public static void stopSwordBlock(LivingEntity entity)
    {
        if (!CarpetSettings.swordBlockHitting) return;
        if (!(entity.level() instanceof ServerLevel level)) return;
        ((PlayerSwordBlockInterface) entity).carpet$setSwordBlockTicks(0);
        ServerNetworkHandler.sendSwordBlock(level, entity, 0);
    }
}