package carpet.mixins;

import carpet.CarpetSettings;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FogRenderer.class, priority = 69420)
public class LevelRenderer_fogOffMixin
{
    @Shadow @Final private GpuBuffer emptyBuffer;

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void carpet$fogOffGetBuffer(FogRenderer.FogMode mode, CallbackInfoReturnable<GpuBufferSlice> cir)
    {
        if (!CarpetSettings.fogOff) return;
        cir.setReturnValue(this.emptyBuffer.slice(0L, (long) FogRenderer.FOG_UBO_SIZE));
    }

}
