package carpet.mixins;

import carpet.CarpetSettings;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
public abstract class Player_attackResetTicker_18CombatMixin {

    @Redirect(method = "onAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;resetOnlyAttackStrengthTicker()V"))
    private void carpet$noResetCooldown(Player instance) {
        if (!CarpetSettings.spamClickCombat) {
            instance.resetOnlyAttackStrengthTicker();
        }
        // else do nothing => no cooldown
    }
}
