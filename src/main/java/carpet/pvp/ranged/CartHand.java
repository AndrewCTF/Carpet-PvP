package carpet.pvp.ranged;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The hands of a bot that puts a rail and a tnt minecart down: the actions {@link BotBody} has no verb for.
 *
 * <p>Everything here is what a player does with the right mouse button and nothing else. A hotbar change takes a
 * tick to reach the hand, so a cell is put down over several ticks rather than one: the slot is asked for, the
 * next tick the item is in the hand, and then the click goes. The click is a use of <em>the item in the hand</em>,
 * which is not a detail: {@code ServerPlayerGameMode.useItemOn} builds its context from the hand and
 * {@code BlockItem.place} takes the cost off {@code context.getItemInHand()}, so a caller that hands the game a
 * stack of rails instead of selecting them lays a free rail and eats whatever the bot was holding, which for a
 * bot holding its bow is the bow.</p>
 *
 * <p>The view is not turned here. {@link #aimAt} hands the body an aim point and the body turns onto it once, the
 * way it does for a projectile: a style that turned the view onto its target and then onto the cell it is laying
 * would make the look controller plan a fresh movement twice a tick and never arrive at either. The click waits
 * for the view to be on the block and comes out of the body's own limiter, so the bot places at its click rate and
 * never places something it cannot see.</p>
 */
public final class CartHand
{
    /** How far inside a block cell the view has to be before the bot counts itself aimed at it. */
    private static final double AIM_INSET = 0.05;
    /**
     * Half the width of the cell the ray has to meet, which is also the dead zone the look controller is given
     * for it. A view that stops within the inscribed sphere of the cell always meets the cell, which is what makes
     * the wait for the view terminate.
     */
    private static final double AIM_RADIUS = 0.42D;

    private final BotBody body;

    public CartHand(BotBody body)
    {
        this.body = body;
    }

    private EntityPlayerMPFake bot()
    {
        return body.bot();
    }

    private ServerLevel level()
    {
        return (ServerLevel) bot().level();
    }

    /** The number of an item the bot still carries, counted over the whole inventory. */
    public int carried(Item item)
    {
        Inventory inventory = bot().getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
        {
            if (inventory.getItem(slot).is(item))
            {
                count += inventory.getItem(slot).getCount();
            }
        }
        return count;
    }

    /**
     * Asks for a hotbar slot, which as for a real client takes effect on the next tick. Reports whether the
     * bot is holding that slot already, so a caller waits a tick rather than clicking at the wrong thing.
     *
     * <p>The item the slot was scanned for is checked as well, because a slot is only a place: a bot that picks
     * up a block out of a crater takes the slot the rail was in, and a click with the wrong thing in the hand
     * still comes back a success, which is a click spent and nothing placed.</p>
     *
     * @param item what the caller means to put down, or null to ask for the slot alone
     */
    public boolean hold(int slot, Item item)
    {
        if (slot < 0)
        {
            return false;
        }
        if (slot != body.currentSlot() && slot != body.pendingSlot())
        {
            body.requestSlot(slot);
        }
        return body.currentSlot() == slot && (item == null || holding(item));
    }

    /** Asks for a hotbar slot without saying what should be in it. */
    public boolean hold(int slot)
    {
        return hold(slot, null);
    }

    /** True while the given item is the one in the main hand, which is what a click will use. */
    public boolean holding(Item item)
    {
        return bot().getMainHandItem().is(item);
    }

    /**
     * Where the view goes to click a cell, which is the middle of the cell rather than the middle of the face: a
     * ray aimed at the middle of a cell always meets it whatever angle the bot is standing at, and the face the
     * click reports is chosen by the caller instead.
     */
    public BotBody.Aim aimAt(BlockPos cell)
    {
        double dx = cell.getX() + 0.5D - bot().getX();
        double dy = cell.getY() + 0.5D - bot().getEyeY();
        double dz = cell.getZ() + 0.5D - bot().getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(flat, 1.0E-6D)));
        return new BotBody.Aim(yaw, pitch, (float) Math.toDegrees(Math.atan2(AIM_RADIUS, Math.max(flat, 0.1D))));
    }

    /** True when a ray from the bot's eyes along the view it has now meets the cell. */
    public boolean onCell(BlockPos cell)
    {
        double yaw = Math.toRadians(bot().getYRot());
        double pitch = Math.toRadians(bot().getXRot());
        return BotBody.rayHitsBox(bot().getX(), bot().getEyeY(), bot().getZ(),
                -Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch),
                cell.getX() + AIM_INSET, cell.getY() + AIM_INSET, cell.getZ() + AIM_INSET,
                cell.getX() + 1.0D - AIM_INSET, cell.getY() + 1.0D - AIM_INSET, cell.getZ() + 1.0D - AIM_INSET);
    }

    /**
     * Uses the item in the hand on a face of a block, as a client does, and reports what the game made of it.
     * The click costs the body's limiter, so a bot lays a rail and a cart at its own click rate.
     *
     * <p>The result is the game's own and not a yes or no: {@link InteractionResult#FAIL} consumes the action
     * as much as a success does, so a caller that only asks whether anything happened is told yes by a
     * placement that was refused — a cart onto no rail, say. {@link #placed} is what says whether something
     * went down.</p>
     */
    public InteractionResult click(BlockPos against, Direction face)
    {
        if (!onCell(against) || !body.click(true) || !bot().isWithinBlockInteractionRange(against, 1.0D))
        {
            return InteractionResult.PASS;
        }
        ItemStack hand = bot().getMainHandItem();
        if (hand.isEmpty())
        {
            return InteractionResult.PASS;
        }
        Vec3 on = Vec3.atCenterOf(against).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D,
                face.getStepZ() * 0.5D);
        InteractionResult result = bot().gameMode.useItemOn(bot(), level(), hand, InteractionHand.MAIN_HAND,
                new BlockHitResult(on, face, against, false));
        bot().resetLastActionTime();
        //? if >=26.3 {
        bot().swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        //?} else {
        /*bot().swing(InteractionHand.MAIN_HAND);
        *///?}
        return result;
    }

    /** Whether a result means the thing the click was for is down, which a refusal is not. */
    public static boolean placed(InteractionResult result)
    {
        return result instanceof InteractionResult.Success;
    }
}