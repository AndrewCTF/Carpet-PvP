package carpet.mixins;

import carpet.CarpetSettings;
import carpet.utils.DelayedTasks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class Player_shieldStunMixin extends LivingEntity {

    protected Player_shieldStunMixin(EntityType<? extends LivingEntity> entityType, Level level) { super(entityType, level); }

    @Inject(method = "blockUsingItem", at = @At("HEAD"))
    private void onShieldDisabled(ServerLevel serverLevel, LivingEntity livingEntity, DamageSource damageSource, float damage/*? if >=26.3 {*/, boolean fullyBlocked/*?}*/, CallbackInfo ci) {
        MinecraftServer server = serverLevel.getServer();
        if (CarpetSettings.shieldStunning && server != null) {
            // The shield stun is set while the hit is processed, so clear it again on the next tick.
            DelayedTasks.scheduleNextTick(server, () -> {
                //~ if >=26.3 'this.invulnerableTime = 0' -> 'this.setInvulnerableTime(0)'
                this.setInvulnerableTime(0);
            });
        }
    }
}
