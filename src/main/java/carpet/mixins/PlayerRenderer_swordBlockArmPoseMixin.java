package carpet.mixins;

import carpet.client.SwordBlockVisuals;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public class PlayerRenderer_swordBlockArmPoseMixin {
    @Inject(method = "getArmPose", at = @At("HEAD"), cancellable = true)
    private static void carpet$forceBlockPose(Avatar avatar, HumanoidArm arm, CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
        if (!(avatar instanceof AbstractClientPlayer player)) return;
        if (!SwordBlockVisuals.isActive(player)) return;
        if (player.isUsingItem()) return;
        InteractionHand hand = (arm == player.getMainArm()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        if (player.getItemInHand(hand).is(ItemTags.SWORDS)) {
            cir.setReturnValue(HumanoidModel.ArmPose.BLOCK);
        }
    }
}
