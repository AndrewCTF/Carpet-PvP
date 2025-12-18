package carpet.mixins;

import carpet.CarpetSettings;
import carpet.utils.CommandHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.PerfCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PerfCommand.class)
public class PerfCommand_permissionMixin
{
    // 1.21.8: make this injection optional to avoid crash if the target signature/name changed
    @Inject(method = {"method_37340", "canRun", "m_37340_"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void canRun(CommandSourceStack source, CallbackInfoReturnable<Boolean> cir)
    {
        if (CarpetSettings.perfPermissionLevel >= 0)
        {
            cir.setReturnValue(CommandHelper.hasPermissionLevel(source, CarpetSettings.perfPermissionLevel));
        }
    }

}
