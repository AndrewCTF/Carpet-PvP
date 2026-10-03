package carpet.mixins;

import carpet.CarpetSettings;
import carpet.fakes.PlayerSwordBlockInterface;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class Player_swordBlockStateMixin extends LivingEntity implements PlayerSwordBlockInterface {
    @Unique private int carpet$swordBlockTicks;
    @Unique private float carpet$pendingKbMultiplier = 1.0F;

    protected Player_swordBlockStateMixin(EntityType<? extends LivingEntity> entityType, Level level) { super(entityType, level); }

    @Override public void carpet$setSwordBlockTicks(int ticks) { this.carpet$swordBlockTicks = ticks; }
    @Override public int carpet$getSwordBlockTicks() { return this.carpet$swordBlockTicks; }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tickSwordBlock(CallbackInfo ci) {
        if (this.carpet$swordBlockTicks > 0) this.carpet$swordBlockTicks--;
        this.carpet$pendingKbMultiplier = 1.0F;
    }

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void applySwordBlock(ServerLevel serverLevel, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!CarpetSettings.swordBlockHitting) return;
        // Check if using a sword (via ItemTags check instead of consumable)
        if (this.isUsingItem() && this.getUseItem().is(ItemTags.SWORDS) && amount > 0.0f) {
            float reduced = (float) Math.max(0.0, amount * CarpetSettings.swordBlockDamageMultiplier);
            this.carpet$pendingKbMultiplier = (float) CarpetSettings.swordBlockKnockbackMultiplier;
            boolean result = super.hurtServer(serverLevel, source, reduced);
            //? if >=26.3 {
            this.setInvulnerableTime(Math.max(this.getInvulnerableTime(), 5));
            //?} else {
            /*this.invulnerableTime = Math.max(this.invulnerableTime, 5);
            *///?}
            cir.setReturnValue(result);
        }
    }
}
