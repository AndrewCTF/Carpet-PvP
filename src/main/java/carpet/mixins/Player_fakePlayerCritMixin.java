package carpet.mixins;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Player.class)
public abstract class Player_fakePlayerCritMixin {

    // Targeting the attack method and modifying the boolean variable that stores the result of canCriticalAttack()
    @ModifyVariable(
        method = "attack",  // Target the attack method
        at = @At(value = "STORE", target = "Lnet/minecraft/world/entity/player/Player;canCriticalAttack()Z"),  // Find the store location for the boolean result
        ordinal = 0         // Use the first occurrence
    )
    private boolean modifyCriticalHitResult(boolean originalCanCritical) {
        // Modify the result of canCriticalAttack() for fake players based on fall distance and player state
        Player player = (Player)(Object)this;
        if (player instanceof EntityPlayerMPFake) {
            // Check critical hit conditions for fake player
            return player.fallDistance > 0.0D
                && !player.onGround()
                && !player.onClimbable()
                && !player.isInWater()
                && !player.isMobilityRestricted()
                && !player.isPassenger()
                && !player.isSprinting();
        }
        return originalCanCritical;  // Otherwise, keep the original result
    }
}