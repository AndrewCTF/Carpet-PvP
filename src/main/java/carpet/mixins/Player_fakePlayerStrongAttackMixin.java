package carpet.mixins;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class Player_fakePlayerStrongAttackMixin
{
//? if <26.1 {
/*    private static final String KNOCKBACK_TARGET = "Lnet/minecraft/world/entity/player/Player;causeExtraKnockback(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/phys/Vec3;)V";
*///?} else {
    private static final String KNOCKBACK_TARGET = "Lnet/minecraft/world/entity/player/Player;causeExtraKnockback(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/damagesource/DamageSource;FZ)V";
//?}

    private static final float FAKE_PLAYER_STRONG_ATTACK_THRESHOLD = 0.8F;
    private static final float FAKE_PLAYER_ATTACK_TICK_DELTA = 1.5F;

    @Unique
    private boolean carpet$fakePlayerTreatAttackAsStrong;
    @Unique
    private boolean carpet$fakePlayerAddSprintKnockback;

    @Inject(method = "attack", at = @At("HEAD"))
    private void carpet$captureFakePlayerAttackStrength(CallbackInfo ci)
    {
        Player player = (Player) (Object) this;
        carpet$fakePlayerTreatAttackAsStrong = player instanceof EntityPlayerMPFake
                && player.getAttackStrengthScale(FAKE_PLAYER_ATTACK_TICK_DELTA) > FAKE_PLAYER_STRONG_ATTACK_THRESHOLD;
        carpet$fakePlayerAddSprintKnockback = carpet$fakePlayerTreatAttackAsStrong && player.isSprinting();
    }

    @ModifyArg(
            method = "attack",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;attackVisualEffects(Lnet/minecraft/world/entity/Entity;ZZZZF)V"
            ),
            index = 3
    )
    private boolean carpet$fakePlayerStrongAttackVisual(boolean original)
    {
        return original || carpet$fakePlayerTreatAttackAsStrong;
    }

    @ModifyArg(
            method = "attack",
            at = @At(
                    value = "INVOKE",
                    target = KNOCKBACK_TARGET
            ),
            index = 1
    )
    private float carpet$fakePlayerSprintKnockback(float original)
    {
        return carpet$fakePlayerAddSprintKnockback && original < 0.5F ? original + 0.5F : original;
    }

    @Inject(
            method = "causeExtraKnockback",
            at = @At("HEAD")
    )
//? if <26.1 {
/*    private void carpet$fakePlayerSprintKnockbackSound(Entity target, float strength, Vec3 oldTargetVelocity, CallbackInfo ci)
*///?} else {
    private void carpet$fakePlayerSprintKnockbackSound(Entity target, float strength, Vec3 oldTargetVelocity, DamageSource damageSource, float damage, boolean comesFromEffect, CallbackInfo ci)
//?}
    {
        if (!carpet$fakePlayerAddSprintKnockback)
        {
            return;
        }

        Player player = (Player) (Object) this;
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, player.getSoundSource(), 1.0F, 1.0F);
    }
}
