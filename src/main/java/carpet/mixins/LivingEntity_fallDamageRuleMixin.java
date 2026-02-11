package carpet.mixins;

import carpet.CarpetSettings;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntity_fallDamageRuleMixin
{
    @Inject(method = "checkFallDamage", at = @At("HEAD"), cancellable = true)
    private void carpetPvp_gateFallDamage(double y, boolean onGround, BlockState state, BlockPos pos, CallbackInfo ci)
    {
        Object self = this;
        if (self instanceof EntityPlayerMPFake)
        {
            return;
        }
        if (self instanceof Player && !CarpetSettings.playerFallDamage)
        {
            ci.cancel();
        }
    }
}
