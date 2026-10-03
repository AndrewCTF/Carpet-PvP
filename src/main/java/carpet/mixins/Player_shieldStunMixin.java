package carpet.mixins;

import carpet.CarpetSettings;
import net.minecraft.server.level.ServerLevel;
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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mixin(Player.class)
public abstract class Player_shieldStunMixin extends LivingEntity {

    @Unique
    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    protected Player_shieldStunMixin(EntityType<? extends LivingEntity> entityType, Level level) { super(entityType, level); }

    @Inject(method = "blockUsingItem", at = @At("HEAD"))
    private void onShieldDisabled(ServerLevel serverLevel, LivingEntity livingEntity, DamageSource damageSource, float damage/*? if >=26.3 {*/, boolean fullyBlocked/*?}*/, CallbackInfo ci) {
        if (CarpetSettings.shieldStunning) {
            executor.schedule(() -> {
                //~ if >=26.3 'this.invulnerableTime = 0' -> 'this.setInvulnerableTime(0)'
                this.setInvulnerableTime(0);
            }, 1, TimeUnit.MILLISECONDS);
        }
    }
}