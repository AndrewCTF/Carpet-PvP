package carpet.mixins;

import carpet.CarpetSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import javax.annotation.Nullable;

@Mixin(Minecraft.class)
public class Minecraft_tickMixin
{
    @Shadow @Nullable public net.minecraft.client.player.LocalPlayer player;

    @Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
    private void onGetTickTargetMillis(final float f, final CallbackInfoReturnable<Float> cir)
    {
        if (!CarpetSettings.smoothClientAnimations) {
            cir.setReturnValue(f);
        }
    }

    /**
     * When smoothClientAnimations is enabled, prevent the local player's body/head
     * rotation from lagging behind in 3rd person view by syncing the "old" rotation
     * values to the current ones at the end of each client tick. This makes the local
     * player's model snap to the current rotation instead of slowly interpolating.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void syncCameraEntityRotation(CallbackInfo ci)
    {
        if (!CarpetSettings.smoothClientAnimations) return;
        Minecraft mc = (Minecraft) (Object) this;
        Entity camera = mc.getCameraEntity();
        if (camera instanceof LivingEntity living && camera == player)
        {
            living.yBodyRotO = living.yBodyRot;
            living.yHeadRotO = living.yHeadRot;
        }
    }
}
