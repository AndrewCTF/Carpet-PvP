//? if <26.1 {
/*package carpet.mixins;

import carpet.CarpetSettings;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelRenderer.class)
public class LevelRenderer_creativeNoClipMixin
{
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
    private boolean canSeeWorld(LocalPlayer clientPlayerEntity)
    {
        return clientPlayerEntity.isSpectator() || (CarpetSettings.creativeNoClip && clientPlayerEntity.isCreative());
    }

}
*///?} else {
    package carpet.mixins;

    import carpet.CarpetSettings;
    import net.minecraft.client.Camera;
    import net.minecraft.client.player.LocalPlayer;
    import org.spongepowered.asm.mixin.Mixin;
    import org.spongepowered.asm.mixin.injection.At;
    import org.spongepowered.asm.mixin.injection.Redirect;

    @Mixin(Camera.class)
    public class LevelRenderer_creativeNoClipMixin
    {
        @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
        private boolean canSeeWorld(LocalPlayer clientPlayerEntity)
        {
            return clientPlayerEntity.isSpectator() || (CarpetSettings.creativeNoClip && clientPlayerEntity.isCreative());
        }

    }
//?}