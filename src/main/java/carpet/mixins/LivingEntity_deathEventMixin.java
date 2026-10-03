package carpet.mixins;

import carpet.pvp.BotEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports an entity dying to the bots that were fighting it, so that a program watching for a kill hears it
 * from the game rather than having to look for it.
 *
 * <p>The game decides to call {@code die} in one place, whatever the entity's own death turns out to be: a mob
 * dies, a player dies, and a fake player is put back on its spawn point. That decision is what is hooked.</p>
 */
@Mixin(LivingEntity.class)
public class LivingEntity_deathEventMixin
{
    @Inject(method = "hurtServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            shift = At.Shift.BEFORE))
    private void reportDeath(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir)
    {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level() instanceof ServerLevel where)
        {
            BotEvents.targetDied(self, where);
        }
    }
}