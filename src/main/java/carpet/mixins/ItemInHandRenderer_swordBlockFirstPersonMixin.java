package carpet.mixins;

import carpet.client.SwordBlockVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
//~ if >=26.3 'ItemInHandRenderer' -> 'FirstPersonHandsAndItemsRenderer'
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
//? if >=26.3 {
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
//?}
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//~ if >=26.3 'ItemInHandRenderer' -> 'FirstPersonHandsAndItemsRenderer'
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class ItemInHandRenderer_swordBlockFirstPersonMixin {
    @Unique private boolean carpet$pushed;

    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void carpet$blockHitStart(
            //? if >=26.3 {
            PlayerRenderState playerRenderState,
            FirstPersonHandsAndItemsRenderState handsRenderState,
            //?} else {
            /*AbstractClientPlayer player,
            *///?}
            float partialTick,
            float pitch,
            InteractionHand hand,
            float swingProgress,
            ItemStack stack,
            float equipProgress,
            PoseStack poseStack,
            SubmitNodeCollector buffer,
            int packedLight,
            CallbackInfo ci
    ) {
        this.carpet$pushed = false;
        //? if >=26.3 {
        AbstractClientPlayer player = Minecraft.getInstance().player;
        //?}
        if (player == null) return;
        if (hand != InteractionHand.MAIN_HAND) return; // main-hand only
        if (stack.isEmpty() || !stack.is(ItemTags.SWORDS)) return;
        // a player really holding the sword up already gets the vanilla pose
        if (player.isUsingItem()) return;

        boolean holding = Minecraft.getInstance().options.keyUse.isDown();
        if (!holding && !SwordBlockVisuals.isActive(player)) return;

        poseStack.pushPose();
        this.carpet$pushed = true;

        float ease = holding ? 1.0f : Math.min(1.0f, SwordBlockVisuals.remaining(player) / 6.0f);
        ease = ease * ease;

        // Pre-rotate translation: move down in screen-space so tip height matches baseline.
        float drop = 0.16f * ease;
        poseStack.translate(0.0F, -drop, 0.0F);

        // Roll-only in-place tilt (Z axis). Strong left tilt (>45°) toward center.
        float baseAngle = 60.0f;
        float dir = (player.getMainArm() == HumanoidArm.RIGHT) ? 1.0f : -1.0f;
        float roll = dir * baseAngle * ease;
        //~ if >=26.3 'poseStack.mulPose(' -> 'poseStack.rotate('
        poseStack.rotate(new Quaternionf().rotationXYZ(0f, 0f, (float) Math.toRadians(roll)));
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void carpet$blockHitEnd(
            //? if >=26.3 {
            PlayerRenderState playerRenderState,
            FirstPersonHandsAndItemsRenderState handsRenderState,
            //?} else {
            /*AbstractClientPlayer player,
            *///?}
            float partialTick,
            float pitch,
            InteractionHand hand,
            float swingProgress,
            ItemStack stack,
            float equipProgress,
            PoseStack poseStack,
            SubmitNodeCollector buffer,
            int packedLight,
            CallbackInfo ci
    ) {
        if (this.carpet$pushed) {
            poseStack.popPose();
            this.carpet$pushed = false;
        }
    }
}