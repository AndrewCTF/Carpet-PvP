package carpet.mixins;

import carpet.CarpetSettings;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class Player_fallDamageRuleMixin
{
    @Inject(method = "checkFallDamage", at = @At("HEAD"), cancellable = true)
    private void carpetPvp_gateFallDamage(double y, boolean onGround, BlockState state, BlockPos pos, CallbackInfo ci)
    {
        if ((Object) this instanceof EntityPlayerMPFake)
        {
            return;
        }
        if (!CarpetSettings.playerFallDamage)
        {
            ci.cancel();
        }
    }
}
