package carpet.mixins;

import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer;

@Mixin(PistonHeadRenderer.class)
public abstract class PistonHeadRenderer_movableBEMixin {
    // Rendering pipeline for piston block entities changed in 1.21.10; behavior disabled until reimplemented.
}
