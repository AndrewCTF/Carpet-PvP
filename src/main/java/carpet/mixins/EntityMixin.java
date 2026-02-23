package carpet.mixins;

import carpet.fakes.EntityInterface;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin implements EntityInterface
{
    @Shadow
    public float yRot;
    
    @Shadow
    public float yRotO;

    @Shadow public @Nullable abstract LivingEntity getControllingPassenger();

    @Shadow public Level level;

    @Override
    public float getMainYaw(float partialTicks)
    {
        return partialTicks == 1.0F ? this.yRot : Mth.lerp(partialTicks, this.yRotO, this.yRot);
    }

    @Inject(method = "isLocalInstanceAuthoritative", at = @At("HEAD"), cancellable = true)
    private void isFakePlayer(CallbackInfoReturnable<Boolean> cir)
    {
        if (getControllingPassenger() instanceof EntityPlayerMPFake) cir.setReturnValue(!level.isClientSide());
    }

    @Shadow
    public abstract boolean onGround();

    @Shadow
    public abstract Vec3 getDeltaMovement();

    // Record fake player fall distance.
    @Inject(method = "tick", at = @At("TAIL"))
    private void updateFallDistance(CallbackInfo ci) {
        Entity entity = (Entity)(Object)this;

        if (entity instanceof EntityPlayerMPFake) {
            if (entity.onGround() && entity.fallDistance > 0.0) {
                // Reset fall distance when the entity hits the ground
                entity.fallDistance = 0;
            } else if (!entity.onGround() && this.getDeltaMovement().y < 0.0) {
                // Otherwise, increase fall distance based on y movement delta
                entity.fallDistance += Math.abs(entity.getDeltaMovement().y);
            }
        }
    }
}
