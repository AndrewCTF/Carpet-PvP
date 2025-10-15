package carpet.mixins;

import carpet.CarpetSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntity_damageTicksOverrideMixin {

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void overrideDamageTicks(ServerLevel serverLevel, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return; // damage not applied
        if (!CarpetSettings.damageTickOverrides) return;
        int ticks = resolveTicks(source);
        if (ticks >= 0) {
            java.lang.reflect.Field f = findInvulnerableField();
            if (f != null) {
                try {
                    int current = f.getInt(this);
                    f.setInt(this, Math.max(current, ticks));
                } catch (IllegalAccessException ignored) {}
            }
        }
    }

    private static java.lang.reflect.Field invulnerableField;
    private static java.lang.reflect.Field findInvulnerableField() {
        if (invulnerableField != null) return invulnerableField;
        Class<?> cls = LivingEntity.class;
        try {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (f.getType() == int.class && f.getName().toLowerCase().contains("invulner")) {
                    f.setAccessible(true);
                    invulnerableField = f;
                    return invulnerableField;
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    private static int resolveTicks(DamageSource source) {
        // explosions
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return CarpetSettings.damageTickExplosion;
        }
        // projectiles
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            return CarpetSettings.damageTickProjectile;
        }
        // melee attacker checks
        Entity attacker = source.getEntity();
        if (attacker instanceof LivingEntity livingAttacker) {
            ItemStack stack = livingAttacker.getMainHandItem();
            if (stack.is(ItemTags.SWORDS)) return CarpetSettings.damageTickSword;
            if (stack.is(ItemTags.AXES)) return CarpetSettings.damageTickAxe;
            if (stack.is(Items.TRIDENT)) return CarpetSettings.damageTickTrident;
            return CarpetSettings.damageTickMeleeOther;
        }
        return CarpetSettings.damageTickOther;
    }
}
