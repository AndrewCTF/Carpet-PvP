package carpet.mixins;

import carpet.CarpetSettings;
import carpet.fakes.PlayerSwordBlockInterface;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(LivingEntity.class)
public abstract class LivingEntity_getKBFix {

    @Inject(
            method = "getKnockback(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;)F",
            at = @At("HEAD"),
            cancellable = true
    )
    private void modifyKnockback(Entity entity, DamageSource damageSource, CallbackInfoReturnable<Float> cir) {
        // entity is the target (victim)
        boolean targetIsBlocking = false;
        if (CarpetSettings.swordBlockHitting && entity instanceof LivingEntity target && target instanceof PlayerSwordBlockInterface) {
            targetIsBlocking = ((PlayerSwordBlockInterface) target).carpet$getSwordBlockTicks() > 0;
        }

        float baseKnockback = (float) ((LivingEntity) (Object) this).getAttributeValue(Attributes.ATTACK_KNOCKBACK);
        Level level = ((LivingEntity) (Object) this).level();
        float result = baseKnockback;
        if (level instanceof ServerLevel serverLevel) {
            result = EnchantmentHelper.modifyKnockback(serverLevel, ((LivingEntity) (Object) this).getMainHandItem(), entity, damageSource, baseKnockback);
        }
        if (targetIsBlocking) {
            result *= (float) CarpetSettings.swordBlockKnockbackMultiplier;
        }
        cir.setReturnValue(result);
    }

    // The attack knockback attribute is 0 for players, so getKnockback above is what a mace stab and a
    // mob's ram use and nothing else. A melee hit is pushed by dealDefaultKnockback, which works out its
    // own 0.4, so the sword block reduction has to be applied there as well. Only 26.1 and up push a
    // melee hit that way: before that a melee hit went through getKnockback, which is handled above.
//? if >=26.1 {
    @ModifyArgs(
            method = "dealDefaultKnockback",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;F)V")
    )
    private void reduceKnockbackWhileBlocking(Args args) {
        if (!CarpetSettings.swordBlockHitting) return;
        if (!(((Object) this) instanceof PlayerSwordBlockInterface blocking)) return;
        if (blocking.carpet$getSwordBlockTicks() <= 0) return;
        args.set(0, ((Double) args.get(0)) * CarpetSettings.swordBlockKnockbackMultiplier);
    }
//? } else {
/*    // Before 26.1 hurtServer pushed every hit that is not NO_KNOCKBACK itself, with its own 0.4.
    @ModifyArgs(
            method = "hurtServer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDD)V")
    )
    private void reduceKnockbackWhileBlocking(Args args) {
        if (!CarpetSettings.swordBlockHitting) return;
        if (!(((Object) this) instanceof PlayerSwordBlockInterface blocking)) return;
        if (blocking.carpet$getSwordBlockTicks() <= 0) return;
        args.set(0, ((Double) args.get(0)) * CarpetSettings.swordBlockKnockbackMultiplier);
    }
*///?}
}
