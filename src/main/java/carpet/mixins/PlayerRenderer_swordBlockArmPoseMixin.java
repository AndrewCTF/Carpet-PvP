package carpet.mixins;

import carpet.client.SwordBlockVisuals;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
// no explicit imports for UseAnim/Consumable to avoid mapping issues; use var and string compare
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerRenderer.class)
public class PlayerRenderer_swordBlockArmPoseMixin {
    @Inject(method = "getArmPose", at = @At("HEAD"), cancellable = true)
    private void carpet$forceBlockPose(AbstractClientPlayer player, HumanoidArm arm, CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
    // Only intervene for our S2C visual window; do not force during actual use (vanilla handles that)
    if (!SwordBlockVisuals.isActive(player)) return;
    if (player.isUsingItem()) return;
        // Map the arm being rendered to the corresponding hand for item lookup
        InteractionHand hand = (arm == player.getMainArm()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    if (player.getItemInHand(hand).is(ItemTags.SWORDS)) {
            cir.setReturnValue(HumanoidModel.ArmPose.BLOCK);
        }
    }
}
