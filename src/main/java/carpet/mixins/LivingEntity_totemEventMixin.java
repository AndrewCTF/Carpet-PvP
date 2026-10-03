package carpet.mixins;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports a totem of undying saving the life of a fake player, which the game decides in one place and never
 * tells anyone about.
 */
@Mixin(LivingEntity.class)
public class LivingEntity_totemEventMixin
{
    @Inject(method = "checkTotemDeathProtection", at = @At("RETURN"))
    private void reportTotemPop(DamageSource source, CallbackInfoReturnable<Boolean> cir)
    {
        // the totem is only spent when the game decided to spend it
        if (cir.getReturnValue() && (Object) this instanceof EntityPlayerMPFake bot)
        {
            BotEvents.totemPopped(bot);
        }
    }
}